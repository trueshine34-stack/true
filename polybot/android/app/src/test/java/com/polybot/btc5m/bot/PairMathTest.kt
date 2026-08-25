package com.polybot.btc5m.bot

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pair bot's arithmetic.
 *
 * The strategy is only sound because one Up plus one Down is worth exactly $1,
 * so these tests keep the accounting honest about what a pair costs, what it
 * returns, and where the fee lands.
 */
class PairMathTest {

    private val settings = PairSettings()

    @Test
    fun aPairAlwaysReturnsADollar() {
        val up = PairLeg(shares = 10.0, costUsd = 4.2)
        val down = PairLeg(shares = 10.0, costUsd = 5.3)

        // Whichever way the window resolves, ten pairs pay ten dollars.
        assertEquals(10.0, PairMath.settlementProceeds(up, down, "Up"), 1e-9)
        assertEquals(10.0, PairMath.settlementProceeds(up, down, "Down"), 1e-9)
        assertEquals(0.5, PairMath.lockedProfit(up, down), 1e-9)
    }

    @Test
    fun onlyTheUnmatchedExcessIsExposed() {
        val up = PairLeg(shares = 15.0, costUsd = 6.3)
        val down = PairLeg(shares = 10.0, costUsd = 5.3)

        assertEquals(15.0, PairMath.settlementProceeds(up, down, "Up"), 1e-9)
        assertEquals(10.0, PairMath.settlementProceeds(up, down, "Down"), 1e-9)
        // The five loose shares are the entire directional risk.
        assertEquals(5.0, up.shares - down.shares, 1e-9)
    }

    @Test
    fun theTighterOfTheTwoCeilingsWins() {
        // 3% on a dollar payout allows 97.09¢, but the hard cap is 95¢.
        assertEquals(0.95, PairMath.maxPairCost(0.03, 0.95), 1e-9)
        // Demanding 10% binds before the hard cap does.
        assertEquals(1.0 / 1.10, PairMath.maxPairCost(0.10, 0.95), 1e-9)
    }

    @Test
    fun theWorkedExampleRestsDownAtFiftyThree() {
        // Up bought at 42¢ as a maker, so no fee is in its average.
        val budget = PairMath.maxPairCost(settings.minPairProfitPct, settings.maxPairAvg)
        val limit = PairMath.completionLimit(
            heavyAvg = 0.42,
            budget = budget,
            taker = false,
            feeRate = 0.07,
            feeExponent = 1.0,
        )
        assertEquals(0.53, limit, 1e-9)
    }

    @Test
    fun crossingTheSpreadEatsIntoTheCompletionPrice() {
        val budget = PairMath.maxPairCost(settings.minPairProfitPct, settings.maxPairAvg)
        val resting = PairMath.completionLimit(0.42, budget, false, 0.07, 1.0)
        val crossing = PairMath.completionLimit(0.42, budget, true, 0.07, 1.0)

        assertTrue("a taker must bid lower to buy the same margin", crossing < resting)

        // The solved price plus its own fee must fit the budget exactly.
        val fee = Strategy.takerFeePerShare(crossing, 0.07, 1.0)
        assertEquals(budget, 0.42 + crossing + fee, 5e-4)
    }

    @Test
    fun noRoomLeftMeansNoOrder() {
        val budget = PairMath.maxPairCost(0.03, 0.95)
        assertEquals(0.0, PairMath.completionLimit(0.97, budget, false, 0.07, 1.0), 1e-9)
    }

    @Test
    fun theCapCountsTheProposedPriceWhenTheOtherSideIsEmpty() {
        val down = PairLeg()
        val up = PairLeg(shares = 5.0, costUsd = 5.0 * 0.42)

        // First Down at 53¢ makes the pair exactly 95¢ — allowed.
        assertFalse(PairMath.breachesPairCap(down, up, 0.53, 5.0, 0.95))
        // At 54¢ it is 96¢ — refused.
        assertTrue(PairMath.breachesPairCap(down, up, 0.54, 5.0, 0.95))
    }

    @Test
    fun theCapBlocksAverageUpOnASideAlreadyHeld() {
        val up = PairLeg(shares = 5.0, costUsd = 5.0 * 0.42)
        val down = PairLeg(shares = 5.0, costUsd = 5.0 * 0.53)

        // Adding Up at 60¢ lifts its average to 51¢, making the pair 104¢.
        assertTrue(PairMath.breachesPairCap(up, down, 0.60, 5.0, 0.95))
        // Adding Up at 40¢ pulls the average down; still inside the cap.
        assertFalse(PairMath.breachesPairCap(up, down, 0.40, 5.0, 0.95))
    }

    @Test
    fun cheapLegsRotateSooner() {
        val cheap = PairLeg(shares = 10.0, costUsd = 10.0 * 0.42)
        val dear = PairLeg(shares = 10.0, costUsd = 10.0 * 0.60)

        assertEquals(0.42 * 1.05, PairMath.rotateTarget(cheap, settings)!!, 1e-9)
        assertEquals(0.60 * 1.10, PairMath.rotateTarget(dear, settings)!!, 1e-9)
        assertNull(PairMath.rotateTarget(PairLeg(), settings))
    }

    @Test
    fun buysSnapDownAndSellsSnapUp() {
        // Never round a buy up or a sell down: either would quietly spend the
        // margin the snapping exists to protect.
        assertEquals(0.53, PairMath.snapDown(0.5349, 0.01), 1e-9)
        assertEquals(0.54, PairMath.snapUp(0.5301, 0.01), 1e-9)
        assertEquals(0.53, PairMath.snapDown(0.53, 0.01), 1e-9)
        assertEquals(0.53, PairMath.snapUp(0.53, 0.01), 1e-9)
    }

    @Test
    fun sellingPartOfALegLeavesItsAverageAlone() {
        val leg = PairLeg()
        leg.buy(5.0, 5.0 * 0.40)
        leg.buy(5.0, 5.0 * 0.50)
        assertEquals(0.45, leg.avg, 1e-9)

        leg.sell(5.0)
        assertEquals(5.0, leg.shares, 1e-9)
        assertEquals(0.45, leg.avg, 1e-9)

        leg.sell(5.0)
        assertEquals(0.0, leg.shares, 1e-9)
        assertEquals(0.0, leg.costUsd, 1e-9)
    }

    /**
     * The whole worked example end to end, priced as the venue would.
     *
     * Both legs rest, so neither pays a fee, and the pair settles for a dollar.
     */
    @Test
    fun theWorkedExampleTurnsAProfitWhicheverWayItResolves() {
        val up = PairLeg()
        val down = PairLeg()

        var spent = 0.0
        // 42/58 on the screen: buy Up at 42¢, wait, complete Down at 53¢.
        up.buy(5.0, 5.0 * 0.42).also { spent += 5.0 * 0.42 }
        down.buy(5.0, 5.0 * 0.53).also { spent += 5.0 * 0.53 }

        assertEquals(0.95, up.avg + down.avg, 1e-9)
        assertTrue(up.avg + down.avg <= PairSettings().maxPairAvg + 1e-9)

        for (winner in listOf("Up", "Down")) {
            val pnl = PairMath.settlementProceeds(up, down, winner) - spent
            assertEquals(0.25, pnl, 1e-9)
            assertTrue("$winner must pay the same", pnl > 0)
        }
    }

    /**
     * A rotation banks profit on the leg that ran and puts it into the other
     * side. The point is not the single trade — it is that the pair average
     * ends up lower than it started.
     */
    @Test
    fun rotatingLowersThePairAverage() {
        val up = PairLeg()
        val down = PairLeg()
        up.buy(10.0, 10.0 * 0.42)
        down.buy(10.0, 10.0 * 0.53)
        val before = up.avg + down.avg

        // Up runs to 50¢; sell half of it there.
        val soldShares = 10.0 * PairSettings().rotateFraction
        val proceeds = soldShares * 0.50
        up.sell(soldShares)
        // Down has fallen to 45¢ on the other side of the same move; buy a lot.
        down.buy(5.0, 5.0 * 0.45)

        val after = up.avg + down.avg
        assertTrue("pair average must fall, was $before now $after", after < before)
        assertTrue("the sale must have banked cash", proceeds > soldShares * 0.42)
        assertTrue(abs(up.avg - 0.42) < 1e-9)
    }
}

/**
 * Where the pair bot's bids sit relative to the market.
 *
 * The margin is made entirely of the distance between the bids and fair value,
 * so this is the part worth pinning down: bidding too close buys pairs at a
 * dollar, and bidding too far never gets filled.
 */
class PairBidTest {

    private val budget = PairMath.maxPairCost(0.03, 0.95)

    @Test
    fun bothBidsTogetherComeToTheBudget() {
        val up = PairMath.allocatedBid(0.42, 0.58, budget)!!
        val down = PairMath.allocatedBid(0.58, 0.42, budget)!!

        assertEquals(budget, up + down, 1e-9)
        assertEquals(0.95, up + down, 1e-9)
    }

    @Test
    fun eachBidSitsBelowItsOwnMid() {
        for (mid in listOf(0.10, 0.25, 0.42, 0.50, 0.75, 0.90)) {
            val bid = PairMath.allocatedBid(mid, 1.0 - mid, budget)!!
            assertTrue("bid $bid must be under mid $mid", bid < mid)
        }
    }

    @Test
    fun theDiscountIsSharedInProportion() {
        // Both sides give up the same fraction of fair value, so neither leg is
        // asked to wait noticeably longer than the other.
        val cheap = PairMath.allocatedBid(0.20, 0.80, budget)!!
        val dear = PairMath.allocatedBid(0.80, 0.20, budget)!!

        assertEquals(cheap / 0.20, dear / 0.80, 1e-9)
        assertEquals(budget, cheap / 0.20, 1e-9)
    }

    @Test
    fun aOneSidedBookYieldsNoBid() {
        assertNull(PairMath.allocatedBid(0.0, 1.0, budget))
        assertNull(PairMath.allocatedBid(0.0, 0.0, budget))
    }

    @Test
    fun theSecondLegIsPricedOffWhatTheFirstActuallyCost() {
        // Up filled at 39¢, better than the 39.9¢ the allocation asked for.
        // The saving must widen the room for Down, not vanish.
        val room = PairMath.completionLimit(0.39, budget, false, 0.07, 1.0)
        assertEquals(0.56, room, 1e-9)
        assertTrue(room > PairMath.allocatedBid(0.58, 0.42, budget)!!)
    }

    /**
     * Joining both best bids is the obvious thing to do and it does not work.
     * This records why the bot bids away from the book instead.
     */
    @Test
    fun joiningBothBestBidsWouldMissTheTarget() {
        // A 2¢ spread each side around a 42/58 market.
        val bidUp = 0.41
        val bidDown = 0.57
        assertEquals(0.98, bidUp + bidDown, 1e-9)
        assertTrue("joining the book buys a 98¢ pair", bidUp + bidDown > 0.95)
    }
}

/**
 * The lead cap, and the failure it exists to prevent.
 *
 * Without it the bot bought twenty shares of one side and none of the other,
 * because "buy the cheaper side" kept pointing at the leg that was collapsing.
 */
class PairLeadCapTest {

    private val settings = PairSettings()
    private val minOrder = 5.0

    private fun lot(cheap: Boolean) =
        PairMath.lotFor(settings.lotShares, minOrder, cheap, settings.cheapSideBonusPct)

    @Test
    fun theCheaperSideGetsTheBiggerLot() {
        assertEquals(6.5, lot(cheap = true), 1e-9)
        assertEquals(5.0, lot(cheap = false), 1e-9)
        assertTrue(lot(cheap = true) > lot(cheap = false))
    }

    @Test
    fun aLotNeverFallsUnderTheVenueFloor() {
        assertEquals(5.0, PairMath.lotFor(1.0, 5.0, cheap = false, bonusPct = 0.3), 1e-9)
        assertEquals(5.0, PairMath.lotFor(2.0, 5.0, cheap = true, bonusPct = 0.3), 1e-9)
    }

    @Test
    fun aLevelBookMayBuyExactlyOneLot() {
        assertEquals(6.5, PairMath.allowance(0.0, 0.0, lot(true)), 1e-9)
        assertEquals(5.0, PairMath.allowance(10.0, 10.0, lot(false)), 1e-9)
    }

    @Test
    fun aSideAlreadyALotAheadMayNotBuy() {
        // Cheap side holds its full lot against an empty other side.
        assertEquals(0.0, PairMath.allowance(6.5, 0.0, lot(true)), 1e-9)
        assertTrue(PairMath.allowance(6.5, 0.0, lot(true)) < minOrder)
    }

    @Test
    fun theSideThatIsBehindMayBuyMore() {
        // Up holds 6.5 as the cheap side, Down holds none. Down is the dear
        // side, so it takes its own smaller lot plus the gap it has to close.
        assertEquals(11.5, PairMath.allowance(0.0, 6.5, lot(cheap = false)), 1e-9)
    }

    /**
     * Replays the run from the failing screenshot: Up quoted 46, 29, 25, 22¢
     * while Down climbed away, so every lot went into Up and no pair was ever
     * formed. The cap has to stop that after a single lot.
     */
    @Test
    fun theCollapsingSideCannotSwallowTheBalance() {
        val up = PairLeg()
        val down = PairLeg()

        for (price in listOf(0.46, 0.29, 0.25, 0.22, 0.18, 0.12)) {
            val allowed = PairMath.allowance(up.shares, down.shares, lot(cheap = true))
            if (allowed < minOrder) continue
            up.buy(minOf(lot(cheap = true), allowed), price * minOf(lot(cheap = true), allowed))
        }

        assertEquals("only one lot may go in", 6.5, up.shares, 1e-9)
        assertEquals(0.0, down.shares, 1e-9)
        assertTrue("exposure must stay small, was ${up.costUsd}", up.costUsd < 3.5)
    }

    @Test
    fun withoutTheCapItRunsAway() {
        // The same six quotes with no cap — this is what the screenshot showed.
        val up = PairLeg()
        for (price in listOf(0.46, 0.29, 0.25, 0.22, 0.18, 0.12)) {
            up.buy(5.0, price * 5.0)
        }
        assertEquals(30.0, up.shares, 1e-9)
        assertTrue("uncapped exposure is multiples of the capped one", up.costUsd > 6.0)
    }

    @Test
    fun theSidesTakeTurns() {
        val up = PairLeg()
        val down = PairLeg()
        var bought = 0

        // Alternate attempts; each side may only proceed when it is not a lot
        // ahead, so the counts stay within one lot of each other throughout.
        repeat(12) { round ->
            val side = if (round % 2 == 0) up else down
            val otherLeg = if (round % 2 == 0) down else up
            val allowed = PairMath.allowance(side.shares, otherLeg.shares, lot(cheap = false))
            if (allowed < minOrder) return@repeat
            side.buy(5.0, 5.0 * 0.47)
            bought += 1
            assertTrue(
                "counts drifted apart at round $round",
                abs(up.shares - down.shares) <= lot(cheap = true) + 1e-9,
            )
        }
        assertTrue("both sides must actually get bought", up.shares > 0 && down.shares > 0)
        assertEquals(12, bought)
    }
}

/**
 * The window's price history, and the bids drawn from it.
 */
class PairLevelTrackTest {

    private fun q(bid: Double?, ask: Double?) = Quote(bid, ask)

    @Test
    fun aLevelSatOnForAWhileCountsAsOneVisit() {
        val track = LevelTrack()
        repeat(20) { track.record(q(0.41, 0.43), 0.01) }

        // Twenty samples, one arrival: the ladder answers "how often does the
        // market come back here", not "how long did it stay".
        assertEquals(1, track.visits[42])
        assertEquals(1, track.visits.size)
    }

    @Test
    fun leavingAndReturningCountsTwice() {
        val track = LevelTrack()
        track.record(q(0.41, 0.43), 0.01)
        track.record(q(0.44, 0.46), 0.01)
        track.record(q(0.41, 0.43), 0.01)

        assertEquals(2, track.visits[42])
        assertEquals(1, track.visits[45])
    }

    @Test
    fun itRemembersTheCheapestOfferNotTheCheapestMid() {
        val track = LevelTrack()
        track.record(q(0.41, 0.43), 0.01)
        track.record(q(0.36, 0.38), 0.01)
        track.record(q(0.44, 0.46), 0.01)

        // What a buy could have paid is the ask, not the mid.
        assertEquals(0.38, track.lowAsk!!, 1e-9)
        assertEquals(0.37, track.lowMid!!, 1e-9)
        assertEquals(0.45, track.highMid!!, 1e-9)
    }

    @Test
    fun aQuoteWithNoMidIsIgnored() {
        val track = LevelTrack()
        track.record(q(null, null), 0.01)

        assertTrue(track.visits.isEmpty())
        assertNull(track.lowAsk)
    }

    @Test
    fun aOneSidedQuoteStillCounts() {
        val track = LevelTrack()
        // Only a bid: mid falls back to it, and there is no ask to record.
        track.record(q(0.41, null), 0.01)

        assertEquals(1, track.visits[41])
        assertNull(track.lowAsk)
    }

    @Test
    fun patientBidsSitUnderTheWindowLowAndUrgentOnesOverIt() {
        val low = 0.38
        assertEquals(0.35, PairMath.anchoredBid(low, 3.0, urgent = false), 1e-9)
        assertEquals(0.41, PairMath.anchoredBid(low, 3.0, urgent = true), 1e-9)
    }

    @Test
    fun theAnchorIsThreeCentsEitherWay() {
        val low = 0.50
        val patient = PairMath.anchoredBid(low, 3.0, urgent = false)
        val urgent = PairMath.anchoredBid(low, 3.0, urgent = true)

        assertEquals(0.06, urgent - patient, 1e-9)
        assertTrue(patient < low && urgent > low)
    }

    /**
     * The anchor may only ever make a bid cheaper than the budget allows — it
     * is one of three ceilings, never a licence to pay more.
     */
    @Test
    fun theAnchorCannotOutbidTheBudget() {
        val budget = PairMath.maxPairCost(0.03, 0.95)
        val budgeted = PairMath.completionLimit(0.42, budget, false, 0.07, 1.0)
        val anchored = PairMath.anchoredBid(0.70, 3.0, urgent = true)

        // Window low far above what the pair can afford; the budget still wins.
        assertEquals(0.53, minOf(budgeted, anchored), 1e-9)
    }
}

/**
 * The venue's real order floor.
 *
 * It is not only a share count: an order also has to be worth a dollar. At a
 * few cents a share those two say very different things, and sizing by the
 * share count alone is rejected at exactly the prices where a cheap side is
 * worth buying.
 */
class MinOrderTest {

    @Test
    fun theShareCountRulesAtOrdinaryPrices() {
        assertEquals(5.0, Orders.minShares(0.50, 5.0), 1e-9)
        assertEquals(5.0, Orders.minShares(0.20, 5.0), 1e-9)
    }

    @Test
    fun theDollarRulesOnceSharesStopReachingIt() {
        assertEquals(20.0, Orders.minShares(0.05, 5.0), 1e-9)
        assertEquals(10.0, Orders.minShares(0.10, 5.0), 1e-9)
        assertEquals(100.0, Orders.minShares(0.01, 5.0), 1e-9)
    }

    @Test
    fun everyFlooredOrderIsWorthAtLeastADollar() {
        for (price in listOf(0.01, 0.03, 0.05, 0.1, 0.19, 0.2, 0.5, 0.9)) {
            val value = Orders.minShares(price, 5.0) * price
            assertTrue(
                "at $price the floor is only $value",
                value >= Orders.MIN_ORDER_VALUE_USD - 1e-9,
            )
        }
    }

    @Test
    fun aNonsensePriceFallsBackToTheShareCount() {
        assertEquals(5.0, Orders.minShares(0.0, 5.0), 1e-9)
        assertEquals(5.0, Orders.minShares(Double.NaN, 5.0), 1e-9)
    }

    @Test
    fun aLargerVenueMinimumStillWins() {
        assertEquals(25.0, Orders.minShares(0.50, 25.0), 1e-9)
    }
}
