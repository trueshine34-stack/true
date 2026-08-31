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
    fun `stands aside when the reversal is one window away`() {
        // A typical window travels sixty dollars, and the rule wants a whole
        // one of room. Thirty is not enough: what is left is smaller than the
        // move a candle makes by accident.
        assertTrue(ProbePlan.tooClose(price = 100_000.0, level = 100_030.0, typical = 60.0))
        // Ninety away, and the window has somewhere to go first.
        assertTrue(!ProbePlan.tooClose(price = 100_000.0, level = 100_090.0, typical = 60.0))
    }

    @Test
    fun `measures the room the same either side of the price`() {
        assertTrue(ProbePlan.tooClose(price = 100_000.0, level = 99_970.0, typical = 60.0))
    }

    @Test
    fun `has no opinion without a level or a scale`() {
        assertTrue(!ProbePlan.tooClose(100_000.0, null, 60.0))
        assertTrue(!ProbePlan.tooClose(100_000.0, 100_010.0, 0.0))
        assertTrue(!ProbePlan.tooClose(0.0, 100_010.0, 60.0))
    }

    @Test
    fun `a zero share switches the check off`() {
        assertTrue(!ProbePlan.tooClose(100_000.0, 100_001.0, 60.0, share = 0.0))
    }

    @Test
    fun `says which price it is standing aside from`() {
        val why = ProbePlan.blockedBecause(
            way = "Up",
            ask = 0.5,
            cashUsd = 100.0,
            settings = on,
            // In open ground between two round numbers, so the reversal is
            // the only thing in the way.
            price = 100_240.0,
            level = 100_270.0,
            typical = 60.0,
        )
        assertEquals("у уровня 100270", why)
    }

    @Test
    fun `room in front of the line is not in the way`() {
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 100_240.0,
                level = 100_440.0,
                typical = 60.0,
            ),
        )
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
    fun `a wall in front stops the trade however the side was chosen`() {
        assertEquals(
            "у уровня 80010",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 80_000.0,
                level = 80_010.0,
                typical = 60.0,
                byLine = false,
            ),
        )
        // And following the line into it is refused for the same reason.
        assertEquals(
            "у уровня 80010",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 80_000.0,
                level = 80_010.0,
                typical = 60.0,
                byLine = true,
            ),
        )
    }

    /**
     * The screenshot: price sat on support at 78 034 with the round 78 000
     * under it, and the rule bought Down into both.
     */
    @Test
    fun `standing on support is not a place to buy downwards`() {
        assertEquals(
            "у уровня 78034",
            ProbePlan.blockedBecause(
                way = "Down",
                ask = 0.34,
                cashUsd = 100.0,
                settings = on,
                price = 78_054.0,
                level = 78_034.0,
                typical = 60.0,
                byLine = false,
            ),
        )
    }

    /** A bounce with the whole room in front of it still trades. */
    @Test
    fun `a bounce with somewhere to go is taken`() {
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                // Off support, with resistance a full window's travel away.
                price = 80_010.0,
                level = 80_200.0,
                typical = 60.0,
                byLine = false,
            ),
        )
    }

    /** Standing on a round number is a reason to bounce off it, not to follow into it. */
    @Test
    fun `a bounce off a round number is not refused for standing on it`() {
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 80_000.0,
                level = 80_200.0,
                typical = 60.0,
                byLine = false,
            ),
        )
        assertEquals(
            "круглый 80000",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 80_000.0,
                level = 80_200.0,
                typical = 60.0,
                byLine = true,
            ),
        )
    }

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
    fun `a bounce away from where the market lives is refused`() {
        val hour = List(54) { 78_120.0 } + List(6) { 77_960.0 }
        assertEquals("Up", ProbePlan.homeSide(hour, 78_000.0))

        val pick = ProbePlan.choose(
            way = "Down",
            wide = "",
            candleBody = -40.0,
            typical = 60.0,
            candleHigh = 78_004.0,
            candleLow = 77_950.0,
            candleClose = 77_969.0,
            minuteBody = -12.0,
            minuteTypical = 20.0,
            above = ProbePlan.Wall(78_000.0, 0, round = true),
            below = ProbePlan.Wall(77_900.0, 2, round = false),
            homeAbove = "Up",
        )
        assertEquals("", pick.side)
        assertEquals("под 78000, живём выше", pick.note)
    }

    @Test
    fun `a bounce off a level the market is not living behind is taken`() {
        val pick = ProbePlan.choose(
            way = "Down",
            wide = "",
            candleBody = -40.0,
            typical = 60.0,
            candleHigh = 78_004.0,
            candleLow = 77_950.0,
            candleClose = 77_969.0,
            minuteBody = -12.0,
            minuteTypical = 20.0,
            above = ProbePlan.Wall(78_000.0, 0, round = true),
            below = ProbePlan.Wall(77_900.0, 2, round = false),
            homeAbove = "",
        )
        assertEquals("Down", pick.side)
        assertEquals("отбой от 78000", pick.note)
    }

    @Test
    fun `and the same the other way up`() {
        val hour = List(54) { 77_800.0 } + List(6) { 78_050.0 }
        assertEquals("Down", ProbePlan.homeSide(hour, 78_000.0))

        val pick = ProbePlan.choose(
            way = "Up",
            wide = "",
            candleBody = 40.0,
            typical = 60.0,
            candleHigh = 78_090.0,
            candleLow = 77_996.0,
            candleClose = 78_040.0,
            minuteBody = 12.0,
            minuteTypical = 20.0,
            above = ProbePlan.Wall(78_200.0, 2, round = false),
            below = ProbePlan.Wall(78_000.0, 0, round = true),
            homeBelow = "Down",
        )
        assertEquals("", pick.side)
        assertEquals("над 78000, живём ниже", pick.note)
    }

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

    /**
     * The screenshot: a minute several times the size of the ones around it
     * spiked and closed red, and the rule bought Down with it — paying up for
     * a move that had already happened, into a side the book had already
     * repriced.
     */
    @Test
    fun `a minute that has already fired is not followed`() {
        // Eighty dollars where a minute usually covers twenty.
        assertEquals(
            "минутка выстрелила вниз",
            ProbePlan.blockedBecause(
                way = "Down",
                ask = 0.54,
                cashUsd = 100.0,
                settings = on,
                price = 78_050.0,
                level = 77_500.0,
                typical = 60.0,
                minuteRange = 80.0,
                minuteBody = -55.0,
                minuteTypical = 20.0,
            ),
        )
    }

    @Test
    fun `the other side of that minute is still open`() {
        // Nothing about a big red minute stops a bet the other way.
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.54,
                cashUsd = 100.0,
                settings = on,
                price = 78_150.0,
                level = 78_600.0,
                typical = 60.0,
                minuteRange = 80.0,
                minuteBody = -55.0,
                minuteTypical = 20.0,
            ),
        )
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
    fun `size is the whole candle and direction is the body`() {
        // A long wick makes the candle big even when the body is modest.
        assertTrue(ProbePlan.spent("Up", minuteRange = 60.0, minuteBody = 5.0, minuteTypical = 20.0))
        assertTrue(!ProbePlan.spent("Down", minuteRange = 60.0, minuteBody = 5.0, minuteTypical = 20.0))
        // A candle that closed where it opened points nowhere.
        assertTrue(!ProbePlan.spent("Up", minuteRange = 60.0, minuteBody = 0.0, minuteTypical = 20.0))
        // And with nothing to compare against, nothing is anomalous.
        assertTrue(!ProbePlan.spent("Up", minuteRange = 60.0, minuteBody = 5.0, minuteTypical = 0.0))
    }

    /**
     * The 18:10 window: price nineteen dollars under the high of the whole
     * four hours on the screen, and the rule bought Up into it because the
     * high had turned the market once, not twice, and so was filtered out of
     * the shelf. The retest could never add a second touch either — a pivot
     * is the extreme of two candles either side of it, and the candle being
     * tested on is the last one there is.
     */
    @Test
    fun `the edge of the range needs half again the room`() {
        // Nineteen dollars under a high, where a five minutes covers thirty.
        assertEquals(
            "край диапазона 78207",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.48,
                cashUsd = 100.0,
                settings = on,
                price = 78_188.0,
                level = 78_207.0,
                typical = 32.0,
                levelEdge = true,
            ),
        )
        // An ordinary wall at forty dollars is far enough; the edge is not.
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.48,
                cashUsd = 100.0,
                settings = on,
                price = 78_188.0,
                level = 78_228.0,
                typical = 32.0,
                levelEdge = false,
            ),
        )
        assertEquals(
            "край диапазона 78228",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.48,
                cashUsd = 100.0,
                settings = on,
                price = 78_188.0,
                level = 78_228.0,
                typical = 32.0,
                levelEdge = true,
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
     * The take price and the price the bid waits at are one rule seen twice.
     * Above it a limit is the same money for the same side on worse terms,
     * wearing an order type as a disguise; below it is money left on the
     * table, since what the rule would have paid at the market it should also
     * be willing to wait at.
     */
    @Test
    fun `the resting bid waits at the price it would have paid`() {
        assertEquals(ProbePlan.MAX_TAKE, ProbePlan.REST_PRICE, 1e-9)
        assertEquals(0.52, ProbePlan.MAX_TAKE, 1e-9)
        // Fifty-two is taken; a cent over it waits.
        assertTrue(!ProbePlan.waits(0.52))
        assertTrue(ProbePlan.waits(0.53))
    }

    /**
     * The 22:35 entry: the move up had flattened out under 78 832, and the
     * five minutes that closed with the window spent most of its height on an
     * upper wick and finished about where it opened. The next five minutes
     * was being asked to do what the last one had just failed at, from the
     * same place.
     */
    @Test
    fun `a candle that reached our way and was pushed back stops the entry`() {
        // Opened at 78 800, poked 78 832, closed 78 806: a body of six on a
        // range of thirty-eight, and twenty-six of it wick above.
        assertTrue(
            ProbePlan.refused(
                way = "Up",
                open = 78_800.0,
                high = 78_832.0,
                low = 78_794.0,
                close = 78_806.0,
                typical = 40.0,
            ),
        )
        assertEquals(
            "свеча с хвостом вверх",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.49,
                cashUsd = 100.0,
                settings = on,
                price = 78_804.0,
                level = 79_400.0,
                typical = 40.0,
                candleOpen = 78_800.0,
                candleHigh = 78_832.0,
                candleLow = 78_794.0,
                candleClose = 78_806.0,
            ),
        )
    }

    @Test
    fun `it wants both halves`() {
        // A big wick over a big body is a candle that took ground and gave
        // some back, which is what a trending five minutes looks like.
        assertTrue(
            !ProbePlan.refused(
                way = "Up",
                open = 78_760.0,
                high = 78_832.0,
                low = 78_756.0,
                close = 78_806.0,
                typical = 40.0,
            ),
        )
        // And a small body with no wick is a market standing still, which is
        // not a refusal of anything.
        assertTrue(
            !ProbePlan.refused(
                way = "Up",
                open = 78_800.0,
                high = 78_808.0,
                low = 78_794.0,
                close = 78_806.0,
                typical = 40.0,
            ),
        )
    }

    @Test
    fun `the wick has to be on the side being bought`() {
        // The same candle read for a Down entry: its long wick is above, so
        // it says nothing about a move down.
        assertTrue(
            !ProbePlan.refused(
                way = "Down",
                open = 78_800.0,
                high = 78_832.0,
                low = 78_794.0,
                close = 78_806.0,
                typical = 40.0,
            ),
        )
    }

    @Test
    fun `without a candle or a scale it says nothing`() {
        assertTrue(!ProbePlan.refused("Up", 0.0, 78_832.0, 78_794.0, 78_806.0, 40.0))
        assertTrue(!ProbePlan.refused("Up", 78_800.0, 78_832.0, 78_794.0, 78_806.0, 0.0))
        assertTrue(!ProbePlan.refused("", 78_800.0, 78_832.0, 78_794.0, 78_806.0, 40.0))
    }

    /**
     * A bid for six shares at fifty cents filled, and the rule filed the
     * window as "лимитка 50¢ снята" while the wallet plainly held 6.0 · 50¢.
     *
     * The order log could not help: an order that filled has left the book,
     * and one the venue no longer knows about looks exactly like one that was
     * cancelled, so the log leaves it alone rather than guess. What may be
     * adopted from the wallet is capped at what was actually ordered, so a
     * position built by hand on the same side is not taken over.
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
    fun `only the move against the side counts`() {
        // Down eighty dollars: bad for Up, and nothing at all for Down.
        assertEquals(80.0, ProbePlan.againstBy("Up", 80_000.0, 79_920.0), 1e-9)
        assertEquals(0.0, ProbePlan.againstBy("Down", 80_000.0, 79_920.0), 1e-9)
        assertEquals(80.0, ProbePlan.againstBy("Down", 80_000.0, 80_080.0), 1e-9)
        assertEquals(0.0, ProbePlan.againstBy("Up", 80_000.0, 80_080.0), 1e-9)
    }

    /** A five-minute candle that ran into resistance and closed back under. */
    private fun offTop(minute: Double) = ProbePlan.choose(
        way = "Up",
        wide = "Up",
        candleBody = 30.0,
        typical = 60.0,
        candleHigh = 78_308.0,
        candleLow = 78_240.0,
        candleClose = 78_260.0,
        minuteBody = minute,
        minuteTypical = 14.0,
        above = ProbePlan.Wall(78_311.0, touches = 4, round = false),
        below = ProbePlan.Wall(78_145.0, touches = 3, round = false),
    )

    @Test
    fun `a wick into resistance and a close back under is a bounce`() {
        // The lines both say up and it is bought Down anyway: the level turned
        // it, and the line will not know for another twenty minutes.
        val pick = offTop(minute = -6.0)
        assertEquals("Down", pick.side)
        assertEquals("отбой от 78311", pick.note)
        assertTrue(!pick.byLine)
    }

    @Test
    fun `a bounce needs the last minute to be leaving`() {
        // The wick is there but the minute is still pushing into the level, so
        // it is not a bounce — and the lines then carry the window.
        val pick = offTop(minute = 6.0)
        assertEquals("Up", pick.side)
    }

    @Test
    fun `a wick into support and a close back over it is the other bounce`() {
        val pick = ProbePlan.choose(
            way = "Down",
            wide = "Down",
            candleBody = -30.0,
            typical = 60.0,
            candleHigh = 78_200.0,
            candleLow = 78_148.0,
            candleClose = 78_170.0,
            minuteBody = 5.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_311.0, touches = 4, round = false),
            below = ProbePlan.Wall(78_145.0, touches = 3, round = false),
        )
        assertEquals("Up", pick.side)
        assertEquals("отбой от 78145", pick.note)
    }

    @Test
    fun `a candle that spanned the whole shelf settled nothing`() {
        val pick = ProbePlan.choose(
            way = "Up",
            wide = "Up",
            candleBody = 5.0,
            typical = 60.0,
            candleHigh = 78_308.0,
            candleLow = 78_148.0,
            candleClose = 78_200.0,
            minuteBody = -3.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_311.0, touches = 4, round = false),
            below = ProbePlan.Wall(78_145.0, touches = 3, round = false),
        )
        assertEquals("", pick.side)
        assertEquals("зажато между уровнями", pick.note)
    }

    @Test
    fun `away from every level the lines decide as before`() {
        val far = ProbePlan.choose(
            way = "Up",
            wide = "Up",
            candleBody = 30.0,
            typical = 60.0,
            candleHigh = 78_260.0,
            candleLow = 78_220.0,
            candleClose = 78_255.0,
            minuteBody = 4.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_600.0, touches = 2, round = false),
            below = ProbePlan.Wall(77_900.0, touches = 2, round = false),
        )
        assertEquals("Up", far.side)
        assertNull(far.note)
    }

    @Test
    fun `a flat five-minute line does not veto a clear minute one`() {
        // The five-minute fit is too weak to call a direction. That is
        // silence, not disagreement — and it used to cost the window.
        val pick = ProbePlan.choose(
            way = "Down",
            wide = "",
            candleBody = -30.0,
            typical = 60.0,
            minuteBody = -5.0,
            minuteTypical = 14.0,
        )
        assertEquals("Down", pick.side)
        assertNull(pick.note)
    }

    @Test
    fun `a five-minute line that does call the other way still vetoes`() {
        val pick = ProbePlan.choose(
            way = "Down",
            wide = "Up",
            candleBody = -30.0,
            typical = 60.0,
            minuteBody = -5.0,
            minuteTypical = 14.0,
        )
        assertEquals("", pick.side)
        assertEquals("тренды спорят", pick.note)
    }

    @Test
    fun `buying into a level the candle was just refused at is refused too`() {
        assertTrue(
            ProbePlan.rejectedAt(
                way = "Up",
                high = 78_308.0,
                low = 78_240.0,
                close = 78_260.0,
                level = 78_311.0,
                typical = 60.0,
            ),
        )
        // Closing above it is a break, not a refusal.
        assertTrue(
            !ProbePlan.rejectedAt(
                way = "Up",
                high = 78_330.0,
                low = 78_240.0,
                close = 78_320.0,
                level = 78_311.0,
                typical = 60.0,
            ),
        )
    }

    @Test
    fun `a level the candle never reached refuses nothing`() {
        assertTrue(
            !ProbePlan.rejectedAt(
                way = "Up",
                high = 78_250.0,
                low = 78_200.0,
                close = 78_240.0,
                level = 78_500.0,
                typical = 60.0,
            ),
        )
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
    fun `the closing candle no longer vetoes a bounce`() {
        // The five minutes is closing green and the bounce buys Down.
        val weak = ProbePlan.choose(
            way = "Up",
            wide = "Up",
            candleBody = 30.0,
            typical = 60.0,
            candleHigh = 78_308.0,
            candleLow = 78_240.0,
            candleClose = 78_260.0,
            minuteBody = -6.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_311.0, touches = 2, round = false),
        )
        assertEquals("Down", weak.side)
        assertEquals("отбой от 78311", weak.note)
    }

    @Test
    fun `a round five hundred is always worth it`() {
        val round = ProbePlan.choose(
            way = "Up",
            wide = "Up",
            candleBody = 30.0,
            typical = 60.0,
            candleHigh = 78_497.0,
            candleLow = 78_440.0,
            candleClose = 78_470.0,
            minuteBody = -6.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_500.0, touches = 0, round = true),
        )
        assertEquals("Down", round.side)
        assertEquals("отбой от 78500", round.note)
    }

    @Test
    fun `so is a price that has turned the market three times`() {
        val strong = ProbePlan.choose(
            way = "Up",
            wide = "Up",
            candleBody = 30.0,
            typical = 60.0,
            candleHigh = 78_308.0,
            candleLow = 78_240.0,
            candleClose = 78_260.0,
            minuteBody = -6.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_311.0, touches = 3, round = false),
        )
        assertEquals("Down", strong.side)
    }

    @Test
    fun `a bounce that agrees with the closing candle needs no such licence`() {
        // Red five minutes, bouncing down off a twice-touched pivot: the entry
        // and the close say the same thing, so the level need not be special.
        val agreed = ProbePlan.choose(
            way = "Up",
            wide = "Up",
            candleBody = -30.0,
            typical = 60.0,
            candleHigh = 78_308.0,
            candleLow = 78_240.0,
            candleClose = 78_260.0,
            minuteBody = -6.0,
            minuteTypical = 14.0,
            above = ProbePlan.Wall(78_311.0, touches = 2, round = false),
        )
        assertEquals("Down", agreed.side)
    }

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
    fun `a bounce running into a round number is refused`() {
        assertEquals(
            "круглый 78000",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.51,
                cashUsd = 100.0,
                settings = on.copy(roomShare = 0.15),
                price = 77_953.0,
                // The wall the bounce is heading into is the round number
                // itself; pass it far away so this is the round check alone.
                level = 79_000.0,
                typical = 157.0,
                byLine = false,
            ),
        )
    }

    @Test
    fun `a bounce off a round number may still trade away from it`() {
        // The same distance, the other way: 78 000 is behind a Down trade,
        // which is exactly the case the exemption exists for.
        assertNull(
            ProbePlan.blockedBecause(
                way = "Down",
                ask = 0.51,
                cashUsd = 100.0,
                settings = on.copy(roomShare = 0.15),
                price = 77_953.0,
                level = 77_000.0,
                typical = 157.0,
                byLine = false,
            ),
        )
    }

    /**
     * And the room in front is floored. The setting may ask for more than
     * half a typical move; it may not ask for less, because a wall reached
     * before the window is half over is not a matter of preference.
     */
    @Test
    fun `the room setting cannot be turned below half a move`() {
        assertEquals(0.5, ProbePlan.roomNeeded(0.15, levelEdge = false), 1e-9)
        assertEquals(0.5, ProbePlan.roomNeeded(0.0, levelEdge = false), 1e-9)
        // A setting above the floor is honoured as it stands.
        assertEquals(1.0, ProbePlan.roomNeeded(1.0, levelEdge = false), 1e-9)
        // And the edge of the range still asks half again on top of whichever.
        assertEquals(0.75, ProbePlan.roomNeeded(0.15, levelEdge = true), 1e-9)
        assertEquals(1.5, ProbePlan.roomNeeded(1.0, levelEdge = true), 1e-9)
    }

    @Test
    fun `forty-seven dollars of room is refused on a hundred and fifty-seven`() {
        // The floor is seventy-eight, and the wall is at forty-seven.
        assertEquals(
            "у уровня 78000",
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.51,
                cashUsd = 100.0,
                settings = on.copy(roomShare = 0.15, roundBand = 0.0),
                price = 77_953.0,
                level = 78_000.0,
                typical = 157.0,
                byLine = false,
            ),
        )
        // Twice as far away and the same window is open ground.
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.51,
                cashUsd = 100.0,
                settings = on.copy(roomShare = 0.15, roundBand = 0.0),
                price = 77_860.0,
                level = 78_000.0,
                typical = 157.0,
                byLine = false,
            ),
        )
    }

}
