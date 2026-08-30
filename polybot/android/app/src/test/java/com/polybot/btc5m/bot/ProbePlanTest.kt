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
        assertTrue(ProbePlan.waits(0.57))
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
        // A typical window travels sixty dollars, so the rule wants twenty-one
        // of room. Ten is not enough: this window arrives at the level with
        // most of itself left, and the direction is nearly spent.
        assertTrue(ProbePlan.tooClose(price = 100_000.0, level = 100_010.0, typical = 60.0))
        // Thirty away, and the window has somewhere to go first.
        assertTrue(!ProbePlan.tooClose(price = 100_000.0, level = 100_030.0, typical = 60.0))
    }

    @Test
    fun `measures the room the same either side of the price`() {
        assertTrue(ProbePlan.tooClose(price = 100_000.0, level = 99_990.0, typical = 60.0))
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
            level = 100_250.0,
            typical = 60.0,
        )
        assertEquals("у разворота 100250", why)
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
    fun `a candle going the line's way leaves the line alone`() {
        assertEquals(
            ProbePlan.Choice("Up", null),
            ProbePlan.choose("Up", candleBody = 40.0, typical = 60.0, intoWall = false),
        )
        assertEquals(
            ProbePlan.Choice("Down", null),
            ProbePlan.choose("Down", candleBody = -40.0, typical = 60.0, intoWall = false),
        )
    }

    @Test
    fun `a candle bigger than usual, the other way, is the turn itself`() {
        // The line says down; the five minutes closed green by more than a
        // candle usually travels. The line is an average over half an hour and
        // will not say so for another twenty minutes.
        val pick = ProbePlan.choose("Down", candleBody = 80.0, typical = 60.0, intoWall = false)
        assertEquals("Up", pick.side)
        assertEquals("разворот", pick.note)
        assertTrue(!pick.byLine)
    }

    @Test
    fun `a candle turning off a level is the bounce, and the bounce is taken`() {
        val pick = ProbePlan.choose("Down", candleBody = 20.0, typical = 60.0, intoWall = true)
        assertEquals("Up", pick.side)
        assertEquals("коррекция от уровня", pick.note)
    }

    @Test
    fun `an ordinary candle against the line in open ground is neither side`() {
        val pick = ProbePlan.choose("Down", candleBody = 20.0, typical = 60.0, intoWall = false)
        assertEquals("", pick.side)
        assertEquals("свеча зелёная", pick.note)
    }

    @Test
    fun `a candle that went nowhere leaves the line to decide`() {
        assertEquals("Down", ProbePlan.choose("Down", 0.0, 60.0, intoWall = false).side)
    }

    @Test
    fun `without a line there is no side at all`() {
        assertEquals("", ProbePlan.choose("", 80.0, 60.0, intoWall = false).side)
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
    fun `the minute candle gets a vote of its own`() {
        // The five minutes is closing green with an upward line and would have
        // passed on its own — but the minute just closed red, and that is the
        // freshest thing on the screen.
        val pick = ProbePlan.choose(
            way = "Up",
            candleBody = 40.0,
            typical = 60.0,
            minuteBody = -8.0,
            minuteTypical = 14.0,
        )
        assertEquals("", pick.side)
        assertEquals("свеча красная", pick.note)
    }

    @Test
    fun `each candle is judged against its own length`() {
        // Twelve dollars is a big minute and a small five minutes. Against a
        // minute's usual travel it is the turn; the same body as a
        // five-minute candle would be noise.
        val minute = ProbePlan.choose(
            way = "Down",
            candleBody = 0.0,
            typical = 60.0,
            minuteBody = 20.0,
            minuteTypical = 14.0,
        )
        assertEquals("Up", minute.side)
        assertEquals("разворот", minute.note)

        val window = ProbePlan.choose(
            way = "Down",
            candleBody = 20.0,
            typical = 60.0,
            minuteBody = 0.0,
            minuteTypical = 14.0,
        )
        assertEquals("", window.side)
    }

    @Test
    fun `the loudest objection is the one that decides`() {
        // Both disagree; the minute is further past its own usual travel, so
        // it is the one that calls the turn.
        val pick = ProbePlan.choose(
            way = "Down",
            candleBody = 30.0,
            typical = 60.0,
            minuteBody = 25.0,
            minuteTypical = 14.0,
        )
        assertEquals("Up", pick.side)
        assertEquals("разворот", pick.note)
    }

    @Test
    fun `both candles with the line leave it alone`() {
        val pick = ProbePlan.choose(
            way = "Up",
            candleBody = 40.0,
            typical = 60.0,
            minuteBody = 6.0,
            minuteTypical = 14.0,
        )
        assertEquals("Up", pick.side)
        assertNull(pick.note)
    }

    @Test
    fun `a position that fell under twenty is abandoned when it comes back`() {
        // It went to fifteen and is bid forty again: forty in hand beats the
        // dollar it will probably never pay.
        assertTrue(ProbePlan.bail(lowWater = 0.15, bid = 0.40))
        assertTrue(ProbePlan.bail(lowWater = 0.05, bid = 0.62))
    }

    @Test
    fun `a position still on the floor is not sold at the floor`() {
        assertTrue(!ProbePlan.bail(lowWater = 0.15, bid = 0.18))
        assertTrue(!ProbePlan.bail(lowWater = 0.15, bid = 0.39))
    }

    @Test
    fun `a position that never fell that far keeps its ladder`() {
        // Down to thirty and back to fifty is an ordinary wobble, and the
        // rungs above are still reachable.
        assertTrue(!ProbePlan.bail(lowWater = 0.30, bid = 0.55))
        assertTrue(!ProbePlan.bail(lowWater = ProbePlan.SINK_PRICE, bid = 0.90))
    }

    @Test
    fun `nothing to say before a price has been seen`() {
        assertTrue(!ProbePlan.bail(lowWater = 0.0, bid = 0.50))
        assertTrue(!ProbePlan.bail(lowWater = 0.10, bid = 0.0))
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
}
