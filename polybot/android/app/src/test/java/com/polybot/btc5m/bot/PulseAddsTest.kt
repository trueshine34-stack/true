package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three bids that wait under an entry, and the exit that does not move
 * when one of them fills.
 */
class PulseAddsTest {

    @Test
    fun `three bids sit six cents apart under the entry`() {
        assertEquals(
            listOf(0.44, 0.38, 0.32),
            PulsePlan.addPrices(0.50, 0.01),
        )
    }

    /** Snapped to the venue's step, since a price it cannot trade is no bid. */
    @Test
    fun `the prices land on the tick`() {
        val at = PulsePlan.addPrices(0.505, 0.01)

        assertTrue(at.all { Math.abs(it * 100 - Math.round(it * 100)) < 1e-6 })
    }

    /** A cheap entry has fewer of them: below a cent there is no book. */
    @Test
    fun `bids that fall off the bottom are dropped`() {
        assertEquals(listOf(0.04), PulsePlan.addPrices(0.10, 0.01))
        assertEquals(emptyList<Double>(), PulsePlan.addPrices(0.05, 0.01))
    }

    @Test
    fun `nothing waits under nothing`() {
        assertEquals(emptyList<Double>(), PulsePlan.addPrices(0.0, 0.01))
    }

    /**
     * The exit is priced off the first entry and stays there, so a lot bought
     * on a lower rung makes more at the same ask rather than dragging the ask
     * down with it.
     */
    @Test
    fun `the exit is priced off the entry, not the average`() {
        val entry = 0.50
        val ask = PulsePlan.takePrice(entry, PulsePlan.Settings(), 0.01)

        // 50c and fifteen percent is 57.5, which rounds up to a tradable 58.
        assertEquals(0.58, ask, 1e-9)

        // A rung filled at 44c is bought 14c under an ask that has not moved.
        val cheaper = PulsePlan.addPrices(entry, 0.01).first()
        assertTrue(ask - cheaper > ask - entry)
    }
}
