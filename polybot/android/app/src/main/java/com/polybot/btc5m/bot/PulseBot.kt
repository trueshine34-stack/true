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
        /** Everything held, which grows as the bids under the entry fill. */
        var shares: Double,
        /** The first price paid, which is what the exit is priced off. */
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
        /**
         * Shares added by the bids waiting under the entry, and what they and
         * the entry together actually cost.
         *
         * The entry [price] is left alone as the first price paid, because
         * that is what the exit is priced off — a lower lot bought at a
         * cheaper price is not a reason to ask less for the whole position,
         * it is a reason to make more on that lot at the same ask. What the
         * round *made*, though, is money out against money in, and once a bid
         * has filled those are two different numbers.
         */
        var spent: Double = 0.0,
        /** The bids still waiting under it, so they can be pulled together. */
        var bids: List<String> = emptyList(),
        /** How much of each of those has already been counted in. */
        var bidFilled: Map<String, Double> = emptyMap(),
    ) {
        /** What the whole position has cost, the added lots included. */
        val cost: Double get() = if (spent > 0.0) spent else shares * price
        val open: Double get() = (shares - sold).coerceAtLeast(0.0)
    }

    /**
     * One closed round, kept so the rule can be read window by window.
     *
     * Totals answer "how has it done" and nothing else: a run of forty
     * rounds that nets a dollar looks identical to one that made and lost
     * forty, and only the second is worth stopping. The list is what says
     * which of the two happened.
     */
    data class Round(
        val windowStart: Long,
        val demo: Boolean,
        val outcome: String,
        val shares: Double,
        /** The first price paid, which the exit was priced off. */
        val price: Double,
        /** And what the whole position cost, the added lots included. */
        val spent: Double = 0.0,
        val proceeds: Double,
        val settled: Double,
        val winner: String,
        val note: String?,
    ) {
        val cost: Double get() = if (spent > 0.0) spent else shares * price
        val pnl: Double get() = proceeds + settled - cost
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

    /**
     * One account: what it holds and what it has done.
     *
     * The rule reads the market once and then acts twice — the reads, the
     * gates and the side are the same for both, and only the money differs.
     * Holding them side by side rather than switching between them is what
     * lets the paper record keep running while the wallet trades, which is
     * the whole point of a paper record.
     */
    class Book(val demo: Boolean, @Volatile var totals: Totals) {
        @Volatile
        var lot: Lot? = null

        /** Every window this account has closed, oldest first. */
        @Volatile
        var rounds: List<Round> = emptyList()
    }

    val paper = Book(demo = true, totals = store.loadTotals(demo = true)).also {
        it.rounds = store.loadRounds(demo = true)
    }
    val real = Book(demo = false, totals = store.loadTotals(demo = false)).also {
        it.rounds = store.loadRounds(demo = false)
    }

    private val books get() = listOf(paper, real)

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastFault: String? = null
        private set

    /** The last read, so the screen can show what the rule is looking at. */
    @Volatile
    var read: PulsePlan.Read? = null
        private set

    @Volatile
    var note: String? = null
        private set

    /**
     * What an account has to spend.
     *
     * Paper is arithmetic on its own bank; the real one is the wallet, and
     * the wallet already has the locked reserve taken out of it, so this rule
     * cannot reach money set aside any more than the desk can.
     */
    fun cash(book: Book): Double =
        if (book.demo) {
            settings.bankUsd + book.totals.got + book.totals.settled -
                book.totals.spent - (book.lot?.cost ?: 0.0) +
                (book.lot?.proceeds ?: 0.0)
        } else {
            engine.usdcRecent() ?: try {
                engine.usdcBalance()
            } catch (e: Exception) {
                0.0
            }
        }

    /**
     * Shares this bot holds, so the desk's own rule leaves them alone.
     *
     * The real lot only: paper shares are not on the venue, and claiming them
     * would hide somebody else's real position from the rule that exits it.
     */
    fun heldShares(asset: String): Double =
        real.lot?.takeIf { it.asset == asset }?.open ?: 0.0

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var openStamp = 0L
    private var openPrice = 0.0

    private companion object {
        const val TICK_MS = 2_000L

        /** More than a day of five-minute windows, which is enough to read. */
        const val MAX_ROUNDS = 200
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

    /** Wipes both records: the paper run and the real one start over together. */
    fun resetBank() {
        for (book in books) {
            book.totals = Totals()
            book.rounds = emptyList()
            store.saveTotals(book.totals, demo = book.demo)
            store.saveRounds(book.rounds, demo = book.demo)
        }
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
                ", выход +" + Math.round(PulsePlan.takeOf(settings) * 100) + "%",
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
        // it can be watched before there is any money to watch it with. The
        // paper account carries on either way; only the real one is held back.
        lastFault = if (session == null && settings.live) "кошелёк не подключён" else null
        val realOn = settings.live && session != null

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

        // Each account's own position first. They are the same rule on the
        // same window, so they usually agree — but a paper lot that filled
        // where a real order did not is exactly the difference worth keeping,
        // and each is worked out on its own terms.
        for (book in books) {
            if (!book.demo && !realOn) continue
            book.lot?.let { open ->
                // A lot from a window that has closed is settled, not managed.
                if (open.windowStart != windowStart) closeRound(book, open)
                else work(book, open, current, market)
            }
        }

        // Whichever accounts are free to open something. With both holding
        // there is no decision left to make this tick.
        val free = books.filter { (it.demo || realOn) && it.lot == null }
        if (free.isEmpty()) {
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
            if (side != null && ask != null) {
                for (book in free) {
                    // The gates above were read against the paper purse; an
                    // account is still only allowed to spend its own money.
                    val mine = PulsePlan.blockedBecause(
                        current.copy(cashUsd = cash(book)),
                        settings,
                        holding = false,
                    )
                    if (mine != null) {
                        if (!book.demo) note = mine
                        continue
                    }
                    buy(book, market, side, ask, windowStart)
                }
            }
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
            cashUsd = cash(paper),
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

    private fun buy(
        book: Book,
        market: Market,
        side: String,
        ask: Double,
        windowStart: Long,
    ) {
        val token = if (side == "Up") market.up.tokenId else market.down.tokenId
        val size = maxOf(settings.shares, Orders.minShares(ask, market.minimumOrderSize))
        // Crossing by a tick can step over the window's ceiling by that same
        // tick, and the venue would refuse the order rather than shave it.
        val limit = minOf(
            PulsePlan.crossPrice(ask, market.tickSize),
            BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart)),
        )

        if (book.demo) {
            paperBuy(book, market, token, side, ask, size, windowStart)
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
        book.lot = Lot(
            asset = token,
            conditionId = market.conditionId,
            outcome = side,
            shares = fill.shares,
            price = price,
            boughtAt = System.currentTimeMillis(),
            windowStart = windowStart,
            spent = fill.shares * price,
        )
        book.totals = book.totals.copy(spent = book.totals.spent + fill.shares * price)
        store.saveTotals(book.totals, demo = false)
        engine.log(
            "trade",
            "Пульс: взял " + String.format("%.1f", fill.shares) + " $side по " +
                "${(price * 100).toInt()}¢",
        )
        book.lot?.let { placeAdds(book, it, market, size) }
    }

    /**
     * What the position is asking, and by which rule.
     *
     * One fixed margin over the entry, or the desk's own ladder — a price
     * that starts high and walks down with the clock, taking what the window
     * is actually offering. The ladder's rungs are absolute, so it can ask
     * under a dear entry; that is the whole of the trade it makes, and the
     * rule that wants it enters often enough on thin evidence that most of
     * its positions are small moves rather than the one big one.
     */
    private fun askPrice(open: Lot, market: Market, bid: Double?): Double {
        if (!settings.ladder) {
            // A doubling is taken whatever the take price says.
            return SellLadder.capped(
                PulsePlan.takePrice(open.price, settings, market.tickSize),
                open.price,
            )
        }
        val nowSec = Clock.nowSec()
        return Exits.price(
            cost = open.price,
            elapsedSec = nowSec - open.windowStart,
            secondsLeft = open.windowStart + PulsePlan.WINDOW_SEC - nowSec,
            highWater = open.highWater,
            rung = open.rung,
            bestBid = bid,
            exit = exit(),
            tick = market.tickSize,
        )
    }

    /**
     * Walks the ladder's step on, once the ask for this tick has been read
     * off the mark it had before.
     *
     * Pricing a rung off the same bid it is tested against walks it up out of
     * reach every time the price jumps, and a resting offer does not do that
     * — it gets hit. So the mark moves after the decision, never before it.
     */
    private fun walkRung(open: Lot, bid: Double) {
        open.highWater = maxOf(open.highWater, bid)
        if (!settings.ladder) return
        open.rung = maxOf(
            open.rung,
            Exits.step(
                Clock.nowSec() - open.windowStart,
                open.highWater,
                open.rung,
                exit(),
            ),
        )
    }

    /**
     * The three bids that wait under an entry, each for the size the entry
     * was, six cents apart.
     *
     * They go out once, with the entry, and are pulled when the window ends.
     * Each is only sent if the account can still pay for it — the wallet's
     * balance is already net of anything locked away, so this cannot reach
     * money that was set aside.
     */
    private fun placeAdds(book: Book, open: Lot, market: Market, size: Double) {
        val ids = ArrayList<String>()
        var left = cash(book)
        for (at in PulsePlan.addPrices(open.price, market.tickSize)) {
            val need = size * at
            if (need > left) break
            val result = try {
                engine.placeManualOrder(
                    tokenId = open.asset,
                    conditionId = open.conditionId,
                    side = "BUY",
                    price = at,
                    size = size,
                    orderType = "GTC",
                    auto = true,
                )
            } catch (e: Exception) {
                break
            }
            if (!result.success) break
            result.orderId?.let { ids.add(it) }
            left -= need
        }
        open.bids = ids
        if (ids.isNotEmpty()) {
            engine.log(
                "info",
                "Пульс: под входом ждут " + ids.size + " заявки по " +
                    PulsePlan.addPrices(open.price, market.tickSize)
                        .take(ids.size)
                        .joinToString("/") { "${(it * 100).toInt()}¢" },
            )
        }
    }

    /**
     * The same buy, on paper.
     *
     * Taking an offer costs what it asks plus the venue's fee, and the fee is
     * real whether or not the money is — a demo that ignored it would report a
     * profit the same trade would not have made.
     */
    private fun paperBuy(
        book: Book,
        market: Market,
        token: String,
        side: String,
        ask: Double,
        size: Double,
        windowStart: Long,
    ) {
        val price = Exits.takenPrice(ask)
        book.lot = Lot(
            asset = token,
            conditionId = market.conditionId,
            outcome = side,
            shares = size,
            price = price,
            boughtAt = System.currentTimeMillis(),
            windowStart = windowStart,
            demo = true,
            spent = size * price,
        )
        book.totals = book.totals.copy(spent = book.totals.spent + size * price)
        store.saveTotals(book.totals, demo = true)
        engine.log(
            "trade",
            "Пульс (демо): взял " + String.format("%.1f", size) + " $side по " +
                "${(ask * 100).toInt()}¢ — счёт $" + String.format("%.2f", cash(book)),
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
    private fun workPaper(book: Book, open: Lot, current: PulsePlan.Read, market: Market) {
        val nowSec = Clock.nowSec()
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

        paperAdds(book, open, market)

        when (PulsePlan.exitFor(open.outcome, current, settings)) {
            PulsePlan.Exit.RIDE -> {
                open.note = "довожу до расчёта"
                return
            }

            PulsePlan.Exit.CUT -> {
                open.note = "режу по рынку"
                paperSell(book, open, bid, "режу")
                return
            }

            PulsePlan.Exit.HOLD -> {
                // Its own price and nothing else. The desk's ladder used to be
                // laid over this and the lower of the two was asked — which is
                // what a ladder does, walk a price down over the window — and
                // the ladder's rungs are absolute: 77¢ early, whatever the
                // shares cost. On a side taken at 85¢ that asked 77¢, a loss
                // sold on purpose, and on every entry it undercut the margin
                // the rule exists to collect. What is left of the ladder here
                // is the doubling cap, which only ever lowers an ask that has
                // run away above twice cost.
                val want = askPrice(open, market, bid)
                open.sellPrice = want
                if (SellLadder.reached(bid, want)) {
                    // Up to the last minute nothing is on the book: the price
                    // is one to wait for, and a bid that jumps past it pays
                    // what it jumped to. Inside the last minute the offer is
                    // resting there and gets the price it asked for.
                    val resting = SellLadder.restsNow(secondsLeft)
                    val got = if (resting) want else bid
                    paperSell(
                        book,
                        open,
                        got,
                        (if (resting) "лимитка" else "по рынку") +
                            " ${(got * 100).toInt()}¢",
                    )
                }
                walkRung(open, bid)
            }
        }
    }

    /**
     * The same three bids, on paper.
     *
     * A resting bid fills when somebody sells into it, so the test is the
     * offer side of the book coming down to meet it: an ask at or under the
     * bid's price is a seller who would have taken it. Each rung fills once,
     * for the size the entry was, and only while the paper purse can pay.
     */
    private fun paperAdds(book: Book, open: Lot, market: Market) {
        val ask = try {
            ClobApi.bestAsk(open.asset)
        } catch (e: Exception) {
            null
        } ?: return
        if (ask <= 0.0) return

        val size = maxOf(settings.shares, Orders.minShares(ask, market.minimumOrderSize))
        for ((i, at) in PulsePlan.addPrices(open.price, market.tickSize).withIndex()) {
            val id = "paper$i"
            if (open.bidFilled.containsKey(id)) continue
            if (ask > at + 1e-9) continue
            // Paper pays the fee the same as the entry did.
            val paid = Exits.takenPrice(at)
            if (size * paid > cash(book)) break
            open.bidFilled = open.bidFilled + (id to size)
            open.shares += size
            open.spent += size * paid
            book.totals = book.totals.copy(spent = book.totals.spent + size * paid)
            store.saveTotals(book.totals, demo = true)
            engine.log(
                "trade",
                "Пульс (демо): добрал " + String.format("%.1f", size) + " ${open.outcome}" +
                    " по ${(at * 100).toInt()}¢ — средняя " +
                    "${((open.spent / open.shares) * 100).toInt()}¢",
            )
        }
    }

    private fun paperSell(book: Book, open: Lot, price: Double, why: String) {
        val left = open.open
        if (left <= 1e-9) return
        val got = left * SellPercent.netSell(price)
        open.sold += left
        open.proceeds += got
        book.totals = book.totals.copy(got = book.totals.got + got)
        store.saveTotals(book.totals, demo = true)
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
    private fun work(book: Book, open: Lot, current: PulsePlan.Read, market: Market) {
        if (open.demo) {
            workPaper(book, open, current, market)
            if (open.open <= 1e-6) finish(book, open)
            return
        }

        collectAdds(book, open)
        collect(open)
        if (open.open <= 1e-6) {
            finish(book, open)
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
                val seen = try {
                    ClobApi.bestBid(open.asset)
                } catch (e: Exception) {
                    null
                }
                val want = askPrice(open, market, seen)
                if (seen != null && seen > 0.0) walkRung(open, seen)
                val secondsLeft = open.windowStart + PulsePlan.WINDOW_SEC - Clock.nowSec()

                // Up to the last minute the price is watched rather than
                // offered. An offer resting at it is a promise to sell at
                // exactly that price, so a book that runs straight through
                // pays the promise and keeps the rest of the move; watching
                // makes the same number a floor instead of a ceiling.
                if (!SellLadder.restsNow(secondsLeft)) {
                    if (open.sellOrderId != null) cancelOffer(open)
                    val bid = seen
                    open.sellPrice = want
                    if (!SellLadder.reached(bid, want)) {
                        open.note = "жду ${(want * 100).toInt()}¢"
                        return
                    }
                    // Reached: take it, a tick under the bid so the top of
                    // book moving between the read and the send cannot miss.
                    offer(
                        open,
                        (bid!! - market.tickSize).coerceAtLeast(market.tickSize),
                        market,
                        cut = true,
                    )
                    return
                }

                if (open.sellOrderId != null && abs(open.sellPrice - want) <= market.tickSize / 2) {
                    return
                }
                if (open.sellOrderId != null) cancelOffer(open)
                offer(open, want, market, cut = false)
            }
        }
    }

    /**
     * Reads the fills of the bids waiting under the entry.
     *
     * Each one that fills buys the same side cheaper and makes the position
     * bigger; the entry price is left alone, because it is what the exit is
     * priced off, and a lot bought lower simply makes more at the same ask.
     */
    private fun collectAdds(book: Book, open: Lot) {
        if (open.bids.isEmpty()) return
        val log = OrderLog.all()
        for (id in open.bids) {
            val entry = log.firstOrNull { it.orderId == id } ?: continue
            val had = open.bidFilled[id] ?: 0.0
            if (entry.matched <= had + 1e-9) continue

            val gained = entry.matched - had
            val price = entry.realPrice
            open.bidFilled = open.bidFilled + (id to entry.matched)
            open.shares += gained
            open.spent += gained * price
            book.totals = book.totals.copy(spent = book.totals.spent + gained * price)
            store.saveTotals(book.totals, demo = book.demo)
            engine.log(
                "trade",
                "Пульс: добрал " + String.format("%.1f", gained) + " ${open.outcome} по " +
                    "${(price * 100).toInt()}¢ — средняя " +
                    "${((open.spent / open.shares) * 100).toInt()}¢",
            )
            // The offer standing over the position is now for fewer shares
            // than are held; it goes back out for the whole of it.
            if (open.sellOrderId != null) cancelOffer(open)
        }
    }

    /** Pulls whatever is still waiting under a position that is done. */
    private fun cancelAdds(open: Lot) {
        if (open.bids.isEmpty()) return
        val session = engine.session()
        if (session != null) {
            for (id in open.bids) {
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
                } catch (e: Exception) {
                    // It may have filled in between; the log knows either way.
                }
            }
        }
        open.bids = emptyList()
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
        real.totals = real.totals.copy(got = real.totals.got + gained * price)
        store.saveTotals(real.totals, demo = false)
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
    private fun finish(book: Book, open: Lot) {
        book.lot = null
        if (!open.demo) cancelAdds(open)
        val pnl = open.proceeds - open.cost
        file(book, open, settlement = 0.0, winner = "", note = open.note ?: "продано")
        book.totals = book.totals.copy(
            rounds = book.totals.rounds + 1,
            wins = book.totals.wins + if (pnl > 0) 1 else 0,
            losses = book.totals.losses + if (pnl < 0) 1 else 0,
        )
        store.saveTotals(book.totals, demo = book.demo)
        engine.log(
            if (pnl >= 0) "trade" else "warn",
            "Пульс" + (if (book.demo) " (демо)" else "") + " закрыл круг: " +
                (if (pnl >= 0) "+" else "−") + "$" + String.format("%.2f", abs(pnl)),
        )
    }

    /**
     * Files a closed round on the account that traded it.
     *
     * Capped at a couple of hundred, which is more than a day of five-minute
     * windows — long enough to read a run, short enough not to grow forever.
     */
    private fun file(
        book: Book,
        open: Lot,
        settlement: Double,
        winner: String,
        note: String?,
    ) {
        book.rounds = (
            book.rounds + Round(
                windowStart = open.windowStart,
                demo = book.demo,
                outcome = open.outcome,
                shares = open.shares,
                price = open.price,
                spent = open.cost,
                proceeds = open.proceeds,
                settled = settlement,
                winner = winner,
                note = note,
            )
            ).takeLast(MAX_ROUNDS)
        store.saveRounds(book.rounds, demo = book.demo)
    }

    /** The window ended holding shares: they settle, at a dollar or at nothing. */
    private fun closeRound(book: Book, open: Lot) {
        book.lot = null
        if (!open.demo) {
            cancelOffer(open)
            // A bid left standing under a settled window would buy a side of
            // a market that has already paid out.
            cancelAdds(open)
        }

        val winner = EventStats.winnerFor(open.windowStart, Clock.nowSec())
        val settlement = if (open.outcome == winner) open.open else 0.0
        val pnl = open.proceeds + settlement - open.cost
        file(book, open, settlement, winner, open.note ?: "расчёт")

        book.totals = book.totals.copy(
            rounds = book.totals.rounds + 1,
            wins = book.totals.wins + if (pnl > 0) 1 else 0,
            losses = book.totals.losses + if (pnl < 0) 1 else 0,
            settled = book.totals.settled + settlement,
        )
        store.saveTotals(book.totals, demo = book.demo)
        engine.log(
            if (pnl >= 0) "trade" else "warn",
            "Пульс" + (if (book.demo) " (демо)" else "") + ": окно закрылось на " +
                (if (winner.isEmpty()) "—" else winner) + ", " +
                (if (pnl >= 0) "+" else "−") + "$" + String.format("%.2f", abs(pnl)),
        )
    }
}
