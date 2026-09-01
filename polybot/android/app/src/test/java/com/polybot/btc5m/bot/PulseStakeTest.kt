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
        // Priced at what a taken offer costs: forty cents plus the fee on it.
        val each = Exits.takenPrice(0.40)
        assertEquals(
            Math.floor((3.0 / each) * 10.0) / 10.0,
            PulsePlan.sharesFor(3.0, 0.40, 5.0),
            1e-9,
        )
        // And never more than the stake pays for, fee included.
        assertTrue(PulsePlan.sharesFor(3.0, 0.40, 5.0) * each <= 3.0 + 1e-9)
        assertTrue(PulsePlan.sharesFor(5.0, 0.80, 5.0) * Exits.takenPrice(0.80) <= 5.0 + 1e-9)
    }

    /**
     * The one that cost real money. The venue's floor used to be applied as a
     * raise — a stake too small to reach it bought the smallest order that
     * exists — which spends money the stake does not have, and where a
     * reserve is set that money is the reserve's. It buys nothing now.
     */
    @Test
    fun `a stake too small for the venue's minimum buys nothing`() {
        // Five shares at eighty cents is four dollars and change with the fee.
        assertEquals(0.0, PulsePlan.sharesFor(1.0, 0.80, 5.0), 1e-9)
        assertEquals(0.0, PulsePlan.sharesFor(3.9, 0.80, 5.0), 1e-9)
        assertTrue(PulsePlan.sharesFor(4.2, 0.80, 5.0) >= 5.0)
    }

    /** And the rule refuses the window rather than the venue refusing it. */
    @Test
    fun `the gate says so before an order is built`() {
        val read = PulsePlan.Read(
            elapsedSec = 120L,
            lead = 20.0,
            momentum = 1.0,
            volume = 1.0,
            lean = 0.70,
            upAsk = 0.80,
            downAsk = 0.20,
            ceiling = 0.95,
            cashUsd = 2.0,
        )
        assertEquals(
            "мало на минимальный ордер",
            PulsePlan.blockedBecause(
                read,
                PulsePlan.Settings(enabled = true, stakeUsd = 0.0, stakePct = 1.0),
                holding = false,
                minimumOrderSize = 5.0,
            ),
        )
    }

    @Test
    fun `nothing is bought without a price`() {
        assertEquals(0.0, PulsePlan.sharesFor(3.0, 0.0, 5.0), 1e-9)
        assertEquals(0.0, PulsePlan.sharesFor(0.0, 0.40, 5.0), 1e-9)
    }

    /** And the soft rule still sits out the start of a window. */
    @Test
    fun `the soft rule waits out the first stretch`() {
        assertEquals(75L, PulsePlan.soft().fromSec)
    }
}
