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
}
