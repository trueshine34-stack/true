package com.polybot.btc5m.bot

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monte Carlo check on the settlement forecast.
 *
 * The formula is only worth trusting if simulated paths agree with it, so this
 * simulates the actual settlement rule — the mean of the feed over the last 30
 * seconds — and compares the resulting distribution against what the closed
 * form predicts.
 */
class TwapModelTest {

    private val w = TWAP_WINDOW_SECONDS
    private val sigma = 3.0 // price units per second
    private val paths = 40_000

    /** One realisation of the settlement, stepping the feed once per second. */
    private fun simulate(random: Random, spot: Double, secondsToClose: Int): Double {
        var price = spot
        val tail = ArrayList<Double>(secondsToClose)
        for (second in 1..secondsToClose) {
            price += sigma * random.nextGaussian()
            // A tick belongs to the averaging window if it lands inside the
            // final 30 seconds.
            if (secondsToClose - second < w) tail.add(price)
        }
        return if (tail.isEmpty()) price else tail.average()
    }

    @Test
    fun `forecast spread matches simulated settlements before the window opens`() {
        for (tau in listOf(300, 180, 60, 30)) {
            val random = Random(tau.toLong())
            val samples = DoubleArray(paths) { simulate(random, 1000.0, tau) }
            val mean = samples.average()
            val sd = sqrt(samples.sumOf { (it - mean) * (it - mean) } / (paths - 1))

            val forecast = Strategy.twapForecast(
                spot = 1000.0,
                observedMean = null,
                secondsToClose = tau.toDouble(),
                sigmaPerSec = sigma,
            )

            assertEquals("mean at ${tau}s", 1000.0, forecast.mean, 4 * sd / sqrt(paths.toDouble()))
            // The forecast models the same discrete once-a-second sampling the
            // simulation uses, so this should be tight.
            assertTrue(
                "sd at ${tau}s: formula ${forecast.sd}, simulated $sd",
                abs(forecast.sd / sd - 1.0) < 0.015,
            )
        }
    }

    @Test
    fun `forecast is calibrated as a probability`() {
        // The number the bot actually trades on is P(settlement >= strike).
        for (tau in listOf(240, 120, 45)) {
            for (offset in listOf(-40.0, 0.0, 30.0)) {
                val random = Random((tau * 31 + offset.toInt()).toLong())
                val strike = 1000.0 + offset
                val hits = (0 until paths).count { simulate(random, 1000.0, tau) >= strike }
                val empirical = hits.toDouble() / paths

                val forecast = Strategy.twapForecast(
                    spot = 1000.0,
                    observedMean = null,
                    secondsToClose = tau.toDouble(),
                    sigmaPerSec = sigma,
                )
                val modelled = Strategy.normalCdf((forecast.mean - strike) / forecast.sd)

                assertTrue(
                    "p at ${tau}s offset $offset: model $modelled, simulated $empirical",
                    abs(modelled - empirical) < 0.02,
                )
            }
        }
    }

    @Test
    fun `a late move counts only in proportion to the time left`() {
        // With 10 of the 30 averaging seconds remaining, two thirds of the
        // settlement is already fixed, so a jump enters at one third.
        val observedMean = 1000.0
        val jumped = Strategy.twapForecast(
            spot = 1030.0,
            observedMean = observedMean,
            secondsToClose = 10.0,
            sigmaPerSec = sigma,
        )
        assertEquals(1010.0, jumped.mean, 1e-9)

        // Treating the current price as the forecast would have claimed +30.
        assertTrue("late move must not be taken at face value", jumped.mean < 1030.0)
    }

    @Test
    fun `forecast spread matches simulation inside the averaging window`() {
        for (tau in listOf(25, 15, 8, 3)) {
            val random = Random(tau.toLong() * 977)
            val observedSeconds = (w - tau).toInt()

            // Replay a path that is already part way through the averaging
            // window, then compare the spread of the finished settlements.
            val settlements = DoubleArray(paths) {
                var price = 1000.0
                val observed = ArrayList<Double>(observedSeconds)
                for (i in 1..observedSeconds) {
                    price += sigma * random.nextGaussian()
                    observed.add(price)
                }
                val spotNow = price
                val rest = ArrayList<Double>(tau)
                for (i in 1..tau) {
                    price += sigma * random.nextGaussian()
                    rest.add(price)
                }
                val settlement = (observed.sum() + rest.sum()) / (observed.size + rest.size)
                // Store the deviation from what was knowable at the cut point.
                val forecast = Strategy.twapForecast(
                    spot = spotNow,
                    observedMean = if (observed.isEmpty()) null else observed.average(),
                    secondsToClose = tau.toDouble(),
                    sigmaPerSec = sigma,
                )
                settlement - forecast.mean
            }

            val mean = settlements.average()
            val sd = sqrt(settlements.sumOf { (it - mean) * (it - mean) } / (paths - 1))
            val predicted = Strategy.twapForecast(
                1000.0, 1000.0, tau.toDouble(), sigma,
            ).sd

            // An unbiased forecast leaves residuals centred on zero.
            assertTrue("residual mean at ${tau}s was $mean", abs(mean) < 4 * sd / sqrt(paths.toDouble()))
            assertTrue(
                "sd at ${tau}s: formula $predicted, simulated $sd",
                abs(predicted / sd - 1.0) < 0.02,
            )
        }
    }

    @Test
    fun `inside the window the forecast collapses toward what is already known`() {
        var previous = Double.MAX_VALUE
        for (tau in listOf(30.0, 20.0, 10.0, 5.0, 1.0)) {
            val sd = Strategy.twapForecast(1000.0, 1000.0, tau, sigma).sd
            assertTrue("sd must shrink as the window closes", sd < previous)
            previous = sd
        }
        assertTrue("sd approaches zero at the close", previous < 0.2)
    }

    @Test
    fun `taker fee peaks at an even-money price`() {
        val rate = 0.07
        val atEven = Strategy.takerFeePerShare(0.5, rate, 1.0)
        assertEquals(0.0175, atEven, 1e-9)
        assertTrue(Strategy.takerFeePerShare(0.9, rate, 1.0) < atEven)
        assertTrue(Strategy.takerFeePerShare(0.1, rate, 1.0) < atEven)
        assertEquals(0.0, Strategy.takerFeePerShare(1.0, rate, 1.0), 1e-9)
    }

    @Test
    fun `horizon volatility recovers the true spread of a smoothed feed`() {
        // A feed that is itself a moving average has autocorrelated increments,
        // so one-second vol scaled by sqrt(h) understates the h-second spread.
        val random = Random(7)
        val raw = DoubleArray(1200)
        var price = 1000.0
        for (i in raw.indices) {
            price += sigma * random.nextGaussian()
            raw[i] = price
        }
        val smoothWindow = 10
        val ticks = ArrayList<Tick>()
        for (i in smoothWindow until raw.size) {
            val avg = (i - smoothWindow + 1..i).sumOf { raw[it] } / smoothWindow
            ticks.add(Tick(1_000_000L + i * 1000L, avg))
        }

        val naive = Strategy.perSecondVolatility(ticks)!! * ticks.last().value
        val atHorizon = Strategy.horizonVolPerSec(ticks, 30.0)!!

        assertTrue(
            "smoothing must depress the one-second estimate: naive $naive vs horizon $atHorizon",
            naive < atHorizon,
        )
        // The horizon estimate should land near the true underlying vol.
        assertTrue("horizon estimate $atHorizon vs true $sigma", abs(atHorizon / sigma - 1) < 0.3)
    }
}

/**
 * The self-calibration is the safety net under every other improvement: if the
 * model is overconfident, this is what notices and turns the confidence down.
 */
class CalibrationTest {

    /** Draws outcomes whose true probability is `factor` of the model's lean. */
    private fun feed(factor: Double, samples: Int, seed: Long): Calibration {
        val random = Random(seed)
        var calibration = Calibration()
        repeat(samples) {
            val modelled = 0.5 + (random.nextDouble() - 0.5) * 0.8
            val truth = 0.5 + factor * (modelled - 0.5)
            calibration = calibration.plus(modelled, random.nextDouble() < truth)
        }
        return calibration
    }

    @Test
    fun `an overconfident model is shrunk toward the coin flip`() {
        // Truth is half the lean the model claims.
        val calibration = feed(factor = 0.5, samples = 4000, seed = 11)
        assertEquals(0.5, calibration.shrinkage(), 0.08)
    }

    @Test
    fun `a well calibrated model is left alone`() {
        val calibration = feed(factor = 1.0, samples = 4000, seed = 12)
        assertTrue("shrinkage was ${calibration.shrinkage()}", calibration.shrinkage() > 0.85)
    }

    @Test
    fun `a model with no signal is shut down`() {
        // Outcomes independent of the model's lean.
        val calibration = feed(factor = 0.0, samples = 4000, seed = 13)
        assertTrue("shrinkage was ${calibration.shrinkage()}", calibration.shrinkage() < 0.12)
    }

    @Test
    fun `an inverted model is clamped rather than flipped`() {
        // Even if the model is worse than useless, never trade its opposite:
        // that is a different bet, and this estimator is not evidence for it.
        val calibration = feed(factor = -1.0, samples = 2000, seed = 14)
        assertEquals(0.0, calibration.shrinkage(), 1e-9)
    }

    @Test
    fun `an empty history starts timid`() {
        assertEquals(0.5, Calibration().shrinkage(), 1e-9)
    }

    @Test
    fun `a short lucky streak cannot unlock full confidence`() {
        var calibration = Calibration()
        // Five perfect calls at a strong lean.
        repeat(5) { calibration = calibration.plus(0.9, upWon = true) }
        assertTrue(
            "five wins should not buy full trust, got ${calibration.shrinkage()}",
            calibration.shrinkage() < 0.8,
        )
    }

    @Test
    fun `brier score reports how good the forecasts actually were`() {
        val coinFlip = feed(factor = 0.0, samples = 3000, seed = 15)
        val sharp = feed(factor = 1.0, samples = 3000, seed = 16)
        assertTrue("coin flip brier ${coinFlip.brier}", coinFlip.brier!! > sharp.brier!!)
        assertEquals(null, Calibration().brier)
    }
}
