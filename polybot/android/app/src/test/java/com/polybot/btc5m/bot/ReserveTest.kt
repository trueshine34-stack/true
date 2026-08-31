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
