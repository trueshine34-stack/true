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

    @Test
    fun `leans the way the line points even when it will not call it`() {
        // Chop with a slight upward tilt: the strict answer is "no trend", and
        // the lean is still up, which is what a rule that acts every window
        // needs. Without this the probe stood aside for four windows in ten.
        val chop = TrendFit.Trend(perHour = 12.0, way = "", fit = 0.05)
        assertEquals("Up", TrendFit.lean(chop))
        assertEquals("Down", TrendFit.lean(chop.copy(perHour = -12.0)))
    }

    @Test
    fun `keeps a called direction as it is`() {
        val up = TrendFit.Trend(perHour = -3.0, way = "Up", fit = 0.9)
        assertEquals("Up", TrendFit.lean(up))
    }

    @Test
    fun `has no lean without a line, or on a flat one`() {
        assertEquals("", TrendFit.lean(null))
        assertEquals("", TrendFit.lean(TrendFit.Trend(perHour = 0.0, way = "", fit = 0.0)))
    }
    /** The same, on five-minute candles, which is what the wide line reads. */
    private fun walk5(closes: List<Double>): List<BinanceCandles.Candle> =
        closes.mapIndexed { i, c ->
            BinanceCandles.Candle(1_787_817_600 + i * 300L, c, c + 1, c - 1, c, 1.0)
        }

    /**
     * The wide line is fitted over an hour, which on the five-minute chart is
     * twelve candles. It was three hours, and three hours fits the session:
     * by the time a line that long has turned, the move it described is over.
     */
    @Test
    fun `the wide line looks back half an hour`() {
        assertEquals(30, TrendFit.WIDE_MINUTES)

        // Three hours of five-minute candles: flat for most of it, climbing
        // through the last half hour. The short fit sees only the climb.
        val flat = (0 until 30).map { 100.0 }
        val up = (1..6).map { 100.0 + it * 10.0 }
        val near = TrendFit.of(walk5(flat + up), TrendFit.WIDE_MINUTES)!!
        assertEquals("Up", near.way)

        // Over three hours the same climb is a corner of the picture, and the
        // slope fitted to it is gentler than the one the half hour sees.
        val session = TrendFit.of(walk5(flat + up), 180)!!
        assertTrue(session.perHour < near.perHour)
    }

    /**
     * The near line is fitted over a quarter of an hour. Half an hour is
     * three windows of history deciding one window's bet, and by the time a
     * turn shows up in a thirty-minute fit five minutes of it have happened.
     */
    @Test
    fun `the near line looks back a quarter of an hour`() {
        assertEquals(15, TrendFit.NEAR_MINUTES)

        // Half an hour of minutes: climbing for the first fifteen, falling
        // through the last fifteen. The short fit sees the fall.
        val up = (0 until 15).map { 100.0 + it }
        val down = (1..15).map { 114.0 - it }
        val near = TrendFit.of(walk(up + down), TrendFit.NEAR_MINUTES)!!
        assertEquals("Down", near.way)

        // Over the whole half hour the two legs cancel and there is no
        // direction to name at all — which is what the rule used to trade.
        val long = TrendFit.of(walk(up + down), 30)!!
        assertEquals("", long.way)
    }

}
