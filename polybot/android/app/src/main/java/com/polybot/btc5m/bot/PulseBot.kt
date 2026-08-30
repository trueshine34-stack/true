package com.polybot.btc5m.bot

import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One clip at a time, over and over, on the side four things agree about.
 *
 * Buy, sell, buy again: the trade is always five shares, the profit is always
 * the same percentage, and everything the bot has to be right about is in the
 * entry. `PulsePlan` holds that rule and is tested on its own; this reads the
 * numbers it needs, sends the orders, and keeps the books.
 *
 * Where the numbers come from:
 *  - the lead, from the price series the market settles against — the window's
 *    own open against the live sixty-second TWAP off the socket;
 *  - momentum and volume, from the one-minute candles the app already streams;
 *  - the lean, from the local copy of Binance's order book;
 *  - the odds, from the venue itself.
 *
 * The profit offer rests from the moment the lot fills, so a round usually
 * closes without this loop doing anything. What the loop is for is the two
 * cases an offer cannot handle: the lead turning against the position, and the
 * window ending with it still ahead, where settlement pays a whole dollar and
 * charges no fee.
 */
class PulseBot(
    private val engine: BotEngine,
    private val store: PulseStore,
    /** The desk's own sell rule as it is set, which paper exits follow. */
    private val exit: () -> AutoSell.Settings,
    private val onStateChanged: () -> Unit,
) {

    data class Lot(
        val asset: String,
        val conditionId: String,
        val outcome: String,
        val shares: Double,
        val price: Double,
        val boughtAt: Long,
        val windowStart: Long,
        var sellOrderId: String? = null,
        var sellPrice: Double = 0.0,
        /** When that offer went out, so a later listing can be believed. */
        var sellPlacedAt: Long = 0L,
        var sold: Double = 0.0,
        var proceeds: Double = 0.0,
        var note: String? = null,
        /** Paper: nothing about this lot reached the venue. */
        val demo: Boolean = false,
        /** Best bid seen while holding, and the rung it has reached. */
        var highWater: Double = 0.0,
        var rung: Int = 0,
    ) {
        val cost: Double get() = shares * price
        val open: Double get() = (shares - sold).coerceAtLeast(0.0)
    }

    data class Totals(
        val rounds: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val spent: Double = 0.0,
        val got: Double = 0.0,
        val settled: Double = 0.0,
    ) {
        val pnl: Double get() = got + settled - spent
    }

    @Volatile
    var settings: PulsePlan.Settings = store.loadSettings()
        private set

    @Volatile
    var totals: Totals = store.loadTotals()
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastFault: String? = null
        private set

    @Volatile
    var lot: Lot? = null
        private set

    /** The last read, so the screen can show what the rule is looking at. */
    @Volatile
    var read: PulsePlan.Read? = null
        private set

    @Volatile
    var note: String? = null
        private set

    val cash: Double
        get() = settings.bankUsd + totals.got + totals.settled - totals.spent -
            (lot?.cost ?: 0.0) + (lot?.proceeds ?: 0.0)

    /** Shares this bot holds, so the desk's own rule leaves them alone. */
    fun heldShares(asset: String): Double =
        lot?.takeIf { it.asset == asset }?.open ?: 0.0

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var openStamp = 0L
    private var openPrice = 0.0

    private companion object {
        const val TICK_MS = 2_000L
    }

    fun update(next: PulsePlan.Settings) {
        settings = next
        store.saveSettings(next)
        when {
            next.enabled && !running -> start()
            !next.enabled && running -> stop()
            else -> onStateChanged()
        }
    }

    fun resetBank() {
        totals = Totals()
        store.saveTotals(totals)
        onStateChanged()
    }

    fun start() {
        if (running) return
        running = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            var backoffMs = 0L
            while (isActive && running) {
                try {
                    tick()
                    backoffMs = 0L
                } catch (e: Exception) {
                    lastFault = e.message ?: "сбой пульса"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }
                delay(if (backoffMs > 0L) backoffMs else TICK_MS)
            }
        }
        engine.log(
            "info",
            "Пульс включён: по " + String.format("%.0f", settings.shares) +
                " долей, вход при перевесе от $" + String.format("%.0f", settings.minEdge) +
                ", выход +" + Math.round(settings.takePct * 100) + "%",
        )
        onStateChanged()
    }

    fun stop() {
        if (!running) return
        running = false
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        engine.log("info", "Пульс выключен")
        onStateChanged()
    }

    private fun tick() {
        val session = engine.session()
        // On paper there is nothing to sign, so an unconnected wallet is not a
        // reason to stop: the whole point of watching a rule on paper is that
        // it can be watched before there is any money to watch it with.
        if (session == null && !settings.demo) {
            lastFault = "кошелёк не подключён"
            return
        }
        lastFault = null

        val nowSec = Clock.nowSec()
        val windowStart = nowSec - SellLadder.elapsedInWindow(nowSec)
        val elapsed = nowSec - windowStart

        val market = engine.currentMarket()
        if (market == null || market.windowStart != windowStart) {
            note = "нет рынка"
            onStateChanged()
            return
        }

        // The free half of the read first. Everything except the odds is
        // already in memory, and there is no reason to ask the venue for a
        // price the rest of the rule has no use for.
        var current = read(windowStart, elapsed)
        read = current

        lot?.let { open ->
            // A lot from a window that has closed is settled, not managed.
            if (open.windowStart != windowStart) closeRound(open)
            else work(open, current, market)
        }

        if (lot != null) {
            note = "в позиции"
            onStateChanged()
            return
        }

        val early = PulsePlan.blockedBecause(current, settings, holding = false)
        if (early != null && early != "нет цены") {
            note = early
            onStateChanged()
            return
        }

        // Every local gate is open: now the prices are worth a request.
        current = current.copy(
            upAsk = quietAsk(market.up.tokenId),
            downAsk = quietAsk(market.down.tokenId),
        )
        read = current

        val blocked = PulsePlan.blockedBecause(current, settings, holding = false)
        note = blocked
        if (blocked == null) {
            val side = PulsePlan.leader(current.lead, settings.minEdge)
            val ask = PulsePlan.askFor(side, current)
            if (side != null && ask != null) buy(market, side, ask, windowStart)
        }

        onStateChanged()
    }

    /**
     * Everything the rule looks at that costs nothing to look at.
     *
     * The two prices are left null and filled in only once the rest of the
     * rule has agreed — a bot that ticks every two seconds and asks the venue
     * for two prices each time would spend the whole day asking about windows
     * it was never going to trade.
     */
    private fun read(windowStart: Long, elapsed: Long): PulsePlan.Read {
        val depth = BinanceBook.depth()
        val bid = depth?.bids?.sum() ?: 0.0
        val ask = depth?.asks?.sum() ?: 0.0
        val lean = if (bid + ask > 0.0) bid / (bid + ask) else 0.5

        return PulsePlan.Read(
            elapsedSec = elapsed,
            lead = lead(windowStart),
            momentum = BinanceCandles.oneMinute.momentum(),
            volume = BinanceCandles.oneMinute.volumeRatio(),
            lean = lean,
            upAsk = null,
            downAsk = null,
            ceiling = BuyCap.ceiling(elapsed),
            cashUsd = cash,
        )
    }

    /**
     * How far this window has moved from its own open, in dollars.
     *
     * The open is fetched once per window and kept: it is the number the market
     * resolves against and it cannot change. The live end comes off the socket,
     * which carries the same sixty-second TWAP once a second.
     */
    private fun lead(windowStart: Long): Double {
        if (openStamp != windowStart || openPrice <= 0.0) {
            val first = try {
                PolyPriceApi.window(windowStart).firstOrNull()?.value
            } catch (e: Exception) {
                null
            } ?: engine.feed.twap60Between(windowStart * 1000, (windowStart + 5) * 1000)
                .firstOrNull()?.value
            if (first != null && first > 0.0) {
                openPrice = first
                openStamp = windowStart
            }
        }
        if (openPrice <= 0.0) return 0.0
        val now = engine.feed.twap60?.value
            ?: engine.feed.twap?.value
            ?: return 0.0
        return now - openPrice
    }

    /** A price the venue will not answer for is a reason to wait, not to fail. */
    private fun quietAsk(tokenId: String): Double? = try {
        ClobApi.bestAsk(tokenId)
    } catch (e: Exception) {
        null
    }

    private fun buy(market: Market, side: String, ask: Double, windowStart: Long) {
        val token = if (side == "Up") market.up.tokenId else market.down.tokenId
        val size = maxOf(settings.shares, Orders.minShares(ask, market.minimumOrderSize))
        // Crossing by a tick can step over the window's ceiling by that same
        // tick, and the venue would refuse the order rather than shave it.
        val limit = minOf(
            PulsePlan.crossPrice(ask, market.tickSize),
            BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart)),
        )

        if (settings.demo) {
            paperBuy(market, token, side, ask, size, windowStart)
            return
        }

        val result = try {
            engine.placeManualOrder(
                tokenId = token,
                conditionId = market.conditionId,
                side = "BUY",
                price = limit,
                size = size,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            note = e.message ?: "ошибка сети"
            return
        }

        if (!result.success) {
            note = result.error ?: "отказ CLOB"
            engine.log("error", "Пульс: $note")
            return
        }

        val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
        if (fill.shares <= 1e-6) {
            // Nothing was taken at that price. Leaving the order resting would
            // be a bet placed by a rule that decided against placing one.
            result.orderId?.let { id ->
                engine.session()?.let { s ->
                    try {
                        ClobApi.cancelOrder(s.creds, s.account.signerAddress, id)
                    } catch (e: Exception) {
                        // It may have filled in between; the log will say so.
                    }
                }
            }
            note = "не налили по ${(ask * 100).toInt()}¢"
            return
        }

        val price = if (fill.usd > 0.0) fill.usd / fill.shares else limit
        lot = Lot(
            asset = token,
            conditionId = market.conditionId,
            outcome = side,
            shares = fill.shares,
            price = price,
            boughtAt = System.currentTimeMillis(),
            windowStart = windowStart,
        )
        totals = totals.copy(spent = totals.spent + fill.shares * price)
        store.saveTotals(totals)
        engine.log(
            "trade",
            "Пульс: взял " + String.format("%.1f", fill.shares) + " $side по " +
                "${(price * 100).toInt()}¢",
        )
    }

    /**
     * The same buy, on paper.
     *
     * Taking an offer costs what it asks plus the venue's fee, and the fee is
     * real whether or not the money is — a demo that ignored it would report a
     * profit the same trade would not have made.
     */
    private fun paperBuy(
        market: Market,
        token: String,
        side: String,
        ask: Double,
        size: Double,
        windowStart: Long,
    ) {
        val price = ProbePlan.takenPrice(ask)
        lot = Lot(
            asset = token,
            conditionId = market.conditionId,
            outcome = side,
            shares = size,
            price = price,
            boughtAt = System.currentTimeMillis(),
            windowStart = windowStart,
            demo = true,
        )
        totals = totals.copy(spent = totals.spent + size * price)
        store.saveTotals(totals)
        engine.log(
            "trade",
            "Пульс (демо): взял " + String.format("%.1f", size) + " $side по " +
                "${(ask * 100).toInt()}¢ — счёт $" + String.format("%.2f", cash),
        )
    }

    /**
     * The paper lot's exit: its own margin, and the desk's ladder as well.
     *
     * Nothing on the desk can see a position that was never placed, so both
     * rules are applied here — whichever price the book reaches first is the
     * one that fills, which is what would happen if both offers were real. The
     * ladder matters most at the end, where its floor sells a side that is no
     * longer going anywhere rather than letting it ride to nothing.
     *
     * The one case where neither sells is the one Pulse is built for: a lead
     * still standing at the close pays a whole dollar and no fee, which beats
     * every rung.
     */
    private fun workPaper(open: Lot, current: PulsePlan.Read, market: Market) {
        val nowSec = Clock.nowSec()
        val elapsed = nowSec - open.windowStart
        val secondsLeft = open.windowStart + 300L - nowSec

        val bid = try {
            ClobApi.bestBid(open.asset)
        } catch (e: Exception) {
            null
        }
        if (bid == null || bid <= 0.0) {
            open.note = "нет спроса"
            return
        }

        open.highWater = maxOf(open.highWater, bid)
        val rule = exit()
        open.rung = maxOf(
            open.rung,
            ProbePlan.exitStep(elapsed, open.highWater, open.rung, rule),
        )

        when (PulsePlan.exitFor(open.outcome, current, settings)) {
            PulsePlan.Exit.RIDE -> {
                open.note = "довожу до расчёта"
                return
            }

            PulsePlan.Exit.CUT -> {
                open.note = "режу по рынку"
                paperSell(open, bid, "режу")
                return
            }

            PulsePlan.Exit.HOLD -> {
                val mine = PulsePlan.takePrice(open.price, settings, market.tickSize)
                val rungAsk = ProbePlan.exitPrice(
                    cost = open.price,
                    elapsedSec = elapsed,
                    secondsLeft = secondsLeft,
                    highWater = open.highWater,
                    rung = open.rung,
                    bestBid = bid,
                    exit = rule,
                    tick = market.tickSize,
                )
                val want = minOf(mine, rungAsk)
                open.sellPrice = want
                if (bid >= want - 1e-9) paperSell(open, want, "по ${(want * 100).toInt()}¢")
            }
        }
    }

    private fun paperSell(open: Lot, price: Double, why: String) {
        val left = open.open
        if (left <= 1e-9) return
        val got = left * SellPercent.netSell(price)
        open.sold += left
        open.proceeds += got
        totals = totals.copy(got = totals.got + got)
        store.saveTotals(totals)
        engine.log(
            "trade",
            "Пульс (демо): продал " + String.format("%.1f", left) + " ${open.outcome} $why",
        )
    }

    /**
     * Keeps the open lot's exit honest.
     *
     * Normally that is one resting offer at the take price and nothing else.
     * The two exceptions are the whole reason this runs: a lead that has turned
     * against the position is sold into the book at once, and a lead that is
     * still standing at the end of the window is not sold at all.
     */
    private fun work(open: Lot, current: PulsePlan.Read, market: Market) {
        if (open.demo) {
            workPaper(open, current, market)
            if (open.open <= 1e-6) finish(open)
            return
        }

        collect(open)
        if (open.open <= 1e-6) {
            finish(open)
            return
        }

        // An offer that has left the book without filling was pulled by
        // somebody — the desk cancels the rules' sells when the person sells by
        // hand, and their order outranks this one. Forget it; the branches
        // below will put a fresh one out.
        val id = open.sellOrderId
        if (id != null &&
            engine.restingAt > open.sellPlacedAt &&
            engine.resting.none { it.id == id }
        ) {
            open.sellOrderId = null
            open.note = "ордер сняли"
        }

        when (PulsePlan.exitFor(open.outcome, current, settings)) {
            PulsePlan.Exit.RIDE -> {
                if (open.sellOrderId != null) {
                    cancelOffer(open)
                    open.note = "довожу до расчёта"
                    engine.log("info", "Пульс: держу ${open.outcome} до расчёта")
                }
            }

            PulsePlan.Exit.CUT -> {
                if (open.sellOrderId != null) cancelOffer(open)
                val bid = try {
                    ClobApi.bestBid(open.asset)
                } catch (e: Exception) {
                    null
                }
                if (bid == null || bid <= 0.0) {
                    open.note = "нет спроса"
                    return
                }
                offer(open, (bid - market.tickSize).coerceAtLeast(market.tickSize), market, cut = true)
            }

            PulsePlan.Exit.HOLD -> {
                val want = PulsePlan.takePrice(open.price, settings, market.tickSize)
                if (open.sellOrderId != null && abs(open.sellPrice - want) <= market.tickSize / 2) {
                    return
                }
                if (open.sellOrderId != null) cancelOffer(open)
                offer(open, want, market, cut = false)
            }
        }
    }

    /** Reads the fill of our own offer off the order log. */
    private fun collect(open: Lot) {
        val id = open.sellOrderId ?: return
        val entry = OrderLog.all().firstOrNull { it.orderId == id } ?: return
        if (entry.matched <= open.sold + 1e-9) return

        val gained = entry.matched - open.sold
        val price = entry.realPrice
        open.sold = entry.matched
        open.proceeds += gained * price
        totals = totals.copy(got = totals.got + gained * price)
        store.saveTotals(totals)
        engine.log(
            "trade",
            "Пульс: продал " + String.format("%.1f", gained) + " по ${(price * 100).toInt()}¢",
        )
    }

    private fun offer(open: Lot, price: Double, market: Market, cut: Boolean) {
        if (open.open < market.minimumOrderSize - 1e-6) return

        // The venue locks freshly bought shares; how long for has been measured
        // rather than guessed.
        val hold = Timings.holdMs(open.boughtAt, System.currentTimeMillis())
        if (hold > 0L) {
            open.note = "жду ${(hold + 999) / 1000} с"
            return
        }

        Timings.sellTried(open.asset, open.boughtAt, System.currentTimeMillis())
        val result = try {
            engine.placeManualOrder(
                tokenId = open.asset,
                conditionId = open.conditionId,
                side = "SELL",
                price = price,
                size = open.open,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            Timings.sellDropped(open.asset)
            open.note = e.message ?: "ошибка сети"
            return
        }

        if (result.success) {
            Timings.sellAccepted(open.asset, open.boughtAt, System.currentTimeMillis())
            open.sellOrderId = result.orderId
            open.sellPrice = price
            open.sellPlacedAt = System.currentTimeMillis()
            open.note = if (cut) "режу по рынку" else null
            if (cut) {
                engine.log(
                    "warn",
                    "Пульс: режу ${open.outcome} по ${(price * 100).toInt()}¢ — перевес ушёл",
                )
            }
        } else {
            Timings.sellRefused(open.asset, open.boughtAt)
            open.note = result.error ?: "отказ CLOB"
        }
    }

    private fun cancelOffer(open: Lot) {
        val id = open.sellOrderId ?: return
        val session = engine.session() ?: return
        try {
            ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
            open.sellOrderId = null
        } catch (e: Exception) {
            // It may have filled in between, which `collect` will notice.
        }
    }

    /** A round that sold out: count it and free the bot to trade again. */
    private fun finish(open: Lot) {
        lot = null
        val pnl = open.proceeds - open.cost
        totals = totals.copy(
            rounds = totals.rounds + 1,
            wins = totals.wins + if (pnl > 0) 1 else 0,
            losses = totals.losses + if (pnl < 0) 1 else 0,
        )
        store.saveTotals(totals)
        engine.log(
            if (pnl >= 0) "trade" else "warn",
            "Пульс закрыл круг: " + (if (pnl >= 0) "+" else "−") +
                "$" + String.format("%.2f", abs(pnl)),
        )
    }

    /** The window ended holding shares: they settle, at a dollar or at nothing. */
    private fun closeRound(open: Lot) {
        lot = null
        cancelOffer(open)

        val winner = EventStats.winnerFor(open.windowStart, Clock.nowSec())
        val settlement = if (open.outcome == winner) open.open else 0.0
        val pnl = open.proceeds + settlement - open.cost

        totals = totals.copy(
            rounds = totals.rounds + 1,
            wins = totals.wins + if (pnl > 0) 1 else 0,
            losses = totals.losses + if (pnl < 0) 1 else 0,
            settled = totals.settled + settlement,
        )
        store.saveTotals(totals)
        engine.log(
            if (pnl >= 0) "trade" else "warn",
            "Пульс: окно закрылось на " + (if (winner.isEmpty()) "—" else winner) + ", " +
                (if (pnl >= 0) "+" else "−") + "$" + String.format("%.2f", abs(pnl)),
        )
    }
}
