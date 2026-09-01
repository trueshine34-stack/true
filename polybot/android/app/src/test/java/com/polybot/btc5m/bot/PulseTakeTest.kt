package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The margin this rule sells at, which is a floor and not a preference.
 *
 * It crosses the spread to take a side four readings agree on, so it has
 * already paid for that agreement in the ask; under fifteen percent the round
 * is a coin toss with a fee on it.
 */
class PulseTakeTest {

    private val tick = 0.01

    @Test
    fun `the offer sits fifteen percent over what the shares cost`() {
        val at = PulsePlan.takePrice(0.40, PulsePlan.Settings(), tick)

        // 40c and fifteen percent is 46c exactly.
        assertEquals(0.46, at, 1e-9)
    }

    /** A setting stored before the floor existed must not sell under it. */
    @Test
    fun `a smaller setting does not lower the floor`() {
        val at = PulsePlan.takePrice(0.40, PulsePlan.Settings(takePct = 0.05), tick)

        assertEquals(0.46, at, 1e-9)
        assertEquals(PulsePlan.MIN_TAKE_PCT, PulsePlan.takeOf(PulsePlan.Settings(takePct = 0.05)), 1e-9)
    }

    /** A larger one is a choice, and is kept. */
    @Test
    fun `a bigger setting is honoured`() {
        val at = PulsePlan.takePrice(0.40, PulsePlan.Settings(takePct = 0.50), tick)

        assertEquals(0.60, at, 1e-9)
    }

    /** Snapped up to the venue's step: a price it will not take is no price. */
    @Test
    fun `the ask is rounded up to a tradable price`() {
        val at = PulsePlan.takePrice(0.37, PulsePlan.Settings(), tick)

        // 37c and fifteen percent is 42.55c, which the venue cannot trade.
        assertEquals(0.43, at, 1e-9)
        assertTrue(at >= 0.37 * (1.0 + PulsePlan.MIN_TAKE_PCT))
    }

    /** And a dear side asks what it can rather than a price over a dollar. */
    @Test
    fun `an expensive entry is capped under a dollar`() {
        val at = PulsePlan.takePrice(0.92, PulsePlan.Settings(), tick)

        assertEquals(0.99, at, 1e-9)
    }
}
