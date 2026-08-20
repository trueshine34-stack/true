package com.polybot.btc5m.bot

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pricing model for the 5-minute window.
 *
 * The market resolves on a 30-second TWAP taken at the end of the window, so
 * the effective horizon runs to the centre of that average rather than to the
 * close, and the averaging damps the terminal variance.
 */
object Strategy {

    /** Abramowitz & Stegun 7.1.26 — ample precision for a pricing decision. */
    fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t -
            0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }

    fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    fun clamp(x: Double, lo: Double, hi: Double): Double = minOf(hi, maxOf(lo, x))

    /**
     * Per-second volatility of log returns. Ticks arrive roughly once a second
     * but the spacing is not exact, so each return is normalised by its own
     * elapsed time before being pooled.
     */
    fun perSecondVolatility(ticks: List<Tick>): Double? {
        if (ticks.size < 8) return null

        val normalised = ArrayList<Double>(ticks.size)
        for (i in 1 until ticks.size) {
            val dtSec = (ticks[i].timestamp - ticks[i - 1].timestamp) / 1000.0
            if (dtSec <= 0) continue
            val prev = ticks[i - 1].value
            val curr = ticks[i].value
            if (prev <= 0 || curr <= 0) continue
            normalised.add(ln(curr / prev) / sqrt(dtSec))
        }
        if (normalised.size < 5) return null

        val mean = normalised.average()
        val variance = normalised.sumOf { (it - mean) * (it - mean) } / (normalised.size - 1)
        val sigma = sqrt(maxOf(variance, 0.0))
        return if (sigma.isFinite() && sigma > 0) sigma else null
    }

    fun fairValue(strike: Double, spot: Double, msToClose: Long, ticks: List<Tick>): FairValue? {
        val sigmaPerSec = perSecondVolatility(ticks) ?: return null

        val secondsToClose = msToClose / 1000.0
        val horizon = maxOf(secondsToClose - TWAP_WINDOW_SECONDS / 2, 1.0)
        val twapDamping = sqrt(
            clamp(1.0 - TWAP_WINDOW_SECONDS / (3.0 * maxOf(secondsToClose, 1.0)), 0.25, 1.0),
        )

        val sigmaHorizon = sigmaPerSec * sqrt(horizon) * spot * twapDamping
        if (sigmaHorizon <= 0.0 || !sigmaHorizon.isFinite()) return null

        val drift = spot - strike
        val pUp = clamp(normalCdf(drift / sigmaHorizon), 0.0001, 0.9999)
        return FairValue(pUp, sigmaPerSec, sigmaHorizon, drift)
    }

    fun decide(
        settings: Settings,
        fair: FairValue?,
        upAsk: Double?,
        downAsk: Double?,
    ): Decision {
        if (settings.mode == StrategyMode.OFF) {
            return Decision(false, null, 0.0, 0.0, "режим \"не торговать\"")
        }
        if (fair == null) {
            return Decision(
                false, null, 0.0, 0.0,
                "мало тиков Chainlink для оценки волатильности",
            )
        }

        fun inBand(p: Double?) =
            p != null && p >= settings.minPrice && p <= settings.maxPrice

        val candidates = ArrayList<Triple<String, Double, Double>>(2)
        if (inBand(upAsk)) candidates.add(Triple("Up", upAsk!!, fair.pUp - upAsk))
        if (inBand(downAsk)) {
            candidates.add(Triple("Down", downAsk!!, 1.0 - fair.pUp - downAsk))
        }

        if (candidates.isEmpty()) {
            return Decision(
                false, null, 0.0, 0.0,
                "нет котировок в коридоре ${settings.minPrice}–${settings.maxPrice}",
            )
        }

        if (settings.mode == StrategyMode.MOMENTUM || settings.mode == StrategyMode.CONTRARIAN) {
            val leading = if (fair.drift >= 0) "Up" else "Down"
            val wanted = if (settings.mode == StrategyMode.MOMENTUM) {
                leading
            } else {
                if (leading == "Up") "Down" else "Up"
            }
            val pick = candidates.firstOrNull { it.first == wanted }
                ?: return Decision(
                    false, null, 0.0, 0.0,
                    "сторона $wanted вне ценового коридора",
                )
            val reason = if (settings.mode == StrategyMode.MOMENTUM) {
                "по тренду: BTC ${if (fair.drift >= 0) "выше" else "ниже"} страйка на " +
                    String.format("%.2f", fair.drift) + " $"
            } else {
                "против тренда: берём отстающую сторону"
            }
            return Decision(true, pick.first, pick.second, pick.third, reason)
        }

        val best = candidates.maxByOrNull { it.third }!!
        if (best.third < settings.minEdge) {
            return Decision(
                false, null, 0.0, 0.0,
                "лучшее преимущество " + String.format("%.1f", best.third * 100) +
                    "% < порога " + String.format("%.1f", settings.minEdge * 100) + "%",
            )
        }

        val modelled = if (best.first == "Up") fair.pUp else 1.0 - fair.pUp
        return Decision(
            true, best.first, best.second, best.third,
            "модель даёт " + String.format("%.1f", modelled * 100) +
                "% против цены " + String.format("%.0f", best.second * 100) + "¢",
        )
    }
}
