package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A move that has run out of breath, and the side that is bought against it.
 */
class FadePlanTest {

    private fun bar(i: Int, open: Double, high: Double, low: Double, close: Double) =
        BinanceCandles.Candle(
            time = 1_787_817_600L + i * 300L,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 1.0,
        )

    /** Twenty flat candles between 100 and 110, for something to break out of. */
    private fun shelf(): MutableList<BinanceCandles.Candle> =
        (0 until 20).map { bar(it, 105.0, 110.0, 100.0, 105.0) }.toMutableList()

    @Test
    fun `a new high closing at its top is sold into`() {
        val candles = shelf()
        // Reaches 120 — above everything before it — and finishes at 118,
        // which is the top eighth of its own range.
        candles.add(bar(20, 106.0, 120.0, 104.0, 118.0))
        assertEquals("Down", FadePlan.side(candles))
    }

    @Test
    fun `a new low closing at its bottom is bought`() {
        val candles = shelf()
        candles.add(bar(20, 104.0, 106.0, 90.0, 92.0))
        assertEquals("Up", FadePlan.side(candles))
    }

    /**
     * The close is the half that matters. A candle that made the high and
     * then gave it all back has already been faded — by itself, inside its
     * own five minutes — and there is nothing left to fade.
     */
    @Test
    fun `a new high that closed back down is left alone`() {
        val candles = shelf()
        candles.add(bar(20, 106.0, 120.0, 104.0, 105.0))
        assertEquals("", FadePlan.side(candles))
        assertTrue(FadePlan.why(candles).startsWith("экстремум есть"))
    }

    @Test
    fun `closing at its top without a new high says nothing`() {
        val candles = shelf()
        // Finishes at the top of its own range, but 109 is inside the shelf.
        candles.add(bar(20, 104.0, 109.0, 103.0, 108.5))
        assertEquals("", FadePlan.side(candles))
        assertEquals("нет экстремума за 20", FadePlan.why(candles))
    }

    /**
     * An outside bar that takes out twenty candles both ways has not run out
     * of breath in either direction; it has simply been enormous.
     */
    @Test
    fun `a candle that broke both ways is not a fade`() {
        val candles = shelf()
        candles.add(bar(20, 105.0, 130.0, 80.0, 128.0))
        assertEquals("", FadePlan.side(candles))
        assertTrue(FadePlan.why(candles).contains("в обе стороны"))
    }

    @Test
    fun `the candle is not counted among the twenty it must beat`() {
        // Exactly twenty-one candles: twenty of shelf and the breakout. One
        // fewer and there is nothing to measure against.
        val candles = shelf()
        candles.add(bar(20, 106.0, 120.0, 104.0, 118.0))
        assertEquals("Down", FadePlan.side(candles))
        assertEquals("", FadePlan.side(candles.takeLast(20)))
        assertEquals("мало свечей", FadePlan.why(candles.takeLast(20)))
    }

    @Test
    fun `a candle with no range at all is not read`() {
        val candles = shelf()
        candles.add(bar(20, 105.0, 105.0, 105.0, 105.0))
        assertEquals("", FadePlan.side(candles))
        assertEquals("свеча без размаха", FadePlan.why(candles))
    }

    @Test
    fun `the quarter is where the line is drawn`() {
        assertEquals(0.25, FadePlan.QUARTER, 1e-9)
        assertEquals(20, FadePlan.REACH)
        val candles = shelf()
        // Range 100 to 120; the top quarter starts at 115.
        candles.add(bar(20, 106.0, 120.0, 100.0, 115.0))
        assertEquals("Down", FadePlan.side(candles))
        candles[20] = bar(20, 106.0, 120.0, 100.0, 114.0)
        assertEquals("", FadePlan.side(candles))
    }
}
