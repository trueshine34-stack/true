package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fair price of a side of a running window, and whether the book is
 * asking less than that.
 *
 * The numbers this checks against are not invented: the curve was fitted by
 * maximum likelihood over 31 756 five-minute windows of real tape across 28
 * days, and the accuracies quoted below are what those windows actually did.
 */
class FairValueTest {

    /** The typical five-minute travel in the tape these were measured on. */
    private val typical = 100.0

    @Test
    fun `an untouched window is a coin`() {
        assertEquals(0.5, FairValue.chanceUp(0.0, typical, 300), 1e-9)
        assertEquals(0.5, FairValue.chanceUp(0.0, typical, 60), 1e-9)
    }

    @Test
    fun `a window already over is decided`() {
        assertEquals(1.0, FairValue.chanceUp(40.0, typical, 0), 1e-9)
        assertEquals(0.0, FairValue.chanceUp(-40.0, typical, 0), 1e-9)
        assertEquals(0.5, FairValue.chanceUp(0.0, typical, 0), 1e-9)
    }

    @Test
    fun `the two sides always add up to one`() {
        for (moved in listOf(-120.0, -30.0, 0.0, 15.0, 90.0)) {
            for (left in listOf(240L, 180L, 60L, 20L)) {
                val up = FairValue.chance("Up", moved, typical, left)
                val down = FairValue.chance("Down", moved, typical, left)
                assertEquals(1.0, up + down, 1e-12)
            }
        }
    }

    @Test
    fun `the same lead is worth more the less time is left to undo it`() {
        // Half a typical move ahead, with four minutes left and with one.
        val early = FairValue.chanceUp(50.0, typical, 240)
        val late = FairValue.chanceUp(50.0, typical, 60)
        assertTrue(late > early)
        // Which is the whole point of waiting: at three minutes in, a move of
        // this size won 81.6% of 2559 windows, and a coin never gets there.
        assertTrue(late > 0.75)
    }

    @Test
    fun `a bigger lead is worth more than a smaller one`() {
        val small = FairValue.chanceUp(10.0, typical, 120)
        val big = FairValue.chanceUp(80.0, typical, 120)
        assertTrue(big > small)
        assertTrue(small > 0.5)
    }

    @Test
    fun `down is the mirror of up`() {
        assertEquals(
            FairValue.chanceUp(60.0, typical, 90),
            FairValue.chance("Down", -60.0, typical, 90),
            1e-12,
        )
    }

    @Test
    fun `a still tape says nothing`() {
        assertEquals(0.5, FairValue.chanceUp(50.0, 0.0, 120), 1e-9)
    }

    @Test
    fun `the edge is what is left after the fee`() {
        // 60¢ fair, 50¢ asked, and the taker fee on a 50¢ share is 1.75¢.
        val edge = ProbePlan.edgeOn(0.60, 0.50)
        assertEquals(0.60 - ProbePlan.takenPrice(0.50), edge, 1e-12)
        assertTrue(edge > 0.08 && edge < 0.09)
    }

    @Test
    fun `a side priced at its worth has no edge`() {
        // The ask alone equals fair, so the fee alone makes it a losing buy.
        assertTrue(ProbePlan.edgeOn(0.70, 0.70) < 0.0)
    }

    @Test
    fun `nothing is bought without an ask`() {
        assertEquals(0.0, ProbePlan.edgeOn(0.80, null), 1e-12)
        assertFalse(ProbePlan.worthTaking(0.80, null, 120, 180))
    }

    @Test
    fun `takes only a real discount`() {
        // Ten cents under fair, halfway through: taken.
        assertTrue(ProbePlan.worthTaking(0.80, 0.68, 150, 150))
        // Two cents under, which the fee eats: refused.
        assertFalse(ProbePlan.worthTaking(0.80, 0.79, 150, 150))
    }

    @Test
    fun `waits out the first half minute`() {
        // The same discount, before the window has shown anything.
        assertFalse(ProbePlan.worthTaking(0.80, 0.68, 10, 290))
        assertTrue(ProbePlan.worthTaking(0.80, 0.68, 30, 270))
    }

    @Test
    fun `never pays near a dollar`() {
        // A 97¢ side may well be worth 99¢, and is still not worth buying:
        // the whole gain is rounding and the whole stake is at risk.
        assertFalse(ProbePlan.worthTaking(0.99, 0.97, 240, 60))
    }

    @Test
    fun `a window already over is not entered`() {
        assertFalse(ProbePlan.worthTaking(0.90, 0.50, 300, 0))
    }
}
