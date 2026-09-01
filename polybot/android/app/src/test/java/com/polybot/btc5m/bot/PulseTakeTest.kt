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

/**
 * How dear a side may be bought as the window runs out.
 *
 * The band's top is set for a window with time left in it. With two minutes
 * to run the same price is charging for a move that is nearly finished, and
 * with one it is charging for one that is all but over.
 */
class PulseLateCapTest {

    private val strict = PulsePlan.Settings()
    private val soft = PulsePlan.soft()

    @Test
    fun `the band's own top holds while the window is young`() {
        assertEquals(0.80, PulsePlan.topPrice(0L, strict), 1e-9)
        assertEquals(0.80, PulsePlan.topPrice(179L, strict), 1e-9)
    }

    /**
     * And keeps holding across the minute mark itself. A new candle opens
     * there, and the first seconds of one say nothing yet — the allowance
     * waits a quarter of a minute for it to.
     */
    @Test
    fun `the allowance waits fifteen seconds past the minute`() {
        assertEquals(0.80, PulsePlan.topPrice(180L, strict), 1e-9)
        assertEquals(0.80, PulsePlan.topPrice(194L, strict), 1e-9)
        assertEquals(0.83, PulsePlan.topPrice(195L, strict), 1e-9)

        assertEquals(0.83, PulsePlan.topPrice(240L, strict), 1e-9)
        assertEquals(0.83, PulsePlan.topPrice(254L, strict), 1e-9)
        assertEquals(0.86, PulsePlan.topPrice(255L, strict), 1e-9)
    }

    @Test
    fun `and it holds to the close once it has lifted`() {
        assertEquals(0.86, PulsePlan.topPrice(299L, strict), 1e-9)
    }

    /**
     * It only ever lifts. The soft rule is already allowed to pay 88c, and a
     * late allowance must not turn into a late restriction.
     */
    @Test
    fun `a wider band is not narrowed by the allowance`() {
        assertEquals(0.88, PulsePlan.topPrice(195L, soft), 1e-9)
        assertEquals(0.88, PulsePlan.topPrice(255L, soft), 1e-9)
    }

    /** And the desk's own ceiling still sits over the top of it. */
    @Test
    fun `the desk's ceiling still binds`() {
        val read = PulsePlan.Read(
            elapsedSec = 260L,
            lead = 20.0,
            momentum = 1.0,
            volume = 1.0,
            lean = 0.70,
            upAsk = 0.93,
            downAsk = 0.07,
            // The last minute closes the desk's own cap at 91c.
            ceiling = BuyCap.ceiling(260L),
            cashUsd = 100.0,
        )
        val why = PulsePlan.blockedBecause(read, strict.copy(enabled = true), holding = false)

        // Refused by this rule's own 86, which is the tighter of the two.
        assertEquals("дорого 93¢ из 86¢", why)
    }
}

/**
 * The last minute, where settlement is close enough to be worth waiting for.
 *
 * A side that is winning with a minute to run is nearly settled, and
 * settlement pays a whole dollar and charges no fee — so an offer at eighty
 * gives away twenty cents of money that was very likely already ours, and a
 * ladder walked down to seventy-seven asks for that giveaway by design.
 */
class PulseLateFloorTest {

    @Test
    fun `nothing is offered under ninety inside the last sixty-five seconds`() {
        assertEquals(0.90, PulsePlan.lateFloor(0.77, 65L), 1e-9)
        assertEquals(0.90, PulsePlan.lateFloor(0.80, 30L), 1e-9)
        assertEquals(0.90, PulsePlan.lateFloor(0.46, 0L), 1e-9)
    }

    /** Before that the exit's own price stands, whatever it is. */
    @Test
    fun `earlier the exit asks what it asks`() {
        assertEquals(0.77, PulsePlan.lateFloor(0.77, 66L), 1e-9)
        assertEquals(0.46, PulsePlan.lateFloor(0.46, 200L), 1e-9)
    }

    /** It is a floor and not a price: an ask already above it is left alone. */
    @Test
    fun `a dearer ask is not pulled down to it`() {
        assertEquals(0.97, PulsePlan.lateFloor(0.97, 20L), 1e-9)
    }

    /** A window that has already closed is not the last minute of one. */
    @Test
    fun `a window past its close is left alone`() {
        assertEquals(0.77, PulsePlan.lateFloor(0.77, -5L), 1e-9)
    }
}

/**
 * Waiting out a move before taking a price that has been reached.
 *
 * These exits watch rather than rest, so the rule chooses the moment it
 * crosses — and the bid that has just arrived at the target is usually on its
 * way past it. Crossing there sells the middle of a run.
 */
class PulseRideTest {

    /** Two and a half seconds without a new high is a move that is over. */
    @Test
    fun `the wait is long enough to be a pause and short enough to be one`() {
        assertEquals(2_500L, PulsePlan.RIDE_MS)
    }
}
