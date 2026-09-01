package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The second pulse: the same four questions with the answers taken earlier.
 *
 * Every gate it opens is a window the strict rule sat out, so the test is
 * that a reading refused there is taken here — and that nothing else moved.
 */
class PulseSoftTest {

    private val strict = PulsePlan.Settings(enabled = true)
    private val soft = PulsePlan.soft().copy(enabled = true)

    private fun read(
        lead: Double,
        lean: Double,
        volume: Double,
        ask: Double,
        elapsed: Long = 60L,
    ) = PulsePlan.Read(
        elapsedSec = elapsed,
        lead = lead,
        momentum = if (lead >= 0) 1.0 else -1.0,
        volume = volume,
        lean = lean,
        upAsk = ask,
        downAsk = 1.0 - ask,
        ceiling = 0.95,
        cashUsd = 100.0,
    )

    /** Half the lead is enough here and was not there. */
    @Test
    fun aThinnerLeadIsTakenByTheSoftRule() {
        val thin = read(lead = 4.0, lean = 0.60, volume = 1.0, ask = 0.55)

        assertNotNull(PulsePlan.blockedBecause(thin, strict, holding = false))
        assertNull(PulsePlan.blockedBecause(thin, soft, holding = false))
    }

    /** A book that is merely not against the side, rather than behind it. */
    @Test
    fun anEvenBookIsEnough() {
        val flat = read(lead = 8.0, lean = 0.52, volume = 1.0, ask = 0.55)

        assertNotNull(PulsePlan.blockedBecause(flat, strict, holding = false))
        assertNull(PulsePlan.blockedBecause(flat, soft, holding = false))
    }

    /** And volume that is merely not dead. */
    @Test
    fun quietVolumeIsEnough() {
        val quiet = read(lead = 8.0, lean = 0.60, volume = 0.5, ask = 0.55)

        assertNotNull(PulsePlan.blockedBecause(quiet, strict, holding = false))
        assertNull(PulsePlan.blockedBecause(quiet, soft, holding = false))
    }

    /** A wider band of odds at both ends. */
    @Test
    fun theOddsBandIsWider() {
        val cheap = read(lead = 8.0, lean = 0.60, volume = 1.0, ask = 0.25)
        val dear = read(lead = 8.0, lean = 0.60, volume = 1.0, ask = 0.85)

        assertNotNull(PulsePlan.blockedBecause(cheap, strict, holding = false))
        assertNull(PulsePlan.blockedBecause(cheap, soft, holding = false))
        assertNotNull(PulsePlan.blockedBecause(dear, strict, holding = false))
        assertNull(PulsePlan.blockedBecause(dear, soft, holding = false))
    }

    /** It looks earlier in the window, too. */
    @Test
    fun itStartsLookingSooner() {
        val early = read(lead = 8.0, lean = 0.60, volume = 1.0, ask = 0.55, elapsed = 25L)

        assertEquals("рано", PulsePlan.blockedBecause(early, strict, holding = false))
        assertNull(PulsePlan.blockedBecause(early, soft, holding = false))
    }

    /**
     * What it does not soften: the momentum has to agree, and the margin it
     * sells at is the same floor. Trading more often on thinner evidence is
     * the point; selling cheaper is not.
     */
    @Test
    fun momentumAndMarginAreNotSoftened() {
        val against = read(lead = 8.0, lean = 0.60, volume = 1.0, ask = 0.55)
            .copy(momentum = -1.0)

        assertEquals("импульс против", PulsePlan.blockedBecause(against, soft, holding = false))
        assertEquals(
            PulsePlan.takePrice(0.40, strict, 0.01),
            PulsePlan.takePrice(0.40, soft, 0.01),
            1e-9,
        )
    }
}
