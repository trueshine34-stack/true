package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catching a side down and letting it back up. */
class CatchPlanTest {

    private val on = CatchPlan.Settings(enabled = true)

    @Test
    fun waitsSixCentsUnderWhereItWasArmed() {
        // Armed at fifty: nothing happens until the market is at forty-four.
        assertEquals(0.44, CatchPlan.buyTarget(0.50, null, on), 1e-9)
        assertFalse(CatchPlan.ready(0.47, CatchPlan.buyTarget(0.50, null, on)))
        assertTrue(CatchPlan.ready(0.44, CatchPlan.buyTarget(0.50, null, on)))
        // And below the target is better than at it.
        assertTrue(CatchPlan.ready(0.41, CatchPlan.buyTarget(0.50, null, on)))
    }

    @Test
    fun everyFurtherClipIsThreeCentsCheaperThanTheLastFill() {
        // Not three cents under the reference: under what was actually paid,
        // which is what makes a run down cost less each time.
        assertEquals(0.41, CatchPlan.buyTarget(0.50, 0.44, on), 1e-9)
        assertEquals(0.38, CatchPlan.buyTarget(0.50, 0.41, on), 1e-9)
        // A clip filled below its target moves the next one down with it.
        assertEquals(0.36, CatchPlan.buyTarget(0.50, 0.39, on), 1e-9)
    }

    @Test
    fun willNotChaseAPriceUpNearPar() {
        // The ladder can only ever walk down, but an arm at a dear price must
        // not put the first clip in at ninety-five.
        assertFalse(CatchPlan.ready(0.95, 0.96))
        assertTrue(CatchPlan.ready(0.89, 0.9))
    }

    @Test
    fun theFirstExitIsTenPercentOnWhatTheFirstClipCost() {
        // Forty-four cents plus a tenth is 48.4, and a sell never rounds down.
        assertEquals(0.49, CatchPlan.sellPrice(0.44, 0, on, 0.01), 1e-9)
    }

    @Test
    fun eachFurtherLotStandsTwoCentsAboveTheLast() {
        val first = CatchPlan.sellPrice(0.44, 0, on, 0.01)
        val second = CatchPlan.sellPrice(0.44, 1, on, 0.01)
        val third = CatchPlan.sellPrice(0.44, 2, on, 0.01)
        assertEquals(0.02, second - first, 1e-9)
        assertEquals(0.02, third - second, 1e-9)
    }

    @Test
    fun theLastHalfMinuteParksThemNearPar() {
        assertFalse(CatchPlan.late(31))
        assertTrue(CatchPlan.late(30))
        assertTrue(CatchPlan.late(0))
        assertEquals(0.96, CatchPlan.latePrice(0, 0.01), 1e-9)
        assertEquals(0.97, CatchPlan.latePrice(1, 0.01), 1e-9)
        assertEquals(0.98, CatchPlan.latePrice(2, 0.01), 1e-9)
        // A fourth lot has nowhere higher to go; it joins the top one.
        assertEquals(0.98, CatchPlan.latePrice(3, 0.01), 1e-9)
    }

    @Test
    fun aClipIsAQuarterOfWhatIsFree() {
        // Twenty dollars free at forty cents: five dollars, twelve and a half
        // shares.
        assertEquals(12.5, CatchPlan.clipShares(20.0, 0.40, on, 5.0), 1e-9)
    }

    @Test
    fun andNeverFewerThanFiveShares() {
        // A quarter of four dollars at forty cents is two and a half shares,
        // which the venue would refuse anyway.
        assertEquals(5.0, CatchPlan.clipShares(4.0, 0.40, on, 5.0), 1e-9)
        // At a cent, five shares is under a dollar, so the venue's own floor
        // is the one that binds.
        assertTrue(CatchPlan.clipShares(4.0, 0.01, on, 5.0) > 5.0)
    }

    @Test
    fun willNotBuyWhatItCannotPayFor() {
        assertTrue(CatchPlan.affordable(10.0, 0.40, 12.5))
        assertFalse(CatchPlan.affordable(4.0, 0.40, 12.5))
    }

    @Test
    fun crossesTheSpreadToBeFilledNow() {
        assertEquals(0.45, CatchPlan.crossPrice(0.44, 0.01), 1e-9)
        assertEquals(0.99, CatchPlan.crossPrice(0.99, 0.01), 1e-9)
    }
}
