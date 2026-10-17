package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun eachSliceGoesOutAboveTheLast() {
        val avg = 0.40
        val first = SellPercent.priceFor(avg, 0.2, tick, null, 200, 60, null)
        val second = SellPercent.priceFor(avg, 0.2, tick, first, 200, 60, null)
        val third = SellPercent.priceFor(avg, 0.2, tick, second, 200, 60, null)

        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun theStretchBeforeTheLastMinuteAsksSeventySeven() {
        val avg = 0.40
        // Margin alone would ask 50¢ here.
        val plain = SellPercent.priceFor(avg, 0.2, tick, null, 200, 60, null)
        assertTrue(plain < 0.77)

        // Inside the fifty seconds before the last minute, the floor lifts it.
        assertEquals(0.77, SellPercent.priceFor(avg, 0.2, tick, null, 100, 60, null), 1e-9)
        // The band starts 110 seconds out and ends where the last minute does.
        assertEquals(0.77, SellPercent.priceFor(avg, 0.2, tick, null, 110, 60, null), 1e-9)
        assertEquals(plain, SellPercent.priceFor(avg, 0.2, tick, null, 111, 60, null), 1e-9)
        assertEquals(0.77, SellPercent.priceFor(avg, 0.2, tick, null, 61, 60, null), 1e-9)
    }

    @Test
    fun aFloorOnlyEverRaisesTheAsk() {
        // A dear lot already asks more than seventy-seven; the floor is a
        // minimum, not a target, and leaves it alone.
        val dear = SellPercent.targetPrice(0.80, 0.2, tick)
        assertTrue(dear > 0.77)
        assertEquals(dear, SellPercent.priceFor(0.80, 0.2, tick, null, 100, 60, null), 1e-9)
    }

    @Test
    fun theBandNeverCrossesTheBookTheWayTheCloseDoes() {
        // 93¢ bid: taken at the close, ignored in the band before it — there is
        // still time, and the band only sets a floor on what is asked.
        assertEquals(0.77, SellPercent.priceFor(0.40, 0.2, tick, null, 100, 60, 0.93), 1e-9)
        assertEquals(0.93, SellPercent.priceFor(0.40, 0.2, tick, null, 30, 60, 0.93), 1e-9)
    }

    @Test
    fun theFloorSaysWhichBandTheClockIsIn() {
        assertNull(SellPercent.floorFor(200, 60))
        assertEquals(0.77, SellPercent.floorFor(100, 60)!!, 1e-9)
        assertEquals(0.90, SellPercent.floorFor(30, 60)!!, 1e-9)
        // Before the window opens there is no floor to apply.
        assertNull(SellPercent.floorFor(-30, 60))
    }

    @Test
    fun theLastMinuteAsksNinetyAndTakesItOnTouch() {
        val avg = 0.40

        // While there is time, the price is the lot's own margin — whatever the
        // book happens to be bidding.
        val holding = SellPercent.priceFor(avg, 0.2, tick, null, 200, 60, 0.62)
        assertEquals(SellPercent.targetPrice(avg, 0.2, tick), holding, 1e-9)
        assertTrue(holding < 0.90)

        // In the last minute the offer sits at ninety, whatever the lot cost…
        assertEquals(0.90, SellPercent.priceFor(avg, 0.2, tick, null, 30, 60, 0.62), 1e-9)
        // …and is taken the moment the book reaches it.
        assertEquals(0.93, SellPercent.priceFor(avg, 0.2, tick, null, 30, 60, 0.93), 1e-9)
    }

    @Test
    fun aSmallProfitIsNoLongerEnoughAtTheClose() {
        // 45c on a 40c lot clears the fee, and used to be taken. It is not a
        // reason to sell a position the market is about to price at a dollar.
        val price = SellPercent.priceFor(0.40, 0.2, tick, null, 20, 60, 0.45)

        assertEquals(0.90, price, 1e-9)
    }

    @Test
    fun theFloorIsSettable() {
        assertEquals(
            0.95,
            SellPercent.priceFor(0.40, 0.2, tick, null, 20, 60, 0.45, closeFloor = 0.95),
            1e-9,
        )
    }

    @Test
    fun aLosingBidIsNotTakenEvenAtTheClose() {
        val price = SellPercent.priceFor(0.40, 0.2, tick, null, 20, 60, 0.38)

        assertTrue("never sells into a bid this far down", price > 0.38)
        assertEquals(0.90, price, 1e-9)
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
    }

    @Test
    fun slicesFollowTheClipsThePositionWasBuiltFrom() {
        assertEquals(5.0, SellPercent.sliceSize(15.0, 5.0, 5.0), 1e-9)
        // Nothing is left behind that the venue would refuse to sell.
        assertEquals(8.0, SellPercent.sliceSize(8.0, 5.0, 5.0), 1e-9)
        assertEquals(12.0, SellPercent.sliceSize(12.0, null, 5.0), 1e-9)
    }
    // ------------------------------------------- one purchase at a time

    private fun lot(shares: Double, price: Double, at: Long = 0L) =
        OrderLog.Lot(shares, price, at)

    @Test
    fun theNextSliceIsTheOldestPurchaseAtItsOwnPrice() {
        val slice = SellPercent.nextSlice(
            listOf(lot(15.0, 0.56), lot(13.0, 0.66)),
            minimumOrderSize = 5.0,
            free = 28.0,
        )
        // Not 28 shares at the average of the two: fifteen, priced off the 56c
        // they were actually bought at. The thirteen come up on the next pass.
        assertEquals(15.0, slice!!.first, 1e-9)
        assertEquals(0.56, slice.second, 1e-9)
    }

    @Test
    fun aPurchaseTooSmallToSellAloneTakesTheNextOneWithIt() {
        val slice = SellPercent.nextSlice(
            listOf(lot(2.0, 0.30), lot(4.0, 0.40), lot(9.0, 0.35)),
            minimumOrderSize = 5.0,
            free = 15.0,
        )
        // Two is below the venue's floor, so the four join it — and the price
        // is the dearer of them, because the sale has to be a profit on both.
        assertEquals(6.0, slice!!.first, 1e-9)
        assertEquals(0.40, slice.second, 1e-9)
    }

    @Test
    fun itNeverOffersMoreThanIsFree() {
        val slice = SellPercent.nextSlice(
            listOf(lot(20.0, 0.44)),
            minimumOrderSize = 5.0,
            free = 8.0,
        )
        // Twelve of them are already resting under an offer; only eight are
        // left to arrange.
        assertEquals(8.0, slice!!.first, 1e-9)
        assertEquals(0.44, slice.second, 1e-9)
    }

    @Test
    fun nothingLeftToArrangeIsNoSlice() {
        assertNull(SellPercent.nextSlice(emptyList(), 5.0, 10.0))
        // Everything is spoken for: free is under the venue's floor.
        assertNull(SellPercent.nextSlice(listOf(lot(9.0, 0.44)), 5.0, 2.0))
        // And what is left of the lots cannot be made into an order.
        assertNull(SellPercent.nextSlice(listOf(lot(2.0, 0.44)), 5.0, 9.0))
    }

    @Test
    fun eachSliceAsksForItsOwnMargin() {
        val cheap = SellPercent.targetPrice(0.56, 0.10, 0.01)
        val dear = SellPercent.targetPrice(0.66, 0.10, 0.01)
        // The whole point: two purchases, two prices, the dearer asking more.
        assertTrue(dear > cheap)
        assertTrue(SellPercent.netSell(cheap) >= 0.56 * 1.10 - 0.01)
        assertTrue(SellPercent.netSell(dear) >= 0.66 * 1.10 - 0.01)
    }

}
