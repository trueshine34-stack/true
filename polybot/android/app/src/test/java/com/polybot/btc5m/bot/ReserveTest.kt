package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reserve, which is subtracted once — where the balance is read — so that
 * every order in the app is sized from what is left of it.
 */
class ReserveTest {

    @Test
    fun `what is locked away is not part of the balance`() {
        assertEquals(60.0, Reserve.free(100.0, 40.0), 1e-9)
    }

    @Test
    fun `no reserve is the wallet itself`() {
        assertEquals(100.0, Reserve.free(100.0, 0.0), 1e-9)
        assertEquals(100.0, Reserve.free(100.0, -5.0), 1e-9)
    }

    /**
     * Locking more than there is means nothing may be bought — which is a
     * balance of zero, and not a negative one that would size an order the
     * wrong way round.
     */
    @Test
    fun `a reserve larger than the wallet leaves nothing`() {
        assertEquals(0.0, Reserve.free(30.0, 40.0), 1e-9)
        assertEquals(0.0, Reserve.free(40.0, 40.0), 1e-9)
    }

    @Test
    fun `an empty wallet stays empty`() {
        assertEquals(0.0, Reserve.free(0.0, 10.0), 1e-9)
        assertEquals(0.0, Reserve.free(-3.0, 0.0), 1e-9)
    }

    /**
     * The share, which is the other way of saying the same reserve — and the
     * one that keeps its meaning while the account moves.
     */
    @Test
    fun aShareOfTheWalletIsLockedWhenOneIsSet() {
        assertEquals(25.0, Reserve.lockedOf(100.0, 0.0, 0.25), 1e-9)
        // And it grows with the account by itself.
        assertEquals(50.0, Reserve.lockedOf(200.0, 0.0, 0.25), 1e-9)
    }

    @Test
    fun aShareWinsOverASumWhenBothArrive() {
        assertEquals(25.0, Reserve.lockedOf(100.0, 40.0, 0.25), 1e-9)
    }

    @Test
    fun noShareLeavesTheSumInCharge() {
        assertEquals(40.0, Reserve.lockedOf(100.0, 40.0, 0.0), 1e-9)
        assertEquals(0.0, Reserve.lockedOf(100.0, 0.0, 0.0), 1e-9)
    }

    /** All of it is a coherent setting: trade nothing. Above all of it is too. */
    @Test
    fun aShareIsCappedAtTheWholeWallet() {
        assertEquals(100.0, Reserve.lockedOf(100.0, 0.0, 1.0), 1e-9)
        assertEquals(100.0, Reserve.lockedOf(100.0, 0.0, 4.0), 1e-9)
        assertEquals(0.0, Reserve.free(100.0, Reserve.lockedOf(100.0, 0.0, 1.0)), 1e-9)
    }

    @Test
    fun anEmptyWalletLocksNothing() {
        assertEquals(0.0, Reserve.lockedOf(0.0, 40.0, 0.25), 1e-9)
    }

    /** A setting that arrived as nonsense must not stop the desk trading. */
    @Test
    fun `a broken reserve is no reserve`() {
        assertEquals(100.0, Reserve.free(100.0, Double.NaN), 1e-9)
        assertEquals(0.0, Reserve.free(Double.NaN, 10.0), 1e-9)
        assertEquals(40.0, Reserve.lockedOf(100.0, 40.0, Double.NaN), 1e-9)
        assertEquals(0.0, Reserve.lockedOf(Double.NaN, 40.0, 0.25), 1e-9)
    }
}

/**
 * A share of the run is a share of the run, not of the run plus a window that
 * has already been paid out.
 */
class ReserveHeldWindowTest {

    private val window = 300L

    @Test
    fun `the window running now always counts`() {
        // A minute in: the last window is long settled and paid.
        assertEquals(3_000L, Reserve.heldSince(3_060L, window))
    }

    @Test
    fun `and the one before it while the payout is still on its way`() {
        // Five seconds past the boundary: the shares have settled and the
        // money has not landed, so they still count as held.
        assertEquals(2_700L, Reserve.heldSince(3_005L, window))
        assertEquals(2_700L, Reserve.heldSince(3_030L, window))
        // A second past the grace, it is cash and counted as cash.
        assertEquals(3_000L, Reserve.heldSince(3_031L, window))
    }

    @Test
    fun `a settled window counted twice takes the account with it`() {
        // Twelve dollars in the wallet, three of settled shares, three
        // quarters locked. Counting both is a reserve of nearly twelve.
        val wrong = Reserve.lockedOf(12.76 + 3.11, 0.0, 0.75)
        assertEquals(0.86, Reserve.free(12.76, wrong), 0.01)
        // Counted once, three quarters of the wallet is locked and a quarter
        // is there to trade with.
        val right = Reserve.lockedOf(12.76, 0.0, 0.75)
        assertEquals(3.19, Reserve.free(12.76, right), 0.01)
    }
}
