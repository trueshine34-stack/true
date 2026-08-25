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
    fun aHandPlacedSellCountsJustLikeARuleOne() {
        record("SELL", 0.93, 5.0, matched = 5.0, auto = false)
        record("SELL", 0.84, 5.0, matched = 5.0, auto = true)

        val fills = OrderLog.takeSellFills()
        assertEquals(2, fills.size)
        assertEquals(10.0, fills.sumOf { it.matched }, 1e-9)
    }

    @Test
    fun matchedVolumeIsOnlyEverCountedOnce() {
        record("SELL", 0.93, 5.0, matched = 5.0)

        assertEquals(5.0, OrderLog.takeSellFills().sumOf { it.matched }, 1e-9)
        // A second sweep must not register the same sale again, or one sell
        // would queue a buy-back on every pass.
        assertTrue(OrderLog.takeSellFills().isEmpty())
    }

    @Test
    fun aPartialFillCountsTheIncrementAndThenTheRest() {
        val entry = record("SELL", 0.84, 10.0, matched = 4.0)
        assertEquals(4.0, OrderLog.takeSellFills().single().matched, 1e-9)

        entry.matched = 10.0
        assertEquals(6.0, OrderLog.takeSellFills().single().matched, 1e-9)
        assertTrue(OrderLog.takeSellFills().isEmpty())
    }

    @Test
    fun buysAreNotSales() {
        record("BUY", 0.42, 5.0, matched = 5.0)
        assertTrue(OrderLog.takeSellFills().isEmpty())
    }

    @Test
    fun anUnfilledSellYieldsNothing() {
        record("SELL", 0.97, 5.0, matched = 0.0)
        assertTrue(OrderLog.takeSellFills().isEmpty())
    }

    @Test
    fun theFillCarriesThePriceTheBuyBackIsMeasuredFrom() {
        record("SELL", 0.93, 5.0, matched = 5.0)
        val fill = OrderLog.takeSellFills().single()

        assertEquals(0.93, fill.price, 1e-9)
        assertEquals("token-a", fill.asset)
        assertEquals("cond-a", fill.conditionId)
        // 20% below where it sold.
        assertEquals(0.744, fill.price * 0.8, 1e-9)
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
    fun ordersFromClosedWindowsAreLetGo() {
        val entry = record("SELL", 0.97, 5.0, matched = 0.0)
        // Two windows on, its market has closed and nothing more will happen.
        assertFalse(OrderLog.hasWorkingSells(entry.windowStart + WINDOW_SECONDS * 2))
    }

    @Test
    fun aVanishedOrderIsAskedAboutRatherThanAssumedFilled() {
        val entry = record("SELL", 0.97, 5.0, matched = 0.0, orderId = "gone")

        // Not in the open listing, and the venue says nothing matched: that is
        // a cancel, and calling it a fill would queue a buy-back for a sale
        // that never happened.
        OrderLog.reconcile(emptyList()) { null }

        assertEquals("cancelled", entry.status)
        assertTrue(OrderLog.takeSellFills().isEmpty())
    }

    @Test
    fun aVanishedOrderThatDidMatchIsAFill() {
        val entry = record("SELL", 0.97, 5.0, matched = 0.0, orderId = "gone")
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
        assertEquals(5.0, OrderLog.takeSellFills().single().matched, 1e-9)
    }
}

/**
 * The clip a buy-back is made in.
 *
 * A position built as three lots of five should be bought back five at a time:
 * taking the whole fifteen at the first price that clears the trigger hands
 * back the rest of the dip.
 */
class BuyLotTest {

    @Before
    fun reset() = OrderLog.clear()

    @After
    fun tidy() = OrderLog.clear()

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
