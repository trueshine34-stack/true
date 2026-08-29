package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {

    /** Five-minute candles walking a path of closes, with small wicks. */
    private fun walk(closes: List<Double>): List<BinanceCandles.Candle> =
        closes.mapIndexed { i, c ->
            BinanceCandles.Candle(
                time = 1_787_817_600L + i * 300L,
                open = c,
                high = c + 5,
                low = c - 5,
                close = c,
                volume = 1.0,
            )
        }

    /** A saw: up to a top, back to a bottom, and up to the same top again. */
    private fun saw(top: Double, bottom: Double, legs: Int, step: Double): List<Double> {
        val out = ArrayList<Double>()
        var here = bottom
        var up = true
        repeat(legs) {
            while (if (up) here < top else here > bottom) {
                out.add(here)
                here += if (up) step else -step
            }
            out.add(if (up) top else bottom)
            up = !up
        }
        return out
    }

    @Test
    fun `finds the price the market keeps turning at`() {
        val candles = walk(saw(top = 100.0, bottom = 60.0, legs = 4, step = 8.0))
        val last = candles.last().close
        val levels = Levels.find(candles, last)

        assertTrue(levels.isNotEmpty())
        // The top was made three times; a line should sit on it.
        assertTrue(levels.any { Math.abs(it.price - 105.0) <= 6.0 })
    }

    @Test
    fun `names a level by where price is now`() {
        val candles = walk(saw(top = 100.0, bottom = 60.0, legs = 3, step = 8.0))
        val last = 80.0
        Levels.find(candles, last).forEach {
            assertEquals(if (it.price > last) "resistance" else "support", it.kind)
        }
    }

    @Test
    fun `has nothing to say about a series too short to have turned`() {
        assertTrue(Levels.find(walk(listOf(10.0, 11.0, 12.0)), 12.0).isEmpty())
        assertTrue(Levels.find(emptyList(), 100.0).isEmpty())
    }

    @Test
    fun `refuses to guess without a price`() {
        val candles = walk(saw(100.0, 60.0, 3, 8.0))
        assertTrue(Levels.find(candles, 0.0).isEmpty())
    }

    @Test
    fun `the level ahead is the nearest one the move is heading into`() {
        val levels = listOf(
            Levels.Level(120.0, 3, "resistance"),
            Levels.Level(105.0, 2, "resistance"),
            Levels.Level(80.0, 4, "support"),
        )
        assertEquals(105.0, Levels.ahead(levels, 100.0, "Up")!!, 1e-9)
        assertEquals(80.0, Levels.ahead(levels, 100.0, "Down")!!, 1e-9)
    }

    @Test
    fun `a trend with nothing in front of it has no level ahead`() {
        val levels = listOf(Levels.Level(80.0, 4, "support"))
        assertNull(Levels.ahead(levels, 100.0, "Up"))
        // And a direction that was never called is not a direction.
        assertNull(Levels.ahead(levels, 100.0, ""))
    }

    @Test
    fun `a typical range is what one candle usually travels`() {
        // Every candle in the walk is ten dollars high.
        val candles = walk(listOf(50.0, 52.0, 54.0, 53.0))
        assertEquals(10.0, Levels.typicalRange(candles), 1e-9)
        assertEquals(0.0, Levels.typicalRange(emptyList()), 1e-9)
    }
}
