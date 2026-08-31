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

    /** A setting that arrived as nonsense must not stop the desk trading. */
    @Test
    fun `a broken reserve is no reserve`() {
        assertEquals(100.0, Reserve.free(100.0, Double.NaN), 1e-9)
        assertEquals(0.0, Reserve.free(Double.NaN, 10.0), 1e-9)
    }
}
