package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaping Polymarket's price series into candles.
 *
 * The endpoint answers with a point every few seconds; a chart wants whole
 * minutes. Getting the grouping wrong is the kind of thing that looks fine and
 * silently draws the wrong body on every bar.
 */
class PolyPriceTest {

    private fun points(vararg pairs: Pair<Long, Double>) =
        pairs.map { PolyPriceApi.Point(it.first * 1000, it.second) }

    @Test
    fun aMinuteBecomesOneCandle() {
        val candles = PolyPriceApi.toCandles(
            points(60L to 100.0, 65L to 103.0, 70L to 98.0, 115L to 101.0),
        )
        assertEquals(1, candles.size)
        val c = candles.single()
        assertEquals(60L, c.time)
        assertEquals(100.0, c.open, 1e-9)
        assertEquals(103.0, c.high, 1e-9)
        assertEquals(98.0, c.low, 1e-9)
        assertEquals(101.0, c.close, 1e-9)
    }

    @Test
    fun candlesAreSplitOnTheMinuteBoundary() {
        val candles = PolyPriceApi.toCandles(
            points(115L to 100.0, 120L to 110.0, 175L to 105.0),
        )
        assertEquals(2, candles.size)
        assertEquals(60L, candles[0].time)
        assertEquals(120L, candles[1].time)
        assertEquals(110.0, candles[1].open, 1e-9)
        assertEquals(105.0, candles[1].close, 1e-9)
    }

    @Test
    fun outOfOrderPointsStillOpenAndCloseCorrectly() {
        // Windows are stitched together, so arrival order is not guaranteed.
        val candles = PolyPriceApi.toCandles(
            points(90L to 105.0, 60L to 100.0, 119L to 108.0),
        )
        assertEquals(100.0, candles.single().open, 1e-9)
        assertEquals(108.0, candles.single().close, 1e-9)
    }

    @Test
    fun pointsBeforeTheWindowAreDropped() {
        val candles = PolyPriceApi.toCandles(
            points(0L to 90.0, 60L to 100.0, 120L to 110.0),
            fromSec = 60L,
        )
        assertEquals(2, candles.size)
        assertTrue(candles.none { it.time < 60L })
    }

    @Test
    fun candlesComeOutInTimeOrder() {
        val candles = PolyPriceApi.toCandles(
            points(180L to 103.0, 60L to 100.0, 120L to 101.0),
        )
        assertEquals(listOf(60L, 120L, 180L), candles.map { it.time })
    }

    @Test
    fun anEmptySeriesIsNotACandle() {
        assertTrue(PolyPriceApi.toCandles(emptyList()).isEmpty())
    }

    @Test
    fun aSinglePointIsAFlatCandle() {
        val c = PolyPriceApi.toCandles(points(60L to 100.0)).single()
        assertEquals(100.0, c.open, 1e-9)
        assertEquals(100.0, c.high, 1e-9)
        assertEquals(100.0, c.low, 1e-9)
        assertEquals(100.0, c.close, 1e-9)
    }
}
