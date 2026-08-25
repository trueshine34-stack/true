package com.polybot.btc5m.bot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Scoring a five-minute event.
 *
 * The arithmetic has two traps. Fees are charged on both sides and in opposite
 * directions — added to what a buy costs, taken out of what a sell pays — so a
 * round that looks flat on price is a loss. And an order that rested and was
 * pulled moved no money: only matched volume counts.
 */
class EventStatsTest {

    private val window = 1_800_000_000L

    @Before
    fun reset() = OrderLog.clear()

    @After
    fun tidy() = OrderLog.clear()

    private fun order(
        action: String,
        price: Double,
        size: Double,
        matched: Double,
        outcome: String = "Up",
        at: Long = window,
    ) = OrderLog.record(
        orderId = "o${(0..1_000_000).random()}",
        asset = if (outcome == "Up") "token-up" else "token-down",
        conditionId = "cond",
        outcome = outcome,
        action = action,
        price = price,
        size = size,
        matched = matched,
        auto = false,
        windowStart = at,
    )

    /** Before the window has closed, so nothing is settled and no side has won. */
    private fun scoreOpen() = EventStats.recent(nowSec = window + 10).first()

    @Test
    fun aRoundTripCountsBothFees() {
        order("BUY", 0.40, 10.0, matched = 10.0)
        order("SELL", 0.50, 10.0, matched = 10.0)

        val event = scoreOpen()
        // 4.00 paid plus fee, 5.00 taken less fee: the dollar of price movement
        // is not a dollar of profit.
        assertTrue(event.pnl < 1.0)
        assertEquals(4.0 + 0.07 * 0.4 * 0.6 * 10, event.spent, 1e-9)
        assertEquals(5.0 - 0.07 * 0.5 * 0.5 * 10, event.got, 1e-9)
    }

    @Test
    fun anOrderThatNeverMatchedMovedNoMoney() {
        order("BUY", 0.40, 10.0, matched = 10.0)
        order("BUY", 0.20, 50.0, matched = 0.0)

        val event = scoreOpen()
        assertEquals(1, event.trades)
        assertEquals(4.0 + 0.07 * 0.4 * 0.6 * 10, event.spent, 1e-9)
    }

    @Test
    fun aPartialFillCountsOnlyWhatFilled() {
        order("BUY", 0.40, 20.0, matched = 5.0)

        val event = scoreOpen()
        assertEquals(2.0 + 0.07 * 0.4 * 0.6 * 5, event.spent, 1e-9)
        assertEquals(5.0, event.held, 1e-9)
    }

    @Test
    fun sharesStillHeldAreOpenUntilTheWindowCloses() {
        order("BUY", 0.40, 10.0, matched = 10.0)

        val open = scoreOpen()
        assertEquals(0.0, open.settlement, 1e-9)
        assertTrue("nothing sold yet, so the round is down what it paid", open.pnl < 0)
        assertEquals(10.0, open.held, 1e-9)
    }

    @Test
    fun eachWindowIsScoredOnItsOwn() {
        order("BUY", 0.40, 10.0, matched = 10.0, at = window)
        order("BUY", 0.60, 10.0, matched = 10.0, at = window - WINDOW_SECONDS)

        val events = EventStats.recent(nowSec = window + 10)
        assertEquals(2, events.size)
        // Newest first.
        assertEquals(window, events[0].windowStart)
        assertEquals(window - WINDOW_SECONDS, events[1].windowStart)
    }

    @Test
    fun theSessionIsTheSumOfItsWindows() {
        order("BUY", 0.40, 10.0, matched = 10.0, at = window)
        order("BUY", 0.30, 10.0, matched = 10.0, at = window - WINDOW_SECONDS)

        val events = EventStats.recent(nowSec = window + 10)
        assertEquals(
            events.sumOf { it.pnl },
            EventStats.sessionPnl(nowSec = window + 10),
            1e-9,
        )
    }
}
