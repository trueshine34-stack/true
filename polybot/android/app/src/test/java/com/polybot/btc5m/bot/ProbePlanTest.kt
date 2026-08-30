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
        assertNull(ProbePlan.targetWindow(W, 279, on))
    }

    @Test
    fun `a longer lead widens both chances`() {
        val early = ProbePlan.Settings(enabled = true, leadSec = 30)
        assertEquals(W + 300, ProbePlan.targetWindow(W, 270, early))
        assertEquals(W, ProbePlan.targetWindow(W, 30, early))
        assertNull(ProbePlan.targetWindow(W, 31, early))
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
        assertEquals("у разворота 100270", why)
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









    @Test
    fun `a side taken against the line is not stopped by the level it came off`() {
        // The wall is the reason for the trade; it cannot also be the reason
        // against it.
        assertNull(
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
        // Following the line into it is still refused.
        assertEquals(
            "круглый 80000",
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









    @Test
    fun `a win puts a quarter of itself on the next window`() {
        assertEquals(1.0, ProbePlan.nextStreak(0.0, 4.0), 1e-9)
        // And the next win adds a quarter of its own on top.
        assertEquals(1.5, ProbePlan.nextStreak(1.0, 2.0), 1e-9)
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
    fun `a side that fell under a third is bought again`() {
        assertTrue(ProbePlan.addsUp(elapsedSec = 30, ask = 0.30, alreadyAdded = false))
        assertTrue(ProbePlan.addsUp(elapsedSec = 120, ask = 0.12, alreadyAdded = false))
    }

    @Test
    fun `a side that has not fallen that far is left alone`() {
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.34, alreadyAdded = false))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.45, alreadyAdded = false))
    }

    @Test
    fun `past two minutes a cheap side is late rather than cheap`() {
        assertTrue(!ProbePlan.addsUp(elapsedSec = 121, ask = 0.20, alreadyAdded = false))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 280, ask = 0.05, alreadyAdded = false))
    }

    @Test
    fun `the same money goes in once and no more`() {
        // A rule that keeps doubling into a falling side loses the account on
        // the day the read is simply wrong.
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.20, alreadyAdded = true))
    }

    @Test
    fun `without a price there is nothing to buy`() {
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = null, alreadyAdded = false))
        assertTrue(!ProbePlan.addsUp(elapsedSec = 30, ask = 0.0, alreadyAdded = false))
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
        above = 78_311.0,
        below = 78_145.0,
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
            above = 78_311.0,
            below = 78_145.0,
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
            above = 78_311.0,
            below = 78_145.0,
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
            above = 78_600.0,
            below = 77_900.0,
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
    fun `a winner standing still at a level is taken there`() {
        // Two dollars in a minute is not a move, the book is paying eighty,
        // and price is on the level it was going to.
        assertTrue(
            ProbePlan.stalling(
                progress = 2.0,
                heldSec = 90,
                bid = 0.80,
                atLevel = true,
            ),
        )
    }

    @Test
    fun `a move still moving is left to move`() {
        assertTrue(
            !ProbePlan.stalling(
                progress = 12.0,
                heldSec = 90,
                bid = 0.80,
                atLevel = true,
            ),
        )
    }

    @Test
    fun `a stall away from any level is just a quiet minute`() {
        assertTrue(
            !ProbePlan.stalling(
                progress = 1.0,
                heldSec = 90,
                bid = 0.80,
                atLevel = false,
            ),
        )
    }

    @Test
    fun `a position that is not plainly ahead is not sold on a stall`() {
        // Seventy-two cents is not the profit this rule is for.
        assertTrue(
            !ProbePlan.stalling(
                progress = 1.0,
                heldSec = 90,
                bid = 0.72,
                atLevel = true,
            ),
        )
        assertTrue(
            ProbePlan.stalling(
                progress = 1.0,
                heldSec = 90,
                bid = ProbePlan.STALL_PRICE,
                atLevel = true,
            ),
        )
    }

    @Test
    fun `a position has to have stood there for a minute`() {
        assertTrue(
            !ProbePlan.stalling(
                progress = 1.0,
                heldSec = 40,
                bid = 0.90,
                atLevel = true,
            ),
        )
    }

    @Test
    fun `going the wrong way counts as not going the right way`() {
        assertTrue(
            ProbePlan.stalling(
                progress = -20.0,
                heldSec = 90,
                bid = 0.85,
                atLevel = true,
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
}
