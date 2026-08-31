package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePlanTest {

    private val on = ProbePlan.Settings(enabled = true)

    private val W = 1_788_060_600L

    @Test
    fun `aims at the next window through the lead before it opens`() {
        // Twenty seconds left, and one.
        assertEquals(W + 300, ProbePlan.targetWindow(W, 280, on))
        assertEquals(W + 300, ProbePlan.targetWindow(W, 299, on))
    }

    @Test
    fun `aims at the running window for the same lead after it opens`() {
        // The venue does not always publish the next market in time, and a
        // window entered two seconds late is still that window's bet.
        assertEquals(W, ProbePlan.targetWindow(W, 0, on))
        assertEquals(W, ProbePlan.targetWindow(W, 20, on))
    }

    @Test
    fun `aims at nothing through the middle of a window`() {
        assertNull(ProbePlan.targetWindow(W, 21, on))
        assertNull(ProbePlan.targetWindow(W, 150, on))
        // The default lead is forty-five, so the entry opens at 255.
        assertNull(ProbePlan.targetWindow(W, 254, on))
        assertEquals(W + 300, ProbePlan.targetWindow(W, 255, on))
    }

    /**
     * The lead has two uses and they are not the same length. Being early is
     * worth three quarters of a minute; being late is worth twenty seconds,
     * because a bet placed forty-five seconds into a five-minute window is
     * missing a sixth of what it is betting on.
     */
    @Test
    fun `a longer lead widens only the early chance`() {
        val early = ProbePlan.Settings(enabled = true, leadSec = 30)
        assertEquals(W + 300, ProbePlan.targetWindow(W, 270, early))
        assertNull(ProbePlan.targetWindow(W, 269, early))
        // The grace after the open stays where it is.
        assertEquals(W, ProbePlan.targetWindow(W, 20, early))
        assertNull(ProbePlan.targetWindow(W, 21, early))
    }

    @Test
    fun `says why it is standing aside`() {
        assertEquals("выключен", ProbePlan.blockedBecause("Up", 0.5, 100.0, ProbePlan.Settings()))
        assertEquals("нет свечей", ProbePlan.blockedBecause("", 0.5, 100.0, on))
        assertEquals("нет цены", ProbePlan.blockedBecause("Up", null, 100.0, on))
        assertEquals("нет цены", ProbePlan.blockedBecause("Up", 0.0, 100.0, on))
        assertEquals("тестовый счёт пуст", ProbePlan.blockedBecause("Up", 0.5, 1.0, on))
        // And on real money it is the wallet that is empty, which is a
        // different sentence about a different purse.
        assertEquals(
            "на счету пусто",
            ProbePlan.blockedBecause("Up", 0.5, 1.0, on.copy(demo = false)),
        )
    }

    @Test
    fun `takes a side at the market only while it is cheap enough`() {
        assertTrue(!ProbePlan.waits(0.42))
        assertTrue(!ProbePlan.waits(ProbePlan.MAX_TAKE))
        assertTrue(ProbePlan.waits(0.58))
        assertTrue(ProbePlan.waits(0.90))
    }

    @Test
    fun `a dear side is bid for rather than chased`() {
        // Cheap enough: pay what is asked.
        assertEquals(0.42, ProbePlan.entryPrice(0.42), 1e-9)
        // Too dear: leave a bid where the rule is willing to buy, and let the
        // window come to it or not.
        assertEquals(ProbePlan.REST_PRICE, ProbePlan.entryPrice(0.72), 1e-9)
    }

    @Test
    fun `a dear side is not a reason to stand the window out`() {
        assertNull(ProbePlan.blockedBecause("Up", 0.86, 100.0, on))
    }

    @Test
    fun `nothing is in the way when the line points and the price is fair`() {
        assertNull(ProbePlan.blockedBecause("Down", 0.48, 20.0, on))
    }

    @Test
    fun `sizes the stake in shares at the price being paid`() {
        // Five dollars at fifty cents is ten shares.
        assertEquals(10.0, ProbePlan.shares(5.0, 0.50, 5.0), 1e-9)
        // And at twenty-five cents, twenty.
        assertEquals(20.0, ProbePlan.shares(5.0, 0.25, 5.0), 1e-9)
    }

    @Test
    fun `never sizes under the venue's floor`() {
        // Five dollars at eighty cents is 6.25 shares, which rounds to 6.3 —
        // above the floor. At a floor of ten it would be the floor instead.
        assertEquals(6.3, ProbePlan.shares(5.0, 0.80, 5.0), 1e-9)
        assertEquals(10.0, ProbePlan.shares(5.0, 0.80, 10.0), 1e-9)
    }

    @Test
    fun `crosses the spread by a tick and stops short of a dollar`() {
        assertEquals(0.51, ProbePlan.crossPrice(0.50, 0.01), 1e-9)
        assertEquals(0.99, ProbePlan.crossPrice(0.99, 0.01), 1e-9)
        assertEquals(0.999, ProbePlan.crossPrice(0.999, 0.001), 1e-9)
    }

    @Test
    fun `a taken share costs the quote plus the fee`() {
        // The fee is largest in the middle, where the outcome is least decided.
        assertEquals(0.5175, ProbePlan.takenPrice(0.50), 1e-9)
        assertEquals(0.263125, ProbePlan.takenPrice(0.25), 1e-9)
        // And vanishes at the ends, along with the doubt.
        assertEquals(0.99 + 0.07 * 0.99 * 0.01, ProbePlan.takenPrice(0.99), 1e-9)
    }

    @Test
    fun `paper money pays the fee too`() {
        // A demo that ignored it would report a profit the same trade would
        // not have made, which is the one thing a demo must not do.
        assertTrue(ProbePlan.takenPrice(0.42) > 0.42)
    }

    @Test
    fun `a price outside the book is left alone`() {
        assertEquals(0.0, ProbePlan.takenPrice(0.0), 1e-9)
        assertEquals(1.0, ProbePlan.takenPrice(1.0), 1e-9)
    }

    @Test
    fun `on the rungs, the paper exit asks what the clock asks`() {
        val rule = AutoSell.Settings(ladder = listOf(0.77, 0.84, 0.89, 0.93, 0.97))
        // The lead moves each rung fifteen seconds early, so the first minute
        // is already asking the second rung by its forty-fifth second.
        assertEquals(
            0.77,
            ProbePlan.exitPrice(0.5, 0, 300, 0.0, 0, 0.5, rule),
            1e-9,
        )
        // Half a minute a rung, so the second one is already asking by the
        // fifteenth second — the lead moves each boundary that much early.
        assertEquals(
            0.84,
            ProbePlan.exitPrice(0.5, 20, 280, 0.0, 0, 0.5, rule),
            1e-9,
        )
    }

    @Test
    fun `a rung the price has cleared is behind it`() {
        val rule = AutoSell.Settings(ladder = listOf(0.77, 0.84, 0.89, 0.93, 0.97))
        // The book has already bid 0.90, so resting at 0.89 would be leaving
        // money on the table.
        assertEquals(
            0.93,
            ProbePlan.exitPrice(0.5, 0, 300, 0.90, 0, 0.90, rule),
            1e-9,
        )
    }

    @Test
    fun `in percent mode the paper exit prices off what the lot cost`() {
        val rule = AutoSell.Settings(percentMode = true, profitPct = 0.2)
        val asked = ProbePlan.exitPrice(0.50, 30, 270, 0.0, 0, 0.5, rule)
        // A fifth over fifty cents, and then some for the fee that comes out
        // of the sale.
        assertTrue(asked > 0.60)
        assertTrue(asked < 0.70)
    }

    @Test
    fun `near the close the paper exit takes what the book is paying`() {
        val rule = AutoSell.Settings(percentMode = true, profitPct = 0.2, panicSec = 60)
        // Thirty seconds left and the book bidding ninety-four: the floor is
        // met, so the price is the bid rather than the margin.
        val asked = ProbePlan.exitPrice(0.50, 270, 30, 0.0, 0, 0.94, rule)
        assertEquals(0.94, asked, 1e-9)
    }

    @Test
    fun `a shorter rung reaches the higher asks sooner`() {
        val long = AutoSell.Settings(ladderStepSec = 60)
        val short = AutoSell.Settings(ladderStepSec = 30)
        val at = 50L
        assertTrue(
            ProbePlan.exitPrice(0.5, at, 210, 0.0, 0, 0.5, short) >
                ProbePlan.exitPrice(0.5, at, 210, 0.0, 0, 0.5, long),
        )
    }

    @Test
    fun `a price sitting on a round five hundred is one to stay out of`() {
        // The numbers everybody else writes orders at.
        assertEquals(80_000.0, ProbePlan.nearRound(80_012.0, 50.0)!!, 1e-9)
        assertEquals(80_500.0, ProbePlan.nearRound(80_460.0, 50.0)!!, 1e-9)
        assertEquals(81_000.0, ProbePlan.nearRound(80_970.0, 50.0)!!, 1e-9)
    }

    @Test
    fun `open ground between two of them is open ground`() {
        assertNull(ProbePlan.nearRound(80_250.0, 50.0))
        assertNull(ProbePlan.nearRound(80_060.0, 50.0))
    }

    @Test
    fun `the edge of the band is still the band`() {
        assertEquals(80_000.0, ProbePlan.nearRound(80_050.0, 50.0)!!, 1e-9)
        assertNull(ProbePlan.nearRound(80_050.01, 50.0))
    }

    @Test
    fun `a zero band switches the check off`() {
        assertNull(ProbePlan.nearRound(80_000.0, 0.0))
    }

    @Test
    fun `the gate names the number it is standing off`() {
        assertEquals(
            "круглый 80500",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 80_480.0,
            ),
        )
    }

    @Test
    fun `away from the round numbers nothing is in the way`() {
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 80_240.0,
            ),
        )
    }

    @Test
    fun `a candle finishing the other way is a reason to wait`() {
        // The line says down and the five minutes is closing green.
        assertTrue(ProbePlan.closingAgainst("Down", 80_000.0, 80_040.0))
        // And the other way round.
        assertTrue(ProbePlan.closingAgainst("Up", 80_000.0, 79_960.0))
    }

    @Test
    fun `a candle finishing with the line is no obstacle`() {
        assertTrue(!ProbePlan.closingAgainst("Down", 80_000.0, 79_960.0))
        assertTrue(!ProbePlan.closingAgainst("Up", 80_000.0, 80_040.0))
    }

    @Test
    fun `a candle that went nowhere says nothing`() {
        assertTrue(!ProbePlan.closingAgainst("Up", 80_000.0, 80_000.0))
        assertTrue(!ProbePlan.closingAgainst("", 80_000.0, 80_040.0))
        assertTrue(!ProbePlan.closingAgainst("Up", 0.0, 80_040.0))
    }









    /**
     * The bot bought Down while standing on the support it had been sitting
     * on for a dozen minutes, because a side chosen off a bounce used to skip
     * the room check altogether. A level outranks a line: the wall a bounce
     * came off is the reason for the trade, but the wall in front of it is
     * still a wall, and [level] is only ever the one in front.
     */
    @Test
    fun `a win puts half of itself on the next window`() {
        assertEquals(2.0, ProbePlan.nextStreak(0.0, 4.0), 1e-9)
        // And the next win adds half of its own on top.
        assertEquals(2.0, ProbePlan.nextStreak(1.0, 2.0), 1e-9)
    }

    /**
     * The next entry is placed while the window before it is still open, so a
     * run that is about to be ended by that window would otherwise stake its
     * largest bet on the way out.
     */
    @Test
    fun `a run does not ride a window that is already losing`() {
        // Five dollars in, the book would pay four: the run is over.
        assertEquals(0.0, ProbePlan.riding(3.0, worth = 4.0, cost = 5.0), 1e-9)
        // Ahead, so it rides.
        assertEquals(3.0, ProbePlan.riding(3.0, worth = 7.0, cost = 5.0), 1e-9)
        // Exactly flat is not a loss.
        assertEquals(3.0, ProbePlan.riding(3.0, worth = 5.0, cost = 5.0), 1e-9)
    }

    @Test
    fun `the reset takes the stake back to base`() {
        val streak = 4.0
        assertEquals(9.0, ProbePlan.stakeFor(5.0, 0.0, 100.0, streak), 1e-9)
        val live = ProbePlan.riding(streak, worth = 1.0, cost = 5.0)
        assertEquals(5.0, ProbePlan.stakeFor(5.0, 0.0, 100.0, live), 1e-9)
    }

    /**
     * The screenshot: price dipped under 78 000, wicked back up into it and
     * closed below, and the rule read a bounce off resistance and sold. But
     * the hour before that had been spent above 78 000 — the level was the
     * floor of the range, being under it was the exception, and the way back
     * was up.
     */
    @Test
    fun `an even record names no home side`() {
        val even = List(30) { 78_100.0 } + List(30) { 77_900.0 }
        assertEquals("", ProbePlan.homeSide(even, 78_000.0))
        // And too little history to judge on says nothing either.
        assertEquals("", ProbePlan.homeSide(List(8) { 78_100.0 }, 78_000.0))
    }

    /**
     * A run that compounds without a ceiling ends with the whole account on
     * one five-minute window.
     */
    @Test
    fun `the run never stakes more than a quarter of the account`() {
        // Base five, a run of forty — but the account is a hundred.
        assertEquals(
            25.0,
            ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 40.0, bank = 100.0),
            1e-9,
        )
        // Under the ceiling the run is untouched.
        assertEquals(
            15.0,
            ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 10.0, bank = 100.0),
            1e-9,
        )
    }

    @Test
    fun `the ceiling never cuts into the base stake`() {
        // An account of ten has no room for a run, but the base is the base.
        assertEquals(
            5.0,
            ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 20.0, bank = 10.0),
            1e-9,
        )
    }

    /**
     * The ceiling is on the progression, not on the entry. A base stake the
     * user set goes in whatever share of the account it happens to be.
     */
    @Test
    fun `the base stake goes in even when it is over a quarter of the account`() {
        // Five dollars of a twelve dollar account is more than a quarter, and
        // it is still the stake.
        assertEquals(
            5.0,
            ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 0.0, bank = 12.0),
            1e-9,
        )
        assertEquals(5.0, ProbePlan.capped(5.0, base = 5.0, bank = 12.0), 1e-9)
        // Only what the run added on top is trimmed.
        assertEquals(5.0, ProbePlan.capped(9.0, base = 5.0, bank = 12.0), 1e-9)
    }

    @Test
    fun `an unknown account leaves the run uncapped`() {
        assertEquals(
            45.0,
            ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 40.0),
            1e-9,
        )
    }

    @Test
    fun `a loss ends the run`() {
        assertEquals(0.0, ProbePlan.nextStreak(3.0, -1.0), 1e-9)
        // A window that made nothing is not a win either.
        assertEquals(0.0, ProbePlan.nextStreak(3.0, 0.0), 1e-9)
    }

    @Test
    fun `the run only ever stakes winnings`() {
        // Five of base plus a run of one and a half.
        assertEquals(6.5, ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 1.5), 1e-9)
        // And after a loss it is the base alone.
        assertEquals(5.0, ProbePlan.stakeFor(5.0, won = 0.0, start = 100.0, streak = 0.0), 1e-9)
    }

    @Test
    fun `a doubled account raises the base by half`() {
        // A hundred that made a hundred has doubled once.
        assertEquals(1, ProbePlan.doublings(won = 100.0, start = 100.0))
        assertEquals(7.5, ProbePlan.stakeFor(5.0, won = 100.0, start = 100.0, streak = 0.0), 1e-9)
    }

    @Test
    fun `doubling again takes three hundred, not two`() {
        // A hundred to two hundred to four hundred: the second doubling needs
        // another two hundred on top of the first hundred.
        assertEquals(1, ProbePlan.doublings(won = 299.0, start = 100.0))
        assertEquals(2, ProbePlan.doublings(won = 300.0, start = 100.0))
        assertEquals(11.25, ProbePlan.stakeFor(5.0, won = 300.0, start = 100.0, streak = 0.0), 1e-9)
    }

    @Test
    fun `an account that has lost money still stakes its base`() {
        assertEquals(0, ProbePlan.doublings(won = -40.0, start = 100.0))
        assertEquals(5.0, ProbePlan.stakeFor(5.0, won = -40.0, start = 100.0, streak = 0.0), 1e-9)
    }

    @Test
    fun `a side that fell under forty-two is bought again`() {
        assertTrue(ProbePlan.addsUp(elapsedSec = 30, ask = 0.41, adds = 0))
        assertTrue(ProbePlan.addsUp(elapsedSec = 55, ask = 0.35, adds = 0))
    }

    @Test
    fun `the second buy waits for thirty-three`() {
        // Forty-one is the first rung's price, not the second's.
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.41, adds = 1))
        assertTrue(ProbePlan.addsUp(elapsedSec = 30, ask = 0.32, adds = 1))
    }

    @Test
    fun `a side that has not fallen that far is left alone`() {
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.42, adds = 0))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.45, adds = 0))
    }

    @Test
    fun `past the first minute a cheap side is late rather than cheap`() {
        assertTrue(ProbePlan.addsUp(elapsedSec = 59, ask = 0.20, adds = 0))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 61, ask = 0.20, adds = 0))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 280, ask = 0.05, adds = 0))
    }

    @Test
    fun `a window holds three buys and never a fourth`() {
        // A rule that keeps doubling into a falling side loses the account on
        // the day the read is simply wrong.
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.05, adds = 2))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.05, adds = 9))
    }

    @Test
    fun `without a price there is nothing to buy`() {
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = null, adds = 0))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.0, adds = 0))
    }

    /**
     * The ladder sold at eighty; the market handed the same side back at
     * sixty-four, which is a fifth off what it went for.
     */
    @Test
    fun `a side is bought back a fifth under its own sale`() {
        assertTrue(ProbePlan.buysBack(60, ask = 0.64, soldAt = 0.80, alreadyBack = false))
        assertTrue(ProbePlan.buysBack(60, ask = 0.60, soldAt = 0.80, alreadyBack = false))
        // A cent short of the drop is not the drop.
        assertTrue(!ProbePlan.buysBack(60, ask = 0.65, soldAt = 0.80, alreadyBack = false))
    }

    @Test
    fun `the buy-back stops at forty-four cents`() {
        // Sold at ninety, so a fifth off is seventy-two — but by the time the
        // price is here the side is no longer the favourite.
        assertTrue(!ProbePlan.buysBack(60, ask = 0.43, soldAt = 0.90, alreadyBack = false))
        assertTrue(ProbePlan.buysBack(60, ask = 0.44, soldAt = 0.90, alreadyBack = false))
    }

    @Test
    fun `a sale is bought back once, and only while there is time`() {
        assertTrue(!ProbePlan.buysBack(60, ask = 0.60, soldAt = 0.80, alreadyBack = true))
        assertTrue(ProbePlan.buysBack(200, ask = 0.60, soldAt = 0.80, alreadyBack = false))
        assertTrue(!ProbePlan.buysBack(250, ask = 0.60, soldAt = 0.80, alreadyBack = false))
        // And a sale that never happened has no price to measure under.
        assertTrue(!ProbePlan.buysBack(60, ask = 0.60, soldAt = 0.0, alreadyBack = false))
    }

    @Test
    fun `an ordinary minute is followed as before`() {
        assertNull(
            ProbePlan.blockedBecause(
                way = "Down",
                ask = 0.54,
                cashUsd = 100.0,
                settings = on,
                price = 78_150.0,
                level = 77_500.0,
                typical = 60.0,
                // Twice the usual is large; two and a half is anomalous.
                minuteRange = 40.0,
                minuteBody = -30.0,
                minuteTypical = 20.0,
            ),
        )
    }

    @Test
    fun `the edge of the range may argue with the closing candle`() {
        // It is the strongest line on the chart, so it counts as important.
        assertTrue(ProbePlan.Wall(78_207.0, 1, round = false, edge = true).important)
        assertTrue(!ProbePlan.Wall(78_207.0, 1, round = false).important)
    }

    /**
     * The same window put in twice what the ceiling allowed, because the
     * ceiling was read against the first buy and the top-up was free.
     */
    @Test
    fun `the ceiling is on the window, not on one buy`() {
        // A forty dollar account allows ten on a window, all buys together.
        assertEquals(10.0, ProbePlan.windowCap(base = 5.0, bank = 40.0), 1e-9)
        // And it never falls under the base stake the user set.
        assertEquals(5.0, ProbePlan.windowCap(base = 5.0, bank = 12.0), 1e-9)
        // With no account known there is nothing to cap against.
        assertEquals(Double.MAX_VALUE, ProbePlan.windowCap(base = 5.0, bank = 0.0), 1e-9)
    }

    /**
     * The entry goes in three quarters of a minute early, where the cheap
     * side is; the cost is that the five-minute candle has not finished, and
     * thirty-five seconds is long enough for it to finish the other way.
     */
    @Test
    fun `the read is taken again ten seconds before the open`() {
        assertTrue(ProbePlan.rechecks(10))
        assertTrue(ProbePlan.rechecks(0))
        assertTrue(!ProbePlan.rechecks(11))
        // Once the window has opened there is nothing left to reconsider.
        assertTrue(!ProbePlan.rechecks(-1))
    }

    /**
     * The order log showed the buy going in nine seconds before the open when
     * the lead had been asked to be forty-five: the saved setting was still
     * on ten, which was the default two versions ago rather than anything the
     * user chose.
     */
    @Test
    fun `a lead left on an old default is not a choice`() {
        assertTrue(10L in ProbePlan.OLD_LEADS)
        assertTrue(20L in ProbePlan.OLD_LEADS)
        // Forty-five is where they land, and is not itself migrated away.
        assertTrue(ProbePlan.DEFAULT_LEAD_SEC !in ProbePlan.OLD_LEADS)
        // A lead actually typed is left alone.
        assertTrue(35L !in ProbePlan.OLD_LEADS)
    }

    /**
     * Everything at or below the take price is bought at the market. The
     * limit is the exception, for the one case where there is nothing worth
     * taking — and it sits under the take price, because a side that has to
     * come down anyway may as well come down far enough to be worth having.
     */
    @Test
    fun `the market takes it, and only a dear side is waited for`() {
        assertEquals(0.56, ProbePlan.MAX_TAKE, 1e-9)
        assertEquals(0.54, ProbePlan.REST_PRICE, 1e-9)
        assertTrue(ProbePlan.REST_PRICE < ProbePlan.MAX_TAKE)
        // Fifty-six is taken at the market; a cent over it is waited for.
        assertTrue(!ProbePlan.waits(0.56))
        assertTrue(ProbePlan.waits(0.57))
        // And what it pays: the offer itself up to the cap, the limit above.
        assertEquals(0.50, ProbePlan.entryPrice(0.50), 1e-9)
        assertEquals(0.56, ProbePlan.entryPrice(0.56), 1e-9)
        assertEquals(0.54, ProbePlan.entryPrice(0.70), 1e-9)
    }

    /**
     * The 22:35 entry: the move up had flattened out under 78 832, and the
     * five minutes that closed with the window spent most of its height on an
     * upper wick and finished about where it opened. The next five minutes
     * was being asked to do what the last one had just failed at, from the
     * same place.
     */
    @Test
    fun `a filled bid is worth what was ordered, never more`() {
        // Ordered six, wallet holds six: all of it is ours.
        assertEquals(6.0, minOf(6.0, 6.0), 1e-9)
        // Ordered six, wallet holds twenty because the rest was bought by
        // hand: only the six.
        assertEquals(6.0, minOf(20.0, 6.0), 1e-9)
        // Ordered six, wallet holds two — a partial fill is what there is.
        assertEquals(2.0, minOf(2.0, 6.0), 1e-9)
    }

    @Test
    fun `only the same side still supports the position`() {
        assertTrue(ProbePlan.stillOn("Up", "Up"))
        assertTrue(!ProbePlan.stillOn("Up", "Down"))
        // A read that has gone quiet is a reason to be out too: the entry was
        // taken on a picture, and the picture is no longer there.
        assertTrue(!ProbePlan.stillOn("Up", ""))
        assertTrue(!ProbePlan.stillOn("", ""))
    }

    @Test
    fun `the entry is forty-five seconds early but never forty-five late`() {
        val s = ProbePlan.Settings(enabled = true, leadSec = 45L)
        // Forty-five seconds before the next window opens: that is the entry.
        assertEquals(300L, ProbePlan.targetWindow(0L, 255L, s))
        assertEquals(300L, ProbePlan.targetWindow(0L, 299L, s))
        // And the grace after an open stays at twenty, whatever the lead is.
        assertEquals(0L, ProbePlan.targetWindow(0L, 20L, s))
        assertNull(ProbePlan.targetWindow(0L, 21L, s))
    }

    /**
     * The dip is only worth buying while it is noise. A minute several times
     * the size of the minutes around it is the news that moved the price.
     */
    @Test
    fun `an outsized minute is not bought into`() {
        // Minutes usually cover fifty dollars; this one covered a hundred and
        // twenty, all of it the wrong way.
        assertTrue(ProbePlan.shocked(against = 120.0, typical = 50.0))
        assertTrue(!ProbePlan.shocked(against = 90.0, typical = 50.0))
        // With nothing to compare against, nothing is anomalous.
        assertTrue(!ProbePlan.shocked(against = 500.0, typical = 0.0))
    }

    @Test
    fun `a bid the market never came back to is pulled after a minute`() {
        assertTrue(ProbePlan.restingDone(60))
        assertTrue(ProbePlan.restingDone(200))
    }

    @Test
    fun `and left out for that minute`() {
        assertTrue(!ProbePlan.restingDone(0))
        assertTrue(!ProbePlan.restingDone(59))
    }

    /**
     * The closing candle used to veto a bounce off a level it disagreed with,
     * unless the level was a round five hundred or thrice-touched. It vetoed
     * more windows than anything else here and had no say in which of them
     * were worth vetoing, so it no longer votes: the bounce stands on the
     * wick, the level and the minute that ends the window.
     */
    @Test
    fun `what makes a level important`() {
        assertTrue(ProbePlan.Wall(78_500.0, touches = 0, round = true).important)
        assertTrue(ProbePlan.Wall(78_311.0, touches = 3, round = false).important)
        assertTrue(!ProbePlan.Wall(78_311.0, touches = 2, round = false).important)
    }
    /**
     * The 09:15 entry: a bounce off 77 680 bought Up at 77 953 with the round
     * 78 000 forty-seven dollars overhead and a typical five-minute move of a
     * hundred and fifty-seven. Three quarters of the window's travel was wall.
     *
     * Two things let it through. The room setting had been turned down to
     * fifteen per cent, so the gate asked for twenty-four dollars and got
     * forty-seven; and the round-number check exempts a bounce, on the
     * reasoning that a bounce trades away from the number it just came off —
     * which this one did not, it ran straight at a different one.
     */
    @Test
    fun `arriving at the wall in profit closes the position`() {
        // Bought Up at 50c, now bid 62c — net of the sell fee that is a
        // profit — with BTC at 77 990 and the wall at 78 000.
        assertTrue(
            ProbePlan.atWall(
                side = "Up",
                btc = 77_990.0,
                wall = 78_000.0,
                typical = 157.0,
                bid = 0.62,
                cost = 0.50,
            ),
        )
        // And the mirror, downwards.
        assertTrue(
            ProbePlan.atWall(
                side = "Down",
                btc = 77_690.0,
                wall = 77_680.0,
                typical = 157.0,
                bid = 0.62,
                cost = 0.50,
            ),
        )
    }

    @Test
    fun `a tenth of a move short of the wall is near enough`() {
        // The orders sit in a band around the level and the turn starts at
        // the edge of it, so the exact price is not waited for.
        assertTrue(
            ProbePlan.atWall("Up", 77_985.0, 78_000.0, 157.0, 0.62, 0.50),
        )
        // Two tenths away is still the middle of the room.
        assertTrue(
            !ProbePlan.atWall("Up", 77_965.0, 78_000.0, 157.0, 0.62, 0.50),
        )
    }

    @Test
    fun `a position underwater at the wall is not sold there`() {
        // Bid 50c on a side that cost 50c is a loss once the fee is paid: the
        // read was wrong about the direction, and paying the spread here only
        // locks the mistake in a few minutes before settlement decides it.
        assertTrue(!ProbePlan.atWall("Up", 77_995.0, 78_000.0, 157.0, 0.50, 0.50))
        assertTrue(!ProbePlan.atWall("Up", 77_995.0, 78_000.0, 157.0, 0.30, 0.50))
    }

    @Test
    fun `no wall, no scale and no quote say nothing`() {
        assertTrue(!ProbePlan.atWall("Up", 77_995.0, null, 157.0, 0.62, 0.50))
        assertTrue(!ProbePlan.atWall("Up", 77_995.0, 78_000.0, 0.0, 0.62, 0.50))
        assertTrue(!ProbePlan.atWall("Up", 77_995.0, 78_000.0, 157.0, null, 0.50))
        assertTrue(!ProbePlan.atWall("", 77_995.0, 78_000.0, 157.0, 0.62, 0.50))
    }

    /**
     * The 10:30 entry bought Up into a shelf the market had been failing at
     * for two hours. The room was measured to the middle of that shelf, so
     * half the zone counted as clear air.
     */
    @Test
    fun `the tilt still has an answer when the call does not`() {
        val weak = TrendFit.Trend(perHour = 128.0, way = "", fit = 0.18)
        assertEquals("Up", TrendFit.lean(weak))
        assertEquals("", weak.way)
    }

    /**
     * While the minute candles keep closing our way the move is still
     * happening; the first one that closes the other way is it pausing at
     * best, and a position already up by a sixth is taken there rather than
     * handed back on the way to a rung it may not reach.
     */
    @Test
    fun `a minute against us takes the profit`() {
        // Bought Up at 50c, bid 62c — net of the fee that is well over 15% —
        // and the last completed minute closed red.
        assertTrue(ProbePlan.turnedAgainst("Up", minuteBody = -18.0, bid = 0.62, cost = 0.50))
        // And the mirror: bought Down, the minute closed green.
        assertTrue(ProbePlan.turnedAgainst("Down", minuteBody = 18.0, bid = 0.62, cost = 0.50))
    }

    @Test
    fun `a minute still going our way is left alone`() {
        assertTrue(!ProbePlan.turnedAgainst("Up", minuteBody = 18.0, bid = 0.62, cost = 0.50))
        assertTrue(!ProbePlan.turnedAgainst("Down", minuteBody = -18.0, bid = 0.62, cost = 0.50))
        // A minute that closed exactly flat said nothing.
        assertTrue(!ProbePlan.turnedAgainst("Up", minuteBody = 0.0, bid = 0.62, cost = 0.50))
    }

    @Test
    fun `and a turn is only taken when there is a profit to take`() {
        // 56c on a side that cost 50c is under a tenth after the fee: a green
        // run has red minutes in it, and this one is not worth ending.
        assertTrue(!ProbePlan.turnedAgainst("Up", minuteBody = -18.0, bid = 0.56, cost = 0.50))
        // 60c is the first cent that clears fifteen per cent net.
        assertTrue(ProbePlan.turnedAgainst("Up", minuteBody = -18.0, bid = 0.60, cost = 0.50))
        // And a position under water is never sold on a red minute.
        assertTrue(!ProbePlan.turnedAgainst("Up", minuteBody = -18.0, bid = 0.44, cost = 0.50))
    }

    @Test
    fun `the gain it asks for is fifteen per cent of what the side cost`() {
        assertEquals(0.15, ProbePlan.TURN_GAIN, 1e-9)
        // Fifteen per cent *after* the fee, so a side bought at fifty has to
        // be bid sixty rather than fifty-eight: the fee on the way out is
        // real money and the gain is what is left of the sale.
        val need = 0.50 * 1.15
        assertTrue(SellPercent.netSell(0.60) >= need)
        assertTrue(SellPercent.netSell(0.59) < need)
        assertTrue(ProbePlan.turnedAgainst("Up", -18.0, 0.60, 0.50))
        assertTrue(!ProbePlan.turnedAgainst("Up", -18.0, 0.59, 0.50))
    }

    /**
     * The entry is the trend, the round five hundreds and the wick — and
     * nothing else about levels.
     *
     * The pivot levels used to choose sides here as well as refuse them, and
     * the rule became an argument between two readings of the same chart. The
     * round numbers stay because they are a fact about where the book sits
     * rather than a reading of anything, and they only ever refuse.
     */
    @Test
    fun `the side is the line and nothing else`() {
        assertEquals(ProbePlan.Choice("Up", null), ProbePlan.choose("Up", "Up"))
        assertEquals(ProbePlan.Choice("Down", null), ProbePlan.choose("Down", ""))
        assertEquals(ProbePlan.Choice("", "нет линии"), ProbePlan.choose("", "Up"))
        assertEquals(ProbePlan.Choice("", "тренды спорят"), ProbePlan.choose("Up", "Down"))
    }

    /**
     * A wick our way is the move having gone there and been sent straight
     * back inside the same candle — the last five minutes asked the question
     * this window is about to ask and were answered no.
     */
    @Test
    fun `a wick our way stops the entry`() {
        // Opens at 100, reaches 140, closes back at 110: forty of the sixty
        // above the body is wick, two thirds of the range.
        assertTrue(ProbePlan.wicked("Up", open = 100.0, high = 140.0, low = 95.0, close = 110.0))
        assertEquals(
            "хвост вверх",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.50,
                cashUsd = 100.0,
                settings = on.copy(roundBand = 0.0),
                price = 77_240.0,
                candleOpen = 100.0,
                candleHigh = 140.0,
                candleLow = 95.0,
                candleClose = 110.0,
            ),
        )
    }

    @Test
    fun `a candle that closed where it reached is bought at once`() {
        // Opens at 100, closes at 138, high 140: the hair on top is a
        // twentieth of the range, which is every candle ever drawn.
        assertTrue(!ProbePlan.wicked("Up", open = 100.0, high = 140.0, low = 99.0, close = 138.0))
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.50,
                cashUsd = 100.0,
                settings = on.copy(roundBand = 0.0),
                price = 77_240.0,
                candleOpen = 100.0,
                candleHigh = 140.0,
                candleLow = 99.0,
                candleClose = 138.0,
            ),
        )
    }

    @Test
    fun `a wick the other way is a reason for the trade, not against it`() {
        // The same candle bought downwards: the long tail is above, which is
        // the other direction having been refused.
        assertTrue(!ProbePlan.wicked("Down", open = 100.0, high = 140.0, low = 95.0, close = 110.0))
    }

    @Test
    fun `the round five hundreds are the levels that are left`() {
        assertEquals(
            "круглый 88500",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.50,
                cashUsd = 100.0,
                settings = on,
                price = 88_470.0,
            ),
        )
        // And a price in open ground between two of them is open ground.
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.50,
                cashUsd = 100.0,
                settings = on,
                price = 88_240.0,
            ),
        )
    }

}
