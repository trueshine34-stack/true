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

    /** The window record() stamps an entry with when none is given. */
    private fun nowWindow(): Long {
        val nowSec = System.currentTimeMillis() / 1000
        return nowSec - (nowSec % WINDOW_SECONDS)
    }

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
    fun aSellFilledAboveItsAskIsStillThisOrder() {
        // The venue never pays a seller less than they asked; anything above is
        // an improvement, and the order it improved on is this one.
        val entry = record("SELL", 0.77, 5.0)
        OrderLog.applyTrade("token-a", "SELL", 0.93, 5.0, tick = 0.01)

        assertEquals(5.0, entry.matched, 1e-9)
        assertEquals(0.93, entry.realPrice, 1e-9)
    }

    @Test
    fun aSellFilledBelowItsAskIsNotThisOrder() {
        val entry = record("SELL", 0.77, 5.0)
        OrderLog.applyTrade("token-a", "SELL", 0.60, 5.0, tick = 0.01)

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
    fun aTradeWithNoOrderToMatchComesBackAsLeftover() {
        record("SELL", 0.72, 5.0, matched = 0.0)

        // Same outcome, but nothing here was placed at this price.
        val left = OrderLog.applyTrade("token-a", "SELL", 0.40, 5.0, tick = 0.01)

        assertEquals(5.0, left, 1e-9)
    }

    @Test
    fun aTradeBooksAgainstTheOrderItBelongsTo() {
        record("SELL", 0.72, 5.0, matched = 0.0)

        val left = OrderLog.applyTrade("token-a", "SELL", 0.72, 5.0, tick = 0.01)

        assertEquals(0.0, left, 1e-9)
        assertEquals("filled", OrderLog.all().first().status)
    }

    @Test
    fun aFillWithNoOrderIsFiledAsWhatItIs() {
        // Sold in the Polymarket app, or placed before this process started:
        // it happened, so the panel and the profit have to know about it.
        val entry = OrderLog.recordFill(
            asset = "token-a",
            conditionId = "cond-a",
            outcome = "Down",
            action = "SELL",
            price = 0.30,
            size = 5.0,
            windowStart = 0L,
            at = System.currentTimeMillis(),
        )

        assertEquals("filled", entry.status)
        assertEquals(5.0, entry.matched, 1e-9)
        assertFalse(entry.auto)
        assertTrue(OrderLog.all().contains(entry))
    }

    @Test
    fun aFilledSaleFromOutsideCoversTheLotItSold() {
        record("BUY", 0.23, 5.0, matched = 5.0)
        assertTrue(OrderLog.hasUncovered(nowWindow()))

        OrderLog.recordFill(
            asset = "token-a",
            conditionId = "cond-a",
            outcome = "Down",
            action = "SELL",
            price = 0.30,
            size = 5.0,
            windowStart = nowWindow(),
            at = System.currentTimeMillis(),
        )

        assertFalse(OrderLog.hasUncovered(nowWindow()))
    }

    @Test
    fun eachPurchaseKeepsItsOwnPrice() {
        record("BUY", 0.32, 5.0, matched = 5.0)
        record("BUY", 0.52, 5.0, matched = 5.0)
        record("BUY", 0.49, 5.0, matched = 5.0)

        val lots = OrderLog.uncoveredLots("token-a")

        // Not one position at 44⅓¢ — three purchases, oldest first.
        assertEquals(3, lots.size)
        assertEquals(0.32, lots[0].price, 1e-9)
        assertEquals(0.52, lots[1].price, 1e-9)
        assertEquals(0.49, lots[2].price, 1e-9)
    }

    @Test
    fun aSellTakesTheOldestPurchaseOut() {
        record("BUY", 0.32, 5.0, matched = 5.0)
        record("BUY", 0.52, 5.0, matched = 5.0)
        record("SELL", 0.41, 5.0, matched = 5.0)

        val lots = OrderLog.uncoveredLots("token-a")

        assertEquals(1, lots.size)
        assertEquals(0.52, lots[0].price, 1e-9)
    }

    @Test
    fun anOfferAlreadyOnTheBookCoversItsLot() {
        record("BUY", 0.32, 5.0, matched = 5.0)
        record("BUY", 0.52, 5.0, matched = 5.0)
        // Resting, nothing matched: those shares have an exit arranged.
        record("SELL", 0.41, 5.0, matched = 0.0)

        val lots = OrderLog.uncoveredLots("token-a")

        assertEquals(1, lots.size)
        assertEquals(0.52, lots[0].price, 1e-9)
    }

    @Test
    fun aPulledOfferLeavesItsLotUncoveredAgain() {
        record("BUY", 0.32, 5.0, matched = 5.0)
        val sell = record("SELL", 0.41, 5.0, matched = 0.0)
        sell.status = "cancelled"

        assertEquals(1, OrderLog.uncoveredLots("token-a").size)
    }

    @Test
    fun aSellBiggerThanOneLotEatsIntoTheNext() {
        record("BUY", 0.32, 5.0, matched = 5.0)
        record("BUY", 0.52, 5.0, matched = 5.0)
        record("SELL", 0.60, 8.0, matched = 8.0)

        val lots = OrderLog.uncoveredLots("token-a")

        assertEquals(1, lots.size)
        assertEquals(2.0, lots[0].shares, 1e-9)
        assertEquals(0.52, lots[0].price, 1e-9)
    }

    @Test
    fun anotherOutcomeIsNotThisOnesBusiness() {
        record("BUY", 0.32, 5.0, matched = 5.0)

        assertTrue(OrderLog.uncoveredLots("token-b").isEmpty())
    }

    @Test
    fun anOfferStillOnTheBookKeepsItsPositionInTheSweep() {
        record("BUY", 0.54, 5.0, matched = 5.0)
        record("SELL", 0.67, 5.0, matched = 0.0)

        // Covered, so nothing is uncovered — and that is exactly why the sweep
        // stopped looking, and why a floor that came into force afterwards
        // never reached the 67¢ offer sitting under it.
        assertFalse(OrderLog.hasUncovered(nowWindow()))
        assertTrue(OrderLog.workingAssets("SELL", nowWindow()).contains("token-a"))
    }

    @Test
    fun anOfferThatIsGoneIsNoLongerTheRulesBusiness() {
        val sell = record("SELL", 0.67, 5.0, matched = 5.0)
        sell.status = "filled"

        assertTrue(OrderLog.workingAssets("SELL", nowWindow()).isEmpty())
    }

    @Test
    fun aBoughtLotWithNoSellIsUncovered() {
        record("BUY", 0.43, 5.0, matched = 5.0)

        assertEquals(5.0, OrderLog.uncovered(nowWindow())["token-a"]!!, 1e-9)
        assertTrue(OrderLog.hasUncovered(nowWindow()))
    }

    @Test
    fun aRestingSellCountsAsTheExit() {
        record("BUY", 0.43, 5.0, matched = 5.0)
        record("SELL", 0.72, 5.0, matched = 0.0)

        // The exit is arranged even though no money has moved yet.
        assertFalse(OrderLog.hasUncovered(nowWindow()))
    }

    @Test
    fun aFilledSellCoversItToo() {
        record("BUY", 0.43, 5.0, matched = 5.0)
        val sell = record("SELL", 0.72, 5.0, matched = 5.0)
        sell.status = "filled"

        assertFalse(OrderLog.hasUncovered(nowWindow()))
    }

    @Test
    fun aPulledSellLeavesTheLotUncoveredAgain() {
        record("BUY", 0.43, 5.0, matched = 5.0)
        val sell = record("SELL", 0.72, 5.0, matched = 0.0)
        sell.status = "cancelled"

        assertEquals(5.0, OrderLog.uncovered(nowWindow())["token-a"]!!, 1e-9)
    }

    @Test
    fun onlyThePartOfAPositionWithNoSellCounts() {
        record("BUY", 0.43, 10.0, matched = 10.0)
        record("SELL", 0.72, 4.0, matched = 0.0)

        assertEquals(6.0, OrderLog.uncovered(nowWindow())["token-a"]!!, 1e-9)
    }

    @Test
    fun aBuyThatNeverFilledIsNotAPosition() {
        record("BUY", 0.43, 5.0, matched = 0.0)

        assertFalse(OrderLog.hasUncovered(nowWindow()))
    }

    @Test
    fun uncoveredLotsFromClosedWindowsAreLetGo() {
        val entry = record("BUY", 0.43, 5.0, matched = 5.0)

        // Two windows on, the market has settled and there is nothing to sell.
        assertFalse(OrderLog.hasUncovered(entry.windowStart + WINDOW_SECONDS * 2))
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

    /**
     * A marketable limit at 81c that sweeps offers at 78 and 79 costs neither
     * of those and not 81 either. The exit is priced off what the position
     * cost, so the lot has to carry the fill and not the ask.
     */
    @Test
    fun aLotIsWorthWhatItActuallyCost() {
        OrderLog.record(
            orderId = "a",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "BUY",
            price = 0.81,
            size = 5.0,
            matched = 5.0,
            fillPrice = 0.785,
            auto = false,
            windowStart = 1_000,
        )

        val lot = OrderLog.uncoveredLots("up").single()
        assertEquals(5.0, lot.shares, 1e-9)
        assertEquals(0.785, lot.price, 1e-9)
    }

    /** With nothing traded there is nothing better than the price asked for. */
    @Test
    fun anUnfilledOrderKeepsItsAskingPrice() {
        OrderLog.record(
            orderId = "a",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "BUY",
            price = 0.4,
            size = 5.0,
            matched = 5.0,
            auto = false,
            windowStart = 1_000,
        )

        assertEquals(0.4, OrderLog.uncoveredLots("up").single().price, 1e-9)
    }

    /**
     * A resting order that fills later fills at the trade's price, and the
     * entry's average is re-weighted rather than left at what was asked for.
     */
    @Test
    fun aLaterFillReweightsTheAverage() {
        OrderLog.record(
            orderId = "a",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "BUY",
            price = 0.50,
            size = 10.0,
            matched = 5.0,
            fillPrice = 0.50,
            auto = false,
            windowStart = 1_000,
        )

        OrderLog.applyTrade("up", "BUY", price = 0.40, size = 5.0, tick = 0.11)

        // Five at fifty and five at forty is ten at forty-five.
        assertEquals(0.45, OrderLog.uncoveredLots("up").single().price, 1e-9)
    }

    /**
     * Everything the rules send is marked auto, so what is left is the person
     * — and a price they chose is not the ladder's to move.
     */
    @Test
    fun aHandSetPriceIsToldApartFromTheRulesOwn() {
        OrderLog.record(
            orderId = "mine",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "SELL",
            price = 0.90,
            size = 5.0,
            matched = 0.0,
            auto = false,
            windowStart = 1_000,
        )
        OrderLog.record(
            orderId = "rule",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "SELL",
            price = 0.84,
            size = 5.0,
            matched = 0.0,
            auto = true,
            windowStart = 1_000,
        )

        assertTrue(OrderLog.byHand("mine"))
        assertFalse(OrderLog.byHand("rule"))
        assertFalse(OrderLog.byHand("never heard of it"))
    }

    /**
     * A fill is never worse than the price the order asked for. Matching
     * "within a tick either way" threw away every improved fill: an order for
     * 85c that traded at 87c found no order to belong to, kept the price it
     * had asked for, and the round's result was wrong by the improvement.
     */
    @Test
    fun anImprovedFillStillBelongsToItsOrder() {
        OrderLog.record(
            orderId = "s",
            asset = "down",
            conditionId = "c",
            outcome = "Down",
            action = "SELL",
            price = 0.85,
            size = 23.0,
            matched = 0.0,
            auto = true,
            windowStart = 1_000,
        )

        assertEquals(0.0, OrderLog.applyTrade("down", "SELL", 0.87, 23.0, 0.01), 1e-9)

        val entry = OrderLog.all().single()
        assertEquals(23.0, entry.matched, 1e-9)
        assertEquals(0.87, entry.realPrice, 1e-9)
    }

    /** And a buy pays at most its limit, so anything under it is its own. */
    @Test
    fun aBuyThatPaidLessKeepsThePriceItPaid() {
        OrderLog.record(
            orderId = "b",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "BUY",
            price = 0.81,
            size = 5.0,
            matched = 0.0,
            auto = false,
            windowStart = 1_000,
        )

        OrderLog.applyTrade("up", "BUY", 0.78, 5.0, 0.01)
        assertEquals(0.78, OrderLog.all().single().realPrice, 1e-9)
    }

    /** A fill worse than the ask cannot have come from this order. */
    @Test
    fun aWorsePriceIsNotThisOrdersFill() {
        OrderLog.record(
            orderId = "b",
            asset = "up",
            conditionId = "c",
            outcome = "Up",
            action = "BUY",
            price = 0.40,
            size = 5.0,
            matched = 0.0,
            auto = false,
            windowStart = 1_000,
        )

        // Nothing to take it, so it is left over for the caller to file.
        assertEquals(5.0, OrderLog.applyTrade("up", "BUY", 0.55, 5.0, 0.01), 1e-9)
        assertEquals(0.40, OrderLog.all().single().realPrice, 1e-9)
    }

    /**
     * The listing says how much filled and is polled every few seconds; the
     * trade feed says at what price and is slower. When the listing gets there
     * first there must still be room for the trade to price the shares — or
     * the order keeps the price it asked for and a second, phantom fill is
     * filed alongside it.
     */
    @Test
    fun aTradeStillPricesSharesTheListingAlreadyCounted() {
        val entry = OrderLog.record(
            orderId = "s",
            asset = "down",
            conditionId = "c",
            outcome = "Down",
            action = "SELL",
            price = 0.85,
            size = 23.0,
            matched = 0.0,
            auto = true,
            windowStart = 1_000,
        )

        // The open-orders listing gets there first.
        OrderLog.reconcile(
            listOf(
                ClobApi.OpenOrder(
                    id = "s",
                    status = "MATCHED",
                    market = "c",
                    assetId = "down",
                    side = "SELL",
                    price = 0.85,
                    originalSize = 23.0,
                    sizeMatched = 23.0,
                    outcome = "Down",
                ),
            ),
        ) { null }
        assertEquals(23.0, entry.matched, 1e-9)
        assertEquals(0.85, entry.realPrice, 1e-9)

        // And the trade, when it arrives, is what says the price.
        assertEquals(0.0, OrderLog.applyTrade("down", "SELL", 0.87, 23.0, 0.01), 1e-9)
        assertEquals(0.87, entry.realPrice, 1e-9)
        assertEquals(1, OrderLog.all().size)
    }

    /** Several orders of a side: the venue fills the most aggressive first. */
    @Test
    fun aFillGoesToTheOrderThatWouldHaveMadeIt() {
        for ((id, price) in listOf("dear" to 0.53, "cheap" to 0.46)) {
            OrderLog.record(
                orderId = id,
                asset = "up",
                conditionId = "c",
                outcome = "Up",
                action = "BUY",
                price = price,
                size = 12.0,
                matched = 0.0,
                auto = false,
                windowStart = 1_000,
            )
        }

        OrderLog.applyTrade("up", "BUY", 0.51, 12.0, 0.01)

        val dear = OrderLog.all().single { it.orderId == "dear" }
        val cheap = OrderLog.all().single { it.orderId == "cheap" }
        assertEquals(12.0, dear.matched, 1e-9)
        assertEquals(0.51, dear.realPrice, 1e-9)
        assertEquals(0.0, cheap.matched, 1e-9)
    }
}
