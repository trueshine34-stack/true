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
class ReserveShareTest {

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

    @Test
    fun `money on its way is on both sides of the sum`() {
        // Ten dollars of the run is in a sale the venue has not paid yet. The
        // share is taken of the whole run, and what is free counts it too —
        // which is what lets the next entry be sized the moment a position is
        // closed rather than twenty seconds later.
        val wallet = 2.0
        val pending = 10.0
        val locked = Reserve.lockedOf(wallet + pending, 0.0, 0.75)
        assertEquals(9.0, locked, 1e-9)
        assertEquals(3.0, Reserve.free(wallet + pending, locked), 1e-9)
    }
}

/**
 * A losing side stops being money held before the window has even closed.
 */
class ReserveDoomedTest {

    @Test
    fun `the last five seconds name the side that has lost`() {
        assertEquals(5L, Reserve.DOOMED_SEC)
        // Price above the open: Down is not coming back.
        assertEquals("Down", Reserve.losingSide(12.0, 5))
        assertEquals("Up", Reserve.losingSide(-12.0, 0))
    }

    @Test
    fun `earlier in the window nothing has lost`() {
        assertEquals(null, Reserve.losingSide(12.0, 6))
        assertEquals(null, Reserve.losingSide(-40.0, 120))
    }

    @Test
    fun `level, or unknown, decides nothing`() {
        assertEquals(null, Reserve.losingSide(0.0, 2))
        assertEquals(null, Reserve.losingSide(null, 2))
    }
}

/** What a buy really takes: the order, plus the fee charged on top of it. */
class ReserveCostTest {

    @Test
    fun `the fee is on top, not out of it`() {
        // Ten shares at eighty cents: eight dollars, plus 7% x 0.8 x 0.2 each.
        assertEquals(8.112, Reserve.buyCost(10.0, 0.80), 1e-9)
        assertEquals(0.0, Reserve.buyCost(0.0, 0.80), 1e-9)
        assertEquals(0.0, Reserve.buyCost(10.0, 0.0), 1e-9)
    }
}
