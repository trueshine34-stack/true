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

    /**
     * A position bought in two goes was offered twice at the same rung, which
     * is one offer for twice the size wearing two hats: the book fills the
     * first and leaves the second exactly where it was.
     */
    @Test
    fun eachFurtherOfferSitsAStepUnderTheOneBeforeIt() {
        assertEquals(0.84, SellLadder.stackedPrice(0.84, 0), 1e-9)
        assertEquals(0.82, SellLadder.stackedPrice(0.84, 1), 1e-9)
        assertEquals(0.80, SellLadder.stackedPrice(0.84, 2), 1e-9)
        assertEquals(0.78, SellLadder.stackedPrice(0.84, 3), 1e-9)
    }

    /** A rung near the floor cannot carry a stack, and zero is not a price. */
    @Test
    fun theStackNeverFallsThroughTheFloor() {
        assertEquals(0.01, SellLadder.stackedPrice(0.03, 5, tick = 0.01), 1e-9)
        assertEquals(0.01, SellLadder.stackedPrice(0.01, 1, tick = 0.01), 1e-9)
    }

    @Test
    fun `a shorter rung spends the ladder sooner`() {
        val rungs = listOf(0.77, 0.84, 0.89, 0.93, 0.97)
        // At half a minute a rung, the ladder is at its top by the halfway
        // mark instead of at the close.
        val half = 30L
        assertEquals(0, SellLadder.stepFor(0, null, rungs, stepSec = half))
        assertEquals(1, SellLadder.stepFor(30, null, rungs, stepSec = half))
        assertEquals(2, SellLadder.stepFor(60, null, rungs, stepSec = half))
        assertEquals(4, SellLadder.stepFor(120, null, rungs, stepSec = half))
        assertEquals(4, SellLadder.stepFor(280, null, rungs, stepSec = half))
    }

    @Test
    fun `a minute stays the minute it was`() {
        val rungs = listOf(0.77, 0.84, 0.89, 0.93, 0.97)
        assertEquals(1, SellLadder.stepFor(60, null, rungs))
        assertEquals(1, SellLadder.stepFor(60, null, rungs, stepSec = 60))
    }

    @Test
    fun `a nonsense rung length falls back to the minute`() {
        val rungs = listOf(0.77, 0.84, 0.89, 0.93, 0.97)
        assertEquals(2, SellLadder.stepFor(120, null, rungs, stepSec = 0))
    }

    @Test
    fun `the half-minute ladder reaches the close instead of the halfway mark`() {
        val ten = SellLadder.HALF_MINUTE
        val step = 30L
        // One rung every thirty seconds, all the way through the window.
        assertEquals(0.77, SellLadder.priceFor(0, null, ten, stepSec = step), 1e-9)
        assertEquals(0.83, SellLadder.priceFor(60, null, ten, stepSec = step), 1e-9)
        assertEquals(0.88, SellLadder.priceFor(120, null, ten, stepSec = step), 1e-9)
        assertEquals(0.92, SellLadder.priceFor(180, null, ten, stepSec = step), 1e-9)
        assertEquals(0.96, SellLadder.priceFor(240, null, ten, stepSec = step), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(270, null, ten, stepSec = step), 1e-9)
    }

    @Test
    fun `five rungs at that step are spent by the halfway mark`() {
        // Which is the reason for the longer one: from here on the old ladder
        // asks the same price for half the window.
        val five = SellLadder.DEFAULT
        assertEquals(0.97, SellLadder.priceFor(135, null, five, stepSec = 30L), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(290, null, five, stepSec = 30L), 1e-9)
    }

    @Test
    fun `it still walks and never slips back`() {
        val ten = SellLadder.HALF_MINUTE
        // Price cleared 88 in the first half-minute: the offer goes above it.
        assertEquals(0.90, SellLadder.priceFor(5, 0.885, ten, stepSec = 30L), 1e-9)
        // And a rung reached is never given up when the price falls back.
        val reached = SellLadder.stepFor(5, 0.885, ten, stepSec = 30L)
        assertEquals(reached, SellLadder.stepFor(20, 0.50, ten, floor = reached, stepSec = 30L))
    }
}

/**
 * The rung as a floor rather than a ceiling.
 *
 * An offer resting at the rung is a promise to sell at exactly that price: a
 * book that runs straight through it pays the promise and keeps the rest of
 * the move. So until the last minute nothing rests — the bid is watched, and
 * when it reaches the rung the shares go into it at whatever it is paying.
 */
class SellLadderWatchTest {

    @Test
    fun `the rung is watched until the last minute`() {
        assertEquals(false, SellLadder.restsNow(300))
        assertEquals(false, SellLadder.restsNow(61))
        assertEquals(true, SellLadder.restsNow(60))
        assertEquals(true, SellLadder.restsNow(0))
    }

    @Test
    fun `a position bought before its window opens is not late`() {
        // More than five minutes left, because the window has not started.
        assertEquals(false, SellLadder.restsNow(320))
    }

    @Test
    fun `the bid has to reach the rung, and anything over it counts`() {
        assertEquals(false, SellLadder.reached(0.83, 0.84))
        assertEquals(true, SellLadder.reached(0.84, 0.84))
        // The point of watching: a bid that jumped past pays what it jumped to.
        assertEquals(true, SellLadder.reached(0.91, 0.84))
    }

    @Test
    fun `no bid is not a reached rung`() {
        assertEquals(false, SellLadder.reached(null, 0.84))
        assertEquals(false, SellLadder.reached(0.0, 0.84))
        assertEquals(false, SellLadder.reached(0.90, 0.0))
    }

    @Test
    fun `the last minute is adjustable`() {
        assertEquals(false, SellLadder.restsNow(31, restSec = 30))
        assertEquals(true, SellLadder.restsNow(30, restSec = 30))
    }
}
