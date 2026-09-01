package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one entry puts in.
 *
 * A count of shares was the wrong unit: five shares of a side at eight cents
 * is forty cents at risk and five at eighty is four dollars, and this rule
 * takes both — so one setting meant ten times the money depending on what the
 * market happened to be charging.
 */
class PulseStakeTest {

    private val flat = PulsePlan.Settings(stakeUsd = 3.0)
    private val share = PulsePlan.Settings(stakeUsd = 3.0, stakePct = 0.10)

    @Test
    fun `a sum is that sum`() {
        assertEquals(3.0, PulsePlan.stakeOf(100.0, flat), 1e-9)
    }

    @Test
    fun `a share is that share of what is free`() {
        assertEquals(10.0, PulsePlan.stakeOf(100.0, share), 1e-9)
        // And it moves with the account, which is the point of setting one.
        assertEquals(5.0, PulsePlan.stakeOf(50.0, share), 1e-9)
    }

    /** A share, where one is set, is the answer; the sum beside it is idle. */
    @Test
    fun `a share wins over a sum`() {
        assertEquals(10.0, PulsePlan.stakeOf(100.0, share), 1e-9)
    }

    /**
     * An account too small for its own stake sits the window out; it does not
     * take a smaller position. Half a stake is a different bet, and a record
     * made of them answers nothing — so the stake stays what it is and the
     * cash gate refuses.
     */
    @Test
    fun `a stake is not shaved to fit`() {
        assertEquals(3.0, PulsePlan.stakeOf(1.5, flat), 1e-9)
        assertEquals(
            "нет денег",
            PulsePlan.blockedBecause(
                PulsePlan.Read(
                    elapsedSec = 90L,
                    lead = 20.0,
                    momentum = 1.0,
                    volume = 1.0,
                    lean = 0.70,
                    upAsk = 0.55,
                    downAsk = 0.45,
                    ceiling = 0.95,
                    cashUsd = 1.5,
                ),
                flat.copy(enabled = true),
                holding = false,
            ),
        )
        assertEquals(0.0, PulsePlan.stakeOf(-10.0, share), 1e-9)
    }

    @Test
    fun `the shares come out of the money at whatever the price is`() {
        // Three dollars of a forty-cent side is seven and a half shares.
        assertEquals(7.5, PulsePlan.sharesFor(3.0, 0.40, 5.0), 1e-9)
        // Five dollars buys six and a bit of an eighty-cent one.
        assertEquals(6.3, PulsePlan.sharesFor(5.0, 0.80, 5.0), 1e-9)
    }

    /**
     * Never under the venue's own floor: an order below it is refused, so a
     * stake too small to clear it buys the smallest order that exists.
     */
    @Test
    fun `the venue's minimum still holds`() {
        assertEquals(5.0, PulsePlan.sharesFor(1.0, 0.80, 5.0), 1e-9)
        // At a cent the floor is one over the price, not the five.
        assertTrue(PulsePlan.sharesFor(0.10, 0.01, 5.0) >= 100.0)
    }

    @Test
    fun `nothing is bought without a price`() {
        assertEquals(0.0, PulsePlan.sharesFor(3.0, 0.0, 5.0), 1e-9)
    }

    /** And the soft rule still sits out the start of a window. */
    @Test
    fun `the soft rule waits out the first stretch`() {
        assertEquals(75L, PulsePlan.soft().fromSec)
    }
}
