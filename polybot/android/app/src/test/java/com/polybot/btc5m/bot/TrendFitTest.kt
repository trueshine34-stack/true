package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The direction the probe trades in, and when it refuses to name one. */
class TrendFitTest {

    private fun walk(closes: List<Double>): List<BinanceCandles.Candle> =
        closes.mapIndexed { i, c ->
            BinanceCandles.Candle(1_787_817_600 + i * 60L, c, c + 1, c - 1, c, 1.0)
        }

    @Test
    fun aSteadyClimbIsUp() {
        val t = TrendFit.of(walk((0 until 30).map { 100.0 + it }), 30)!!
        assertEquals("Up", t.way)
        // A dollar a minute is sixty an hour.
        assertEquals(60.0, t.perHour, 1e-6)
        assertEquals(1.0, t.fit, 1e-6)
    }

    @Test
    fun aSteadyFallIsDown() {
        val t = TrendFit.of(walk((0 until 30).map { 100.0 - it }), 30)!!
        assertEquals("Down", t.way)
        assertEquals(-60.0, t.perHour, 1e-6)
    }

    @Test
    fun chopIsNotADirection() {
        val t = TrendFit.of(walk((0 until 30).map { 100.0 + if (it % 2 == 0) -3 else 3 }), 30)!!
        assertEquals("", t.way)
        assertTrue(t.fit < TrendFit.MIN_FIT)
    }

    @Test
    fun aDriftTooSmallForItsRangeIsNotADirection() {
        val closes = (0 until 30).map {
            100.0 + it * 0.02 + when (it % 3) { 0 -> 10.0; 1 -> -10.0; else -> 0.0 }
        }
        assertEquals("", TrendFit.of(walk(closes), 30)!!.way)
    }

    @Test
    fun onlyTheSpanAskedForCounts() {
        val closes = (0 until 60).map { 200.0 - it } + (0 until 10).map { 140.0 + it * 2 }
        assertEquals("Up", TrendFit.of(walk(closes), 10)!!.way)
        assertEquals("Down", TrendFit.of(walk(closes), 70)!!.way)
    }

    @Test
    fun tooLittleHistoryHasNoAnswer() {
        assertNull(TrendFit.of(walk(listOf(100.0, 101.0)), 30))
        assertNull(TrendFit.of(emptyList(), 30))
    }
}
