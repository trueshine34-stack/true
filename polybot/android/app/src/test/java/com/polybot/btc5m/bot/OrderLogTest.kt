package com.polybot.btc5m.bot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The order log is what the buy-back hangs on.
 *
 * It has to count a sell's matched volume exactly once, and it has to count
 * sells placed by hand as readily as ones a rule placed — the rule used to
 * track only its own orders, which is why a limit sell put on by hand filled
 * without the buy-back ever hearing about it.
 */
class OrderLogTest {

    @Before
    fun reset() = OrderLog.clear()

    @After
    fun tidy() = OrderLog.clear()

    private fun record(
        action: String,
        price: Double,
        size: Double,
        matched: Double = 0.0,
        auto: Boolean = false,
        orderId: String? = "o${(0..1_000_000).random()}",
    ) = OrderLog.record(
        orderId = orderId,
        asset = "token-a",
        conditionId = "cond-a",
        outcome = "Up",
        action = action,
        price = price,
        size = size,
        matched = matched,
        auto = auto,
        windowStart = 0L,
    )

    @Test
    fun aTradeMarksTheOrderItFilled() {
        val entry = record("SELL", 0.77, 5.0)
        OrderLog.applyTrade("token-a", "SELL", 0.77, 5.0, tick = 0.01)

        assertEquals(5.0, entry.matched, 1e-9)
        assertEquals("filled", entry.status)
    }

    @Test
    fun aPartialTradeLeavesTheOrderWorking() {
        val entry = record("SELL", 0.77, 10.0)
        OrderLog.applyTrade("token-a", "SELL", 0.77, 4.0, tick = 0.01)

        assertEquals(4.0, entry.matched, 1e-9)
        assertEquals("partial", entry.status)
    }

    @Test
    fun aTradeAtAnotherPriceIsNotThisOrder() {
        val entry = record("SELL", 0.77, 5.0)
        OrderLog.applyTrade("token-a", "SELL", 0.93, 5.0, tick = 0.01)

        assertEquals(0.0, entry.matched, 1e-9)
        assertEquals("resting", entry.status)
    }

    @Test
    fun aBuyTradeDoesNotFillASell() {
        val entry = record("SELL", 0.77, 5.0)
        OrderLog.applyTrade("token-a", "BUY", 0.77, 5.0, tick = 0.01)
        assertEquals(0.0, entry.matched, 1e-9)
    }

    @Test
    fun volumeSpillsOntoTheNextOrderAtTheSamePrice() {
        val first = record("SELL", 0.77, 5.0)
        val second = record("SELL", 0.77, 5.0)
        OrderLog.applyTrade("token-a", "SELL", 0.77, 8.0, tick = 0.01)

        assertEquals(5.0, first.matched, 1e-9)
        assertEquals(3.0, second.matched, 1e-9)
    }

    @Test
    fun aTradeWithNoOrderOfOursIsHarmless() {
        // Sold from the Polymarket app: nothing here to mark, and the buy-back
        // works off the trade itself rather than off this.
        OrderLog.applyTrade("token-z", "SELL", 0.77, 5.0, tick = 0.01)
    }

    @Test
    fun anUnresolvableOrderIsLeftAloneRatherThanCalledCancelled() {
        val entry = record("SELL", 0.97, 5.0, orderId = "gone")
        // The venue answers nothing, which is what a fill and a cancel both
        // look like. Guessing "cancelled" is what killed the buy-back.
        OrderLog.reconcile(emptyList()) { null }

        assertEquals("resting", entry.status)
    }

    @Test
    fun aVenueAnswerIsStillBelieved() {
        val entry = record("SELL", 0.97, 5.0, orderId = "gone")
        OrderLog.reconcile(emptyList()) {
            ClobApi.OpenOrder(
                id = "gone",
                status = "matched",
                market = "cond-a",
                assetId = "token-a",
                side = "SELL",
                price = 0.97,
                originalSize = 5.0,
                sizeMatched = 5.0,
                outcome = "Up",
            )
        }
        assertEquals("filled", entry.status)
    }

    @Test
    fun aRestingSellKeepsTheRuleAwake() {
        val window = System.currentTimeMillis() / 1000 - (System.currentTimeMillis() / 1000) % WINDOW_SECONDS
        record("SELL", 0.97, 5.0, matched = 0.0)

        assertTrue(OrderLog.hasWorkingSells(window))
    }

    @Test
    fun aFilledSellDoesNotKeepItAwake() {
        val window = System.currentTimeMillis() / 1000 - (System.currentTimeMillis() / 1000) % WINDOW_SECONDS
        val entry = record("SELL", 0.97, 5.0, matched = 0.0)
        entry.status = "filled"

        assertFalse(OrderLog.hasWorkingSells(window))
    }

    @Test
    fun aRestingBuyKeepsTheRuleAwake() {
        val window = System.currentTimeMillis() / 1000 -
            (System.currentTimeMillis() / 1000) % WINDOW_SECONDS
        record("BUY", 0.42, 5.0, matched = 0.0)

        // The position it will become still has to be covered by a sell, and
        // nothing else in the loop knows the fill is coming.
        assertTrue(OrderLog.hasWorkingBuys(window))
        assertTrue(OrderLog.hasWorkingBuy("token-a"))
        assertFalse(OrderLog.hasWorkingBuy("token-b"))
    }

    @Test
    fun aFilledBuyStopsKeepingItAwake() {
        val window = System.currentTimeMillis() / 1000 -
            (System.currentTimeMillis() / 1000) % WINDOW_SECONDS
        val entry = record("BUY", 0.42, 5.0, matched = 5.0)
        entry.status = "filled"

        assertFalse(OrderLog.hasWorkingBuys(window))
        assertFalse(OrderLog.hasWorkingBuy("token-a"))
    }

    @Test
    fun ordersFromClosedWindowsAreLetGo() {
        val entry = record("SELL", 0.97, 5.0, matched = 0.0)
        // Two windows on, its market has closed and nothing more will happen.
        assertFalse(OrderLog.hasWorkingSells(entry.windowStart + WINDOW_SECONDS * 2))
    }

    private fun buy(size: Double, asset: String = "token-a") = OrderLog.record(
        orderId = "b$size$asset",
        asset = asset,
        conditionId = "cond-a",
        outcome = "Up",
        action = "BUY",
        price = 0.42,
        size = size,
        matched = size,
        auto = false,
        windowStart = 0L,
    )

    @Test
    fun threeLotsOfFiveGiveAClipOfFive() {
        repeat(3) { buy(5.0) }
        assertEquals(5.0, OrderLog.buyLotFor("token-a")!!, 1e-9)
    }

    @Test
    fun theSmallestClipWins() {
        buy(15.0)
        buy(5.0)
        // Mixed sizes: the smallest is the one that keeps the buy-back gradual.
        assertEquals(5.0, OrderLog.buyLotFor("token-a")!!, 1e-9)
    }

    @Test
    fun clipsAreKeptPerOutcome() {
        buy(5.0, "token-a")
        buy(20.0, "token-b")
        assertEquals(5.0, OrderLog.buyLotFor("token-a")!!, 1e-9)
        assertEquals(20.0, OrderLog.buyLotFor("token-b")!!, 1e-9)
    }

    @Test
    fun aSellIsNotABuyClip() {
        OrderLog.record(
            orderId = "s1",
            asset = "token-a",
            conditionId = "cond-a",
            outcome = "Up",
            action = "SELL",
            price = 0.77,
            size = 5.0,
            matched = 5.0,
            auto = false,
            windowStart = 0L,
        )
        assertNull(OrderLog.buyLotFor("token-a"))
    }

    @Test
    fun nothingBoughtMeansNoClipToCopy() {
        assertNull(OrderLog.buyLotFor("never-seen"))
    }
}

/**
 * An order belongs to its market's window, not to the clock's.
 *
 * The desk can buy into the next window before it opens. Stamping such an order
 * with the current window filed it where the desk would never look for it —
 * neither while pointed at the next window, nor after that window began.
 */
class OrderWindowTest {

    @Before
    fun reset() = OrderLog.clear()

    @After
    fun tidy() = OrderLog.clear()

    private val now = System.currentTimeMillis() / 1000
    private val current = now - now % WINDOW_SECONDS
    private val next = current + WINDOW_SECONDS

    private fun place(windowStart: Long) = OrderLog.record(
        orderId = "o$windowStart",
        asset = "token-a",
        conditionId = "cond-a",
        outcome = "Up",
        action = "SELL",
        price = 0.77,
        size = 5.0,
        matched = 0.0,
        auto = false,
        windowStart = windowStart,
    )

    @Test
    fun anOrderForTheNextWindowIsFiledUnderIt() {
        place(next)
        assertTrue(OrderLog.forWindow(next).isNotEmpty())
        assertTrue(OrderLog.forWindow(current).isEmpty())
    }

    @Test
    fun itIsStillThereOnceThatWindowBegins() {
        place(next)
        // The clock moves on; the order does not move with it.
        assertEquals(1, OrderLog.forWindow(next).size)
    }

    @Test
    fun withoutAMarketItFallsBackToTheClock() {
        place(0L)
        assertEquals(1, OrderLog.forWindow(current).size)
    }

    @Test
    fun aPreOpenSellStillKeepsTheRuleAwake() {
        place(next)
        assertTrue(OrderLog.hasWorkingSells(current))
    }
}
