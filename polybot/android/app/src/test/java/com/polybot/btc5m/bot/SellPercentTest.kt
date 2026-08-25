package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selling by margin.
 *
 * The mistake this guards against is the obvious one: resting at `avg x 1.2`
 * and calling it twenty percent. The fee comes out of the proceeds, so that
 * price pays less than twenty — and at cheap prices, appreciably less.
 */
class SellPercentTest {

    private val tick = 0.01

    @Test
    fun theAskedForMarginIsWhatTheSaleActuallyPays() {
        for (avg in listOf(0.08, 0.2, 0.4, 0.55, 0.7)) {
            val price = SellPercent.targetPrice(avg, 0.20, tick)
            assertTrue(
                "net of $price must clear ${avg * 1.2}",
                SellPercent.netSell(price) >= avg * 1.2 - 1e-9,
            )
            // And one tick lower would not have: no more is asked than needed.
            assertTrue(SellPercent.netSell(price - tick) < avg * 1.2)
        }
    }

    @Test
    fun itAsksMoreThanTheNaiveMultiple() {
        assertTrue(SellPercent.targetPrice(0.4, 0.2, tick) > 0.4 * 1.2)
    }

    @Test
    fun theTargetSitsOnTheVenueGrid() {
        val price = SellPercent.targetPrice(0.37, 0.25, tick)
        assertEquals(Math.round(price * 100).toDouble() / 100.0, price, 1e-9)
        assertTrue(price <= 0.99)
    }

    @Test
    fun breakEvenIsAProfitAndNotARoundingOne() {
        for (avg in listOf(0.05, 0.3, 0.62, 0.9)) {
            val floor = SellPercent.breakEven(avg, tick)
            assertTrue(SellPercent.netSell(floor) > avg)
        }
    }

    @Test
    fun eachSliceGoesOutAboveTheLast() {
        val avg = 0.40
        val first = SellPercent.priceFor(avg, 0.2, tick, null, 200, 60, null)
        val second = SellPercent.priceFor(avg, 0.2, tick, first, 200, 60, null)
        val third = SellPercent.priceFor(avg, 0.2, tick, second, 200, 60, null)

        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun inTheLastMinuteAnyProfitWillDo() {
        val avg = 0.40
        // A bid well under the margin, but still a win after the fee.
        val bid = 0.45
        assertTrue(SellPercent.netSell(bid) > avg)

        val holding = SellPercent.priceFor(avg, 0.2, tick, null, 200, 60, bid)
        val panicking = SellPercent.priceFor(avg, 0.2, tick, null, 30, 60, bid)

        assertTrue("still holding out for the margin", holding > bid)
        assertEquals("takes the bid", bid, panicking, 1e-9)
    }

    @Test
    fun aLosingBidIsNotTakenEvenAtTheClose() {
        val avg = 0.40
        val bid = 0.38
        val price = SellPercent.priceFor(avg, 0.2, tick, null, 20, 60, bid)

        assertTrue("never sells at a loss", price > bid)
        assertTrue(SellPercent.netSell(price) > avg)
    }

    @Test
    fun aBidThatOnlyCoversTheFeeIsNotAProfit() {
        val avg = 0.50
        // Marked up, but the fee eats the difference.
        val bid = 0.51
        assertTrue(SellPercent.netSell(bid) < avg)
        assertTrue(SellPercent.priceFor(avg, 0.2, tick, null, 10, 60, bid) > bid)
    }

    @Test
    fun holdingOutEndsOnlyInsideTheLastMinute() {
        assertTrue(SellPercent.holdingOut(200, 60))
        assertFalse(SellPercent.holdingOut(45, 60))
        // Before the window opens there is nothing to panic about.
        assertTrue(SellPercent.holdingOut(-30, 60))
    }

    @Test
    fun aPositionWithNoKnownCostAsksForACent() {
        // This is why the rule must not call it at all until the average is
        // known: data-api reports a fresh position with its size already right
        // and its cost basis still at zero, and a margin over zero is the tick
        // floor — which sells the position for nothing.
        assertEquals(tick, SellPercent.targetPrice(0.0, 0.2, tick), 1e-9)
        assertEquals(tick, SellPercent.breakEven(0.0, tick), 1e-9)
    }

    @Test
    fun slicesFollowTheClipsThePositionWasBuiltFrom() {
        assertEquals(5.0, SellPercent.sliceSize(15.0, 5.0, 5.0), 1e-9)
        // Nothing is left behind that the venue would refuse to sell.
        assertEquals(8.0, SellPercent.sliceSize(8.0, 5.0, 5.0), 1e-9)
        assertEquals(12.0, SellPercent.sliceSize(12.0, null, 5.0), 1e-9)
    }
}
