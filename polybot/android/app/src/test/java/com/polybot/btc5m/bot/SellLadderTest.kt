package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The auto-sell ladder: one rung a minute, but never behind the market.
 */
class SellLadderTest {

    private val ladder = SellLadder.DEFAULT

    @Test
    fun theClockWalksItUpOneRungAMinute() {
        // Each rung takes over fifteen seconds before its minute, so by the
        // minute itself it is already in place.
        assertEquals(0.77, SellLadder.priceFor(0, null, ladder), 1e-9)
        assertEquals(0.84, SellLadder.priceFor(60, null, ladder), 1e-9)
        assertEquals(0.89, SellLadder.priceFor(120, null, ladder), 1e-9)
        assertEquals(0.93, SellLadder.priceFor(180, null, ladder), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(240, null, ladder), 1e-9)
    }

    @Test
    fun itStaysOnTheLastRungPastTheEnd() {
        assertEquals(0.97, SellLadder.priceFor(299, null, ladder), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(3000, null, ladder), 1e-9)
    }

    @Test
    fun clearingARungInTheFirstMinuteJumpsStraightToTheNext() {
        // The worked example: price passed 77 while still in minute one, so the
        // next sell goes out at 84 rather than waiting for the clock.
        assertEquals(0.84, SellLadder.priceFor(10, 0.78, ladder), 1e-9)
        assertEquals(1, SellLadder.stepFor(10, 0.78, ladder))
    }

    @Test
    fun theCustomLadderBoundariesAlsoLeadTheMinute() {
        val short = listOf(0.50, 0.90)
        assertEquals(0.50, SellLadder.priceFor(44, null, short), 1e-9)
        assertEquals(0.90, SellLadder.priceFor(45, null, short), 1e-9)
    }

    @Test
    fun itSkipsEveryRungThePriceHasAlreadyCleared() {
        assertEquals(0.93, SellLadder.priceFor(5, 0.90, ladder), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(5, 0.95, ladder), 1e-9)
        // Past the top rung there is nowhere higher to go.
        assertEquals(0.97, SellLadder.priceFor(5, 0.99, ladder), 1e-9)
    }

    @Test
    fun touchingARungExactlyIsNotClearingIt() {
        // At exactly 77 the resting order is still the one that should fill.
        assertEquals(0, SellLadder.stepFor(0, 0.77, ladder))
        assertEquals(1, SellLadder.stepFor(0, 0.7701, ladder))
    }

    @Test
    fun theClockWinsWhenItIsAhead() {
        // Minute four, price never went anywhere: the clock decides.
        assertEquals(0.93, SellLadder.priceFor(200, 0.40, ladder), 1e-9)
    }

    @Test
    fun itNeverSlipsBackDown() {
        // A spike to 90 in minute one puts us on 93. When the price falls back,
        // the floor keeps the ladder there rather than selling into the dip.
        val reached = SellLadder.stepFor(10, 0.90, ladder)
        assertEquals(3, reached)
        assertEquals(3, SellLadder.stepFor(20, 0.50, ladder, floor = reached))
        assertEquals(0.93, SellLadder.priceFor(20, 0.50, ladder, floor = reached), 1e-9)
    }

    @Test
    fun aCustomLadderIsHonoured() {
        val short = listOf(0.50, 0.90)
        assertEquals(0.50, SellLadder.priceFor(0, null, short), 1e-9)
        assertEquals(0.90, SellLadder.priceFor(60, null, short), 1e-9)
        assertEquals(0.90, SellLadder.priceFor(600, null, short), 1e-9)
        assertEquals(0.90, SellLadder.priceFor(0, 0.60, short), 1e-9)
    }

    @Test
    fun anEmptyLadderCannotCrash() {
        assertEquals(0, SellLadder.stepFor(120, 0.9, emptyList()))
    }

    @Test
    fun elapsedIsMeasuredInsideTheFiveMinuteWindow() {
        assertEquals(0, SellLadder.elapsedInWindow(1_787_625_000L))
        assertEquals(61, SellLadder.elapsedInWindow(1_787_625_061L))
        assertEquals(299, SellLadder.elapsedInWindow(1_787_625_299L))
    }
}

/**
 * Buying into a window that has not opened yet.
 *
 * The desk can point one window ahead, so a position can exist before its own
 * window starts. Its ladder must begin at the first rung — reading the clock's
 * current window instead started it four rungs up, selling at 93¢ what should
 * have been offered at 77¢.
 */
class SellLadderBeforeStartTest {

    private val ladder = SellLadder.DEFAULT

    @Test
    fun aWindowThatHasNotOpenedIsOnTheFirstRung() {
        // Ninety seconds before the window opens.
        assertEquals(0, SellLadder.stepFor(-90, null, ladder))
        assertEquals(0.77, SellLadder.priceFor(-90, null, ladder), 1e-9)
    }

    @Test
    fun theRungStillClimbsOnceItOpens() {
        assertEquals(0.77, SellLadder.priceFor(-1, null, ladder), 1e-9)
        assertEquals(0.77, SellLadder.priceFor(0, null, ladder), 1e-9)
        assertEquals(0.84, SellLadder.priceFor(60, null, ladder), 1e-9)
    }

    @Test
    fun priceStillOverridesTheClockBeforeTheStart() {
        // A pre-open position whose price already cleared the first rung.
        assertEquals(0.84, SellLadder.priceFor(-30, 0.80, ladder), 1e-9)
    }
}

/**
 * The rung changes a little before the minute, not on it.
 *
 * Flipping exactly on the boundary puts the replacement order into the book at
 * the moment it turns; arriving fifteen seconds early gets the offer in place
 * first. Spacing stays one minute — the whole sequence just shifts.
 */
class SellLadderLeadTest {

    private val ladder = SellLadder.DEFAULT

    @Test
    fun eachRungArrivesFifteenSecondsEarly() {
        assertEquals(0.77, SellLadder.priceFor(44, null, ladder), 1e-9)
        assertEquals(0.84, SellLadder.priceFor(45, null, ladder), 1e-9)

        assertEquals(0.84, SellLadder.priceFor(104, null, ladder), 1e-9)
        assertEquals(0.89, SellLadder.priceFor(105, null, ladder), 1e-9)

        assertEquals(0.93, SellLadder.priceFor(224, null, ladder), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(225, null, ladder), 1e-9)
    }

    @Test
    fun theSpacingIsStillAMinute() {
        val changes = (0..300).filter {
            SellLadder.stepFor(it.toLong(), null, ladder) !=
                SellLadder.stepFor((it - 1).toLong(), null, ladder)
        }
        assertEquals(listOf(45, 105, 165, 225), changes)
    }

    @Test
    fun theLeadIsAdjustable() {
        assertEquals(0.77, SellLadder.priceFor(59, null, ladder, leadSec = 0), 1e-9)
        assertEquals(0.84, SellLadder.priceFor(60, null, ladder, leadSec = 0), 1e-9)
        assertEquals(0.84, SellLadder.priceFor(30, null, ladder, leadSec = 30), 1e-9)
    }

    @Test
    fun theLeadDoesNotOpenTheLadderEarlyBeforeTheWindow() {
        // Fifteen seconds before the window opens is still the first rung.
        assertEquals(0.77, SellLadder.priceFor(-15, null, ladder), 1e-9)
        assertEquals(0.77, SellLadder.priceFor(-60, null, ladder), 1e-9)
    }

    @Test
    fun itStillStopsAtTheTopRung() {
        assertEquals(0.97, SellLadder.priceFor(299, null, ladder), 1e-9)
    }
}
