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
     * Sell price for a point in the window.
     *
     * Rungs are absolute offsets from the window open, so the ladder walks
     * itself up as the window runs down: cheap while the outcome can still
     * flip, near par once the TWAP has almost no room left. Before the first
     * rung's offset the first price applies, so a position opened early is
     * never left without a resting sell.
     */
    fun exitPriceFor(ladder: List<ExitStep>, elapsedSec: Long): Double {
        if (ladder.isEmpty()) return 0.99
        val sorted = ladder.sortedBy { it.fromSec }
        return (sorted.lastOrNull { it.fromSec <= elapsedSec } ?: sorted.first()).price
    }

    /**
     * Forecast of the settlement TWAP.
     *
     * These markets settle on the average of the feed over the last 30 seconds,
     * not on the price at the close. Two consequences, and the second is the
     * one that bites:
     *
     * - The spread of the settlement is the spread of an *average*, which is
     *   narrower than the spread of the endpoint.
     * - Once the averaging window has opened, part of the answer is already
     *   fixed. A move at that point only enters the average in proportion to
     *   the time left: with 10 seconds to go, a jump counts for a third of
     *   itself. Treating the current price as the forecast overstates a late
     *   move threefold, which is exactly when a bot is most tempted to act.
     *
     * @param spot latest feed value
     * @param observedMean average of the feed inside the averaging window so
     *        far, ignored until the window has opened
     * @param sigmaPerSec price-unit volatility per second
     */
    fun twapForecast(
        spot: Double,
        observedMean: Double?,
        secondsToClose: Double,
        sigmaPerSec: Double,
        twapWindowSec: Double = TWAP_WINDOW_SECONDS,
        tickSpacingSec: Double = 1.0,
    ): TwapForecast {
        val tau = maxOf(secondsToClose, 0.0)
        val w = twapWindowSec
        val dt = maxOf(tickSpacingSec, 0.001)
        val windowTicks = maxOf(1.0, w / dt)

        if (tau >= w) {
            // The averaging has not started, so the whole window lies ahead.
            // Var of the mean of the window's ticks is σ²(s + spread), with s
            // the wait until averaging begins.
            val s = tau - w
            val variance =
                sigmaPerSec * sigmaPerSec * (s + meanSpreadFactor(windowTicks, dt))
            return TwapForecast(spot, sqrt(maxOf(variance, 0.0)))
        }

        // Inside the window: a known part, and an unknown part that shrinks.
        val observedFraction = (w - tau) / w
        val mean = if (observedMean == null) {
            spot
        } else {
            observedFraction * observedMean + (1 - observedFraction) * spot
        }

        val remainingTicks = maxOf(1.0, tau / dt)
        // Var of the sum of the remaining ticks, divided by the full window
        // count squared.
        val sumVariance =
            dt * remainingTicks * (remainingTicks + 1) * (2 * remainingTicks + 1) / 6.0
        val variance =
            sigmaPerSec * sigmaPerSec * sumVariance / (windowTicks * windowTicks)
        return TwapForecast(mean, sqrt(maxOf(variance, 0.0)))
    }

    /**
     * Spread of the mean of `n` Brownian samples spaced `dt` apart, in σ² units.
     *
     * The continuous limit of this is w/3, and using that limit understates the
     * spread — by 5% of variance at a one-second tick, and much more in the
     * last seconds of the window. Understating spread is the direction that
     * makes a model overconfident, so the discrete form is the one to keep.
     */
    private fun meanSpreadFactor(n: Double, dt: Double): Double =
        dt * (n + 1) * (2 * n + 1) / (6.0 * n)

    /**
     * Volatility measured at the horizon that matters, rather than extrapolated
     * from one-second ticks.
     *
     * The settlement feed is itself a moving average, so its one-second
     * increments are positively autocorrelated. Scaling them by √h understates
     * the spread over h seconds, and an understated spread makes the model
     * overconfident — which is how a bot talks itself into an edge that is not
     * there. Measuring increments directly at h sidesteps the whole question.
     */
    fun horizonVolPerSec(ticks: List<Tick>, horizonSec: Double): Double? {
        if (horizonSec <= 0) return null
        val horizonMs = (horizonSec * 1000).toLong()
        if (ticks.size < 30) return null

        val diffs = ArrayList<Double>()
        var j = 0
        for (i in ticks.indices) {
            val target = ticks[i].timestamp + horizonMs
            while (j < ticks.size && ticks[j].timestamp < target) j++
            if (j >= ticks.size) break
            // Overlapping windows: fewer independent samples, but far less
            // estimation noise than carving the history into disjoint blocks.
            diffs.add(ticks[j].value - ticks[i].value)
        }
        if (diffs.size < 10) return null

        val mean = diffs.average()
        val variance = diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1)
        val sd = sqrt(maxOf(variance, 0.0))
        if (!sd.isFinite() || sd <= 0.0) return null
        return sd / sqrt(horizonSec)
    }

    data class Settlement(
        val soldShares: Double,
        val proceedsUsd: Double,
        val heldShares: Double,
        val pnlUsd: Double,
    )

    /**
     * What a finished window was worth.
     *
     * Shares leave a position two ways — filled by the resting ladder, or sold
     * at market by a take-profit — and only what is left rides on the outcome.
     * Costs and proceeds arrive here already net of fees, so this is pure
     * arithmetic with nowhere for a fee to hide.
     */
    fun settlementPnl(
        entryShares: Double,
        entryCostUsd: Double,
        ladderFills: List<Pair<Double, Double>>,
        soldAtMarket: Double,
        marketProceedsUsd: Double,
        outcomeWon: Boolean,
    ): Settlement {
        val ladderShares = ladderFills.sumOf { it.first }
        val ladderProceeds = ladderFills.sumOf { it.first * it.second }

        val soldShares = ladderShares + soldAtMarket
        val proceeds = ladderProceeds + marketProceedsUsd
        // Never claim to hold more than was bought, however the fills arrived.
        val heldShares = (entryShares - soldShares).coerceAtLeast(0.0)
        val redeemed = if (outcomeWon) heldShares else 0.0

        return Settlement(
            soldShares = soldShares,
            proceedsUsd = proceeds,
            heldShares = heldShares,
            pnlUsd = proceeds + redeemed - entryCostUsd,
        )
    }

    /**
     * Taker fee per share, in the same units as the probability.
     *
     * Polymarket charges `rate · (p(1−p))^exponent` per share, which peaks at
     * an even-money price — exactly where these markets trade. At 50c it is
     * 1.75c a share, so an edge under that is a loss dressed as a trade.
     */
    fun takerFeePerShare(price: Double, rate: Double, exponent: Double): Double {
        val base = price * (1 - price)
        if (base <= 0) return 0.0
        val fee = rate * Math.pow(base, exponent)
        return if (fee.isFinite() && fee > 0) fee else 0.0
    }

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

    /**
     * Probability that the settlement lands at or above the strike.
     *
     * @param observedWindowMean mean of the feed inside the averaging window so
     *        far, null until the window opens
     * @param calibration shrinkage toward 50/50, learned from settled windows
     */
    fun fairValue(
        strike: Double,
        spot: Double,
        msToClose: Long,
        ticks: List<Tick>,
        observedWindowMean: Double? = null,
        calibration: Double = 1.0,
    ): FairValue? {
        val secondsToClose = msToClose / 1000.0

        // Measure volatility over a horizon near the one being forecast, and
        // fall back to scaled tick-to-tick vol only when history is short.
        val horizon = clamp(secondsToClose, 10.0, 60.0)
        val sigmaPrice = horizonVolPerSec(ticks, horizon)
            ?: (perSecondVolatility(ticks)?.times(spot))
            ?: return null
        if (!sigmaPrice.isFinite() || sigmaPrice <= 0.0) return null

        val forecast = twapForecast(
            spot = spot,
            observedMean = observedWindowMean,
            secondsToClose = secondsToClose,
            sigmaPerSec = sigmaPrice,
        )
        if (forecast.sd <= 0.0 || !forecast.sd.isFinite()) return null

        val drift = forecast.mean - strike
        val raw = normalCdf(drift / forecast.sd)
        // Shrinking toward 50/50 costs a little edge when the model is right and
        // saves a lot when it is not, which is the trade worth making.
        val shrunk = 0.5 + clamp(calibration, 0.0, 1.0) * (raw - 0.5)

        return FairValue(
            pUp = clamp(shrunk, 0.0001, 0.9999),
            rawPUp = clamp(raw, 0.0001, 0.9999),
            sigmaPerSec = sigmaPrice / maxOf(spot, 1.0),
            sigmaHorizon = forecast.sd,
            drift = drift,
        )
    }

    fun decide(
        settings: Settings,
        fair: FairValue?,
        upAsk: Double?,
        downAsk: Double?,
        feeRate: Double = 0.0,
        feeExponent: Double = 1.0,
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

        // Edge net of the taker fee. The fee peaks at an even-money price,
        // which is exactly where these markets sit, so ignoring it flatters
        // every marginal trade.
        fun netEdge(probability: Double, price: Double) =
            probability - price - takerFeePerShare(price, feeRate, feeExponent)

        val candidates = ArrayList<Triple<String, Double, Double>>(2)
        if (inBand(upAsk)) candidates.add(Triple("Up", upAsk!!, netEdge(fair.pUp, upAsk)))
        if (inBand(downAsk)) {
            candidates.add(Triple("Down", downAsk!!, netEdge(1.0 - fair.pUp, downAsk)))
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
            val fee = takerFeePerShare(best.second, feeRate, feeExponent)
            return Decision(
                false, null, 0.0, 0.0,
                "преимущество после комиссии " + String.format("%.1f", best.third * 100) +
                    "% < порога " + String.format("%.1f", settings.minEdge * 100) +
                    "% (комиссия " + String.format("%.1f", fee * 100) + "¢/долю)",
            )
        }

        val modelled = if (best.first == "Up") fair.pUp else 1.0 - fair.pUp
        val fee = takerFeePerShare(best.second, feeRate, feeExponent)
        return Decision(
            true, best.first, best.second, best.third,
            "модель даёт " + String.format("%.1f", modelled * 100) +
                "% против цены " + String.format("%.0f", best.second * 100) +
                "¢, комиссия " + String.format("%.1f", fee * 100) +
                "¢ — чистое преимущество " + String.format("%.1f", best.third * 100) + "%",
        )
    }
}
