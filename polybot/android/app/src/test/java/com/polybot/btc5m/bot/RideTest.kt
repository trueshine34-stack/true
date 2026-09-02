package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTest {

    @Test
    fun `nothing happens under the rung`() {
        assertEquals(Ride.Act.WAIT, Ride.act(0.80, rung = 0.86, secondsLeft = 200, sinceHighMs = 9_000))
        assertEquals(Ride.Act.WAIT, Ride.act(null, rung = 0.86, secondsLeft = 200, sinceHighMs = 9_000))
    }

    @Test
    fun `a rung reached is ridden while the price keeps making highs`() {
        assertEquals(
            Ride.Act.RIDE,
            Ride.act(0.88, rung = 0.86, secondsLeft = 200, sinceHighMs = 400),
        )
        // And sold once it has stood still for the pause.
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.88, rung = 0.86, secondsLeft = 200, sinceHighMs = 2_000),
        )
    }

    @Test
    fun `the pause is the setting, not the default`() {
        assertEquals(
            Ride.Act.RIDE,
            Ride.act(0.88, 0.86, secondsLeft = 200, sinceHighMs = 2_000, waitMs = 5_000),
        )
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.88, 0.86, secondsLeft = 200, sinceHighMs = 700, waitMs = 500),
        )
    }

    @Test
    fun `ninety-three is taken while there is still time to lose it`() {
        // Climbing, and it does not matter: with more than half a minute left
        // ninety-three is the trade.
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.93, rung = 0.96, secondsLeft = 36, sinceHighMs = 0),
        )
        // Inside the last half minute the run may be ridden again.
        assertEquals(
            Ride.Act.RIDE,
            Ride.act(0.93, rung = 0.90, secondsLeft = 35, sinceHighMs = 0),
        )
        // Still under the rung there, so still nothing to do.
        assertEquals(
            Ride.Act.WAIT,
            Ride.act(0.93, rung = 0.96, secondsLeft = 20, sinceHighMs = 9_000),
        )
    }

    @Test
    fun `ninety-eight is taken at once, at any time and at any rung`() {
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.98, rung = 0.99, secondsLeft = 30, sinceHighMs = 0),
        )
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.99, rung = 0.99, secondsLeft = 280, sinceHighMs = 0),
        )
    }

    @Test
    fun `the slider cannot ask for a pause the rule cannot keep`() {
        assertEquals(Ride.MIN_WAIT_MS, Ride.waitOf(0))
        assertEquals(Ride.MAX_WAIT_MS, Ride.waitOf(60_000))
        assertEquals(2_000L, Ride.waitOf(2_000))
    }
}

/**
 * The last seconds belong to the settlement, not to the book.
 */
class RideCloseTest {

    @Test
    fun `nothing is sold in the last six seconds`() {
        assertEquals(6L, Ride.CLOSE_SEC)
        // Not even at the prices that are otherwise taken at once.
        assertEquals(
            Ride.Act.SETTLE,
            Ride.act(0.99, rung = 0.90, secondsLeft = 6, sinceHighMs = 9_000),
        )
        assertEquals(
            Ride.Act.SETTLE,
            Ride.act(0.93, rung = 0.90, secondsLeft = 0, sinceHighMs = 9_000),
        )
        // A second earlier the rules still apply.
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.99, rung = 0.90, secondsLeft = 7, sinceHighMs = 0),
        )
    }
}

/**
 * Some positions are not runs, and waiting on them gives the recovery back.
 */
class RideFragileTest {

    @Test
    fun `a cheap buy is not ridden`() {
        assertEquals(0.30, Ride.CHEAP, 1e-9)
        assertTrue(Ride.fragile(cost = 0.24, lowWater = 0.24))
        assertTrue(!Ride.fragile(cost = 0.30, lowWater = 0.30))
    }

    @Test
    fun `nor is one that has been worth half of what it cost`() {
        // Bought at eighty, seen at thirty-nine: the book has already said
        // what it thinks of this side.
        assertTrue(Ride.fragile(cost = 0.80, lowWater = 0.39))
        assertTrue(!Ride.fragile(cost = 0.80, lowWater = 0.41))
        // Nothing seen yet is not the same as having been cheap.
        assertTrue(!Ride.fragile(cost = 0.80, lowWater = 0.0))
        assertTrue(!Ride.fragile(cost = 0.0, lowWater = 0.1))
    }

    @Test
    fun `such a position takes its rung the moment it is reached`() {
        // Still climbing, and it does not matter.
        assertEquals(
            Ride.Act.TAKE,
            Ride.act(0.86, rung = 0.86, secondsLeft = 200, sinceHighMs = 0, patient = false),
        )
        // Under the rung there is still nothing to do.
        assertEquals(
            Ride.Act.WAIT,
            Ride.act(0.85, rung = 0.86, secondsLeft = 200, sinceHighMs = 0, patient = false),
        )
        // And the close still outranks it.
        assertEquals(
            Ride.Act.SETTLE,
            Ride.act(0.99, rung = 0.86, secondsLeft = 3, sinceHighMs = 0, patient = false),
        )
        // A patient one waits, as before.
        assertEquals(
            Ride.Act.RIDE,
            Ride.act(0.86, rung = 0.86, secondsLeft = 200, sinceHighMs = 0),
        )
    }
}
