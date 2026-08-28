package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taking the profit the book is showing rather than the one being asked for. */
class TakePlanTest {

    private val on = TakePlan.Settings(enabled = true)

    @Test
    fun takesAGainThatIsThereAfterTheFee() {
        // Forty cents paid. The fee on a sale is taken in money, so the bid has
        // to pay more than 46c for fifteen percent to survive it.
        assertFalse(TakePlan.ready(0.40, 0.46, on))
        assertTrue(TakePlan.ready(0.40, 0.49, on))
    }

    @Test
    fun leavesAnythingUnderTheThresholdAlone() {
        assertFalse(TakePlan.ready(0.40, 0.42, on))
        assertFalse(TakePlan.ready(0.40, 0.40, on))
        // And a loss is very much not a gain.
        assertFalse(TakePlan.ready(0.40, 0.30, on))
    }

    @Test
    fun doesNothingWhileSwitchedOff() {
        assertFalse(TakePlan.ready(0.40, 0.90, on.copy(enabled = false)))
    }

    @Test
    fun holdsWhenThereIsNoBidOrNoCost() {
        assertFalse(TakePlan.ready(0.40, null, on))
        assertFalse(TakePlan.ready(0.40, 0.0, on))
        assertFalse(TakePlan.ready(0.0, 0.90, on))
    }

    @Test
    fun theGainItReportsIsTheOneAfterTheFee() {
        // Half a dollar paid, ninety bid: the fee at ninety cents is small, so
        // this is a little under eighty percent rather than exactly it.
        val gain = TakePlan.gainAt(0.50, 0.90)
        assertTrue(gain > 0.75 && gain < 0.80)
        assertEquals(0.0, TakePlan.gainAt(0.0, 0.90), 1e-9)
    }

    @Test
    fun sellsAtickUnderTheBidToBeTakenNow() {
        assertEquals(0.44, TakePlan.takePrice(0.45, 0.01), 1e-9)
        // And never under the venue's own floor.
        assertEquals(0.01, TakePlan.takePrice(0.01, 0.01), 1e-9)
    }

    @Test
    fun aTighterThresholdTakesSooner() {
        val eager = TakePlan.Settings(enabled = true, gain = 0.05)
        assertTrue(TakePlan.ready(0.40, 0.45, eager))
        assertFalse(TakePlan.ready(0.40, 0.45, on))
    }
}
