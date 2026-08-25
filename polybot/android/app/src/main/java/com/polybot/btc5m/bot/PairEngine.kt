package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The pair bot.
 *
 * It does not forecast anything. It builds matched Up/Down pairs, which settle
 * to exactly $1 regardless of outcome, and tries to assemble them for less than
 * that: seed the cheap side in small lots, rest a limit on the other side at
 * whatever price still leaves the pair under its ceiling, and recycle a leg
 * that has run into the side that has not.
 *
 * The edge is the spread between what a pair costs and the dollar it pays, so
 * every order rests by default. Polymarket charges the taker only — crossing
 * both legs of a 95¢ pair hands back about two thirds of the margin.
 *
 * Unmatched shares are the only thing here exposed to the outcome, so the book
 * is squared before the window closes.
 */
class PairEngine(
    private val feed: ChainlinkFeed,
    private val journal: Journal,
    private val session: () -> BotEngine.Session?,
    private val marketNow: () -> Market?,
    private val onStateChanged: () -> Unit,
    private val onLog: (LogEntry) -> Unit,
) {
    @Volatile
    var settings: PairSettings = PairSettings()

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var haltReason: String? = null
        private set

    @Volatile
    var book: PairBook? = null
        private set

    @Volatile
    var quotes: Quotes? = null
        private set

    val orders = CopyOnWriteArrayList<PairOrder>()
    val fills = CopyOnWriteArrayList<PairFill>()
    val history = CopyOnWriteArrayList<PairBook>()

    @Volatile
    var stats: PairStats = PairStats()
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val ids = AtomicLong(0)
    private val logIds = AtomicLong(0)

    private var feeRate = 0.07
    private var feeExponent = 1.0
    private var feeMarket: String? = null

    private var lastQuoteMs = 0L
    private var lastReconcileMs = 0L
    private val random = java.util.Random()

    private companion object {
        const val TICK_MS = 500L
        const val QUOTE_EVERY_MS = 2_000L
        const val RECONCILE_EVERY_MS = 2_500L
        const val MAX_FILLS = 400
        const val MAX_HISTORY = 60
    }

    fun log(level: String, message: String) {
        val entry = LogEntry(logIds.incrementAndGet(), System.currentTimeMillis(), level, "Пара · $message")
        journal.log(level, "[PAIR] $message")
        onLog(entry)
    }

    fun updateSettings(next: PairSettings) {
        settings = next
        log("info", "Настройки обновлены")
        onStateChanged()
    }

    fun start() {
        if (running) return
        if (!settings.dryRun && session() == null) {
            log("error", "Кошелёк не подключён — боевой режим невозможен")
            return
        }
        running = true
        haltReason = null
        log("info", if (settings.dryRun) "Запуск в тестовом режиме" else "Запуск в боевом режиме")

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            while (isActive && running) {
                try {
                    tick()
                } catch (e: Exception) {
                    log("error", "Сбой цикла: ${e.message}")
                }
                delay(TICK_MS)
            }
        }
        onStateChanged()
    }

    fun stop(reason: String? = null) {
        if (!running) return
        running = false
        haltReason = reason
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        // Resting orders are real money; pull them rather than leaving them out
        // there with nothing watching the book.
        cancelAllLive("бот остановлен")
        log("info", reason?.let { "Остановлен: $it" } ?: "Остановлен")
        onStateChanged()
    }

    fun reset() {
        cancelAllLive("сброс")
        book = null
        fills.clear()
        history.clear()
        stats = PairStats()
        log("info", "Книга и статистика обнулены")
        onStateChanged()
    }

    // ----------------------------------------------------------------- loop

    private fun tick() {
        val market = marketNow()
        if (market == null) {
            haltReason = "рынок окна не найден"
            return
        }
        haltReason = null
        loadFees(market)

        val current = book
        if (current == null || current.windowStart != market.windowStart) {
            current?.let { closeOut(it) }
            book = PairBook(
                windowStart = market.windowStart,
                windowEnd = market.windowEnd,
                market = market,
                nextSeedAtMs = System.currentTimeMillis(),
            )
            orders.clear()
            log("info", "Новое окно ${formatWindow(market.windowStart)}")
            onStateChanged()
            return
        }
        current.market = market

        refreshQuotes(market)
        reconcile(market)

        val now = System.currentTimeMillis()
        val closesInMs = market.windowEnd * 1000L - now

        if (closesInMs <= settings.flattenSec * 1000L) {
            flatten(current, market)
        } else {
            rotate(current, market)
            completePair(current, market)
            seed(current, market, now)
        }
        onStateChanged()
    }

    private fun loadFees(market: Market) {
        if (feeMarket == market.conditionId) return
        try {
            val (rate, exponent) = ClobApi.feeParams(market.conditionId)
            feeRate = rate
            feeExponent = exponent
        } catch (e: Exception) {
            // Keep the published defaults; they are what the venue documents.
        }
        feeMarket = market.conditionId
    }

    private fun refreshQuotes(market: Market) {
        val now = System.currentTimeMillis()
        if (now - lastQuoteMs < QUOTE_EVERY_MS) return
        lastQuoteMs = now
        quotes = try {
            Quotes(
                up = ClobApi.quote(market.up.tokenId),
                down = ClobApi.quote(market.down.tokenId),
                atMs = now,
            )
        } catch (e: Exception) {
            quotes
        }
    }

    private fun quoteFor(side: String): Quote? {
        val q = quotes ?: return null
        if (System.currentTimeMillis() - q.atMs > 15_000) return null
        return if (side == "Up") q.up else q.down
    }

    private fun legOf(bookNow: PairBook, side: String): PairLeg =
        if (side == "Up") bookNow.up else bookNow.down

    private fun other(side: String): String = if (side == "Up") "Down" else "Up"

    private fun tokenOf(market: Market, side: String): String =
        if (side == "Up") market.up.tokenId else market.down.tokenId

    // ------------------------------------------------------------ the rules

    /**
     * Buy the cheap side in lots.
     *
     * Randomising inside the configured window keeps the bot from laying a
     * metronome into the book, which is both easy to read and easy to trade
     * against.
     */
    private fun seed(bookNow: PairBook, market: Market, now: Long) {
        if (now < bookNow.nextSeedAtMs) return

        val upMid = quoteFor("Up")?.mid
        val downMid = quoteFor("Down")?.mid
        if (upMid == null || downMid == null) return

        val side = if (upMid <= downMid) "Up" else "Down"
        // One resting buy per side. The other side's order is the pair
        // completion and has a job of its own.
        if (liveOrder(side, "BUY") != null) return

        val lot = maxOf(settings.lotShares, market.minimumOrderSize)
        val price = bidFor(bookNow, market, side) ?: return
        if (price > settings.maxSeedPrice) {
            // Nothing is cheap right now; wait rather than pay up.
            scheduleNextSeed(bookNow, now)
            return
        }
        if (!withinCaps(bookNow, side, price, lot)) {
            scheduleNextSeed(bookNow, now)
            return
        }

        place(market, side, "BUY", price, lot, "набор дешёвой стороны")
        scheduleNextSeed(bookNow, now)
    }

    /**
     * Where to rest a bid on one side.
     *
     * Two prices apply and the lower wins. The book gives one: never bid above
     * what is already on offer, or the order crosses and pays the taker fee
     * that the whole margin is made of. The pair budget gives the other: with
     * Up and Down quoted at a combined dollar, a pair can only be assembled for
     * 95¢ by bidding *below* both mids and waiting to be hit — which is exactly
     * the "wait for the other side to come down" the strategy is built on.
     *
     * Once one leg is held the budget side is exact: whatever is left of the
     * ceiling after what that leg actually cost.
     */
    private fun bidFor(bookNow: PairBook, market: Market, side: String): Double? {
        val quote = quoteFor(side) ?: return null
        val budget = PairMath.maxPairCost(settings.minPairProfitPct, settings.maxPairAvg)
        val opposite = legOf(bookNow, other(side))

        val budgeted = if (opposite.shares > 1e-9) {
            // A leg is already held, so the ceiling is exact: whatever it left.
            PairMath.completionLimit(
                heavyAvg = opposite.avg,
                budget = budget,
                taker = settings.takerEntry,
                feeRate = feeRate,
                feeExponent = feeExponent,
            )
        } else {
            // No position yet, so split the ceiling the way the market splits
            // the dollar.
            val mine = quoteFor(side)?.mid ?: return null
            val theirs = quoteFor(other(side))?.mid ?: return null
            PairMath.allocatedBid(mine, theirs, budget) ?: return null
        }
        if (budgeted <= 0.0) return null

        val ceiling = if (settings.takerEntry) {
            quote.bestAsk ?: return null
        } else {
            // Strictly inside the offer: at the ask we would be the taker.
            (quote.bestAsk?.minus(market.tickSize)) ?: quote.bestBid ?: return null
        }
        val price = PairMath.snapDown(min(budgeted, ceiling), market.tickSize)
        return if (price >= market.tickSize) price else null
    }

    private fun scheduleNextSeed(bookNow: PairBook, now: Long) {
        val lo = settings.minIntervalSec.coerceAtLeast(1)
        val hi = settings.maxIntervalSec.coerceAtLeast(lo)
        val wait = lo + if (hi > lo) random.nextInt(hi - lo + 1) else 0
        bookNow.nextSeedAtMs = now + wait * 1000L
    }

    /**
     * Rest a buy on the light side at the price that still clears the margin.
     *
     * This is the order that does the work: it sits below the market waiting
     * for the other side to come down, and because it rests it pays no fee.
     */
    private fun completePair(bookNow: PairBook, market: Market) {
        val gap = bookNow.imbalance
        if (abs(gap) < 1e-9) return

        val lightSide = if (gap > 0) "Down" else "Up"
        val heavy = legOf(bookNow, other(lightSide))
        val needed = abs(gap)
        if (heavy.avg <= 0.0) return

        val budget = PairMath.maxPairCost(settings.minPairProfitPct, settings.maxPairAvg)
        val limit = bidFor(bookNow, market, lightSide) ?: return
        val size = maxOf(needed, market.minimumOrderSize)
        val existing = liveOrder(lightSide, "BUY")
        if (existing != null) {
            val samePrice = abs(existing.price - limit) < market.tickSize / 2
            val sameSize = abs(existing.remaining - size) < 0.51
            if (samePrice && sameSize) return
            cancel(existing, "перестановка")
        }
        if (!withinCaps(bookNow, lightSide, limit, size)) return

        place(
            market, lightSide, "BUY", limit, size,
            "добор пары под ${cents(budget)}",
        )
    }

    /**
     * Sell part of a leg that has run, and put the money into the other side.
     *
     * Selling half of a risen leg and buying the side that fell is what keeps
     * the two counts moving toward each other while the average cost of the
     * pair drifts down.
     */
    private fun rotate(bookNow: PairBook, market: Market) {
        for (side in listOf("Up", "Down")) {
            val leg = legOf(bookNow, side)
            if (leg.shares <= 1e-9) continue

            val target = PairMath.rotateTarget(leg, settings) ?: continue
            val bid = quoteFor(side)?.bestBid ?: continue
            if (bid < target) continue

            if (liveOrder(side, "SELL") != null) continue

            val size = leg.shares * settings.rotateFraction
            if (size < market.minimumOrderSize) continue

            // Above the bid, never on it: resting keeps the fee at nil, and on
            // a 10% target the taker fee would eat a third of the gain.
            val price = PairMath.snapUp(
                maxOf(target, bid + market.tickSize),
                market.tickSize,
            ).coerceAtMost(1.0 - market.tickSize)
            if (price <= leg.avg) continue

            place(
                market, side, "SELL", price, size,
                "ротация +" + pct(price / leg.avg - 1.0),
                rotation = true,
            )
        }
    }

    /**
     * Square the book before the close.
     *
     * Matched pairs settle to $1 whichever way the window goes, so only the
     * excess needs handling. Completing it caps the loss at what the second leg
     * costs; leaving it is a coin flip on the whole excess.
     */
    private fun flatten(bookNow: PairBook, market: Market) {
        val gap = bookNow.imbalance
        if (abs(gap) < 1e-9) {
            cancelAllLive("книга сведена")
            return
        }

        val lightSide = if (gap > 0) "Down" else "Up"
        val needed = maxOf(abs(gap), market.minimumOrderSize)

        // Stop feeding the imbalance; only the squaring order may stand.
        orders.filter { it.live && !(it.side == lightSide && it.action == "BUY") }
            .forEach { cancel(it, "сведение книги") }

        val ask = quoteFor(lightSide)?.bestAsk ?: return
        val heavy = legOf(bookNow, other(lightSide))
        // Past this point the aim is to stop the bleeding, not to make the
        // margin, so the only limit is that squaring must beat the excess
        // settling worthless.
        val cap = (1.0 - heavy.avg).coerceAtLeast(0.0)
        if (ask > cap) return

        val price = PairMath.snapUp(ask, market.tickSize)
        val existing = liveOrder(lightSide, "BUY")
        if (existing != null) {
            if (abs(existing.price - price) < market.tickSize / 2) return
            cancel(existing, "сведение книги")
        }
        place(market, lightSide, "BUY", price, needed, "сведение перед закрытием")
    }

    // ------------------------------------------------------------- guards

    private fun withinCaps(
        bookNow: PairBook,
        side: String,
        price: Double,
        shares: Double,
    ): Boolean {
        val leg = legOf(bookNow, side)
        val opposite = legOf(bookNow, other(side))

        if (PairMath.breachesPairCap(leg, opposite, price, shares, settings.maxPairAvg)) {
            return false
        }
        // Resting buys are money already committed; counting only filled cost
        // would let the bot queue up more than its ceiling and then have every
        // order fill at once.
        val pending = orders.filter { it.live && it.action == "BUY" }
            .sumOf { it.price * it.remaining }
        if (bookNow.exposureUsd + pending + price * shares > settings.maxExposureUsd) {
            return false
        }

        val projectedImbalance = if (side == "Up") {
            bookNow.up.shares + shares - bookNow.down.shares
        } else {
            bookNow.up.shares - (bookNow.down.shares + shares)
        }
        if (abs(projectedImbalance) > settings.maxImbalanceShares) return false
        return true
    }

    // -------------------------------------------------------------- orders

    private fun liveOrder(side: String?, action: String): PairOrder? =
        orders.firstOrNull { it.live && it.action == action && (side == null || it.side == side) }

    private fun place(
        market: Market,
        side: String,
        action: String,
        price: Double,
        size: Double,
        note: String,
        rotation: Boolean = false,
    ) {
        if (price <= 0.0 || price >= 1.0 || size <= 0.0) return

        val order = PairOrder(
            localId = ids.incrementAndGet(),
            orderId = null,
            side = side,
            action = action,
            price = price,
            size = size,
            dryRun = settings.dryRun,
            placedAt = System.currentTimeMillis(),
            note = note,
            rotation = rotation,
        )

        if (settings.dryRun) {
            orders.add(order)
            log("trade", "[ТЕСТ] $action $side " + shares(size) + " по ${cents(price)} — $note")
            // A limit placed through the book fills at once, as taker.
            crossOnPlacement(order)
            return
        }

        val s = session()
        if (s == null) {
            log("error", "Кошелёк недоступен — ордер не отправлен")
            return
        }

        val cfg = Orders.roundConfigFor(market.tickSize)
        val amounts = Orders.limitOrderAmounts(action, size, price, cfg)
        val signed = Orders.buildAndSign(
            keyPair = s.keys,
            signerAddress = s.account.signerAddress,
            funder = s.account.funderAddress,
            signatureType = s.account.signatureType,
            tokenId = tokenOf(market, side),
            side = action,
            amounts = amounts,
            negRisk = market.negRisk,
        )

        val result = try {
            ClobApi.postOrder(signed, s.creds, s.account.signerAddress, "GTC")
        } catch (e: Exception) {
            log("error", "$action $side не отправлен: ${e.message}")
            return
        }
        if (!result.success) {
            log("error", "$action $side отклонён: ${result.error ?: "отказ CLOB"}")
            return
        }

        order.orderId = result.orderId
        orders.add(order)
        log("trade", "$action $side " + shares(size) + " по ${cents(price)} — $note")

        // A crossing limit matches on arrival; the venue reports it in the same
        // response, so book it now rather than waiting for a poll. It fills at
        // the far side of the book, not at our limit, so take the price the
        // amounts actually imply.
        val matched = result.takingAmount ?: 0.0
        if (matched > 1e-9) {
            val paid = result.makingAmount
            val actual = if (paid != null && matched > 0.0) {
                if (action == "BUY") paid / matched else matched / paid
            } else {
                price
            }
            fill(order, min(matched, size), actual, taker = true)
        }
    }

    private fun cancel(order: PairOrder, why: String) {
        order.cancelled = true
        if (order.dryRun) {
            log("info", "[ТЕСТ] Снят ${order.action} ${order.side} — $why")
            return
        }
        val s = session() ?: return
        try {
            order.orderId?.let { ClobApi.cancelOrder(s.creds, s.account.signerAddress, it) }
            log("info", "Снят ${order.action} ${order.side} — $why")
        } catch (e: Exception) {
            log("error", "Не удалось снять ордер: ${e.message}")
        }
    }

    private fun cancelAllLive(why: String) {
        orders.filter { it.live }.forEach { cancel(it, why) }
    }

    /**
     * Belt and braces at the end of a window: cancel everything this account has
     * on the closing market. A cancel that failed on a flaky connection would
     * otherwise leave a live order behind with nothing tracking it.
     */
    private fun sweepMarket(bookNow: PairBook) {
        if (settings.dryRun) return
        val s = session() ?: return
        val conditionId = bookNow.market?.conditionId ?: return
        try {
            val n = ClobApi.cancelMarketOrders(s.creds, s.account.signerAddress, conditionId)
            if (n > 0) log("info", "Снято ордеров при закрытии окна: $n")
        } catch (e: Exception) {
            log("error", "Не удалось снять ордера закрытого окна: ${e.message}")
        }
    }

    // ------------------------------------------------------------- fills

    /**
     * Paper mode: a limit that lands on or through the far side of the book is
     * a taker and fills immediately, exactly as the venue would treat it.
     */
    private fun crossOnPlacement(order: PairOrder) {
        val quote = quoteFor(order.side) ?: return
        if (order.action == "BUY") {
            val ask = quote.bestAsk ?: return
            if (order.price >= ask) fill(order, order.remaining, ask, taker = true)
        } else {
            val bid = quote.bestBid ?: return
            if (order.price <= bid) fill(order, order.remaining, bid, taker = true)
        }
    }

    private fun reconcile(market: Market) {
        if (settings.dryRun) {
            simulateRestingFills()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastReconcileMs < RECONCILE_EVERY_MS) return
        lastReconcileMs = now

        val s = session() ?: return
        val open = try {
            ClobApi.openOrders(s.creds, s.account.signerAddress, market.conditionId)
                .associateBy { it.id }
        } catch (e: Exception) {
            return
        }

        for (order in orders.filter { it.live && it.orderId != null }) {
            val remote = open[order.orderId]
            if (remote != null) {
                val delta = remote.sizeMatched - order.matched
                if (delta > 1e-9) fill(order, delta, order.price, taker = false)
                continue
            }
            // Gone from the book: either it filled or someone cancelled it.
            val resolved = try {
                ClobApi.order(s.creds, s.account.signerAddress, order.orderId!!)
            } catch (e: Exception) {
                null
            }
            val matched = resolved?.sizeMatched ?: order.size
            val delta = matched - order.matched
            if (delta > 1e-9) fill(order, delta, order.price, taker = false)
            if (order.remaining > 1e-9) order.cancelled = true
        }
    }

    /**
     * Paper mode: a resting order fills once the market has *passed* its price,
     * not merely touched it. Touching only means our order reached the front of
     * a queue we cannot see; assuming a fill there flatters the results.
     */
    private fun simulateRestingFills() {
        for (order in orders.filter { it.live }) {
            val quote = quoteFor(order.side) ?: continue
            if (order.action == "BUY") {
                val ask = quote.bestAsk ?: continue
                if (ask < order.price) fill(order, order.remaining, order.price, taker = false)
            } else {
                val bid = quote.bestBid ?: continue
                if (bid > order.price) fill(order, order.remaining, order.price, taker = false)
            }
        }
    }

    private fun fill(order: PairOrder, shares: Double, price: Double, taker: Boolean) {
        val bookNow = book ?: return
        if (shares <= 1e-9) return

        val fee = if (taker) Strategy.takerFeePerShare(price, feeRate, feeExponent) * shares else 0.0
        val leg = legOf(bookNow, order.side)
        order.matched += shares

        if (order.action == "BUY") {
            val cost = shares * price + fee
            leg.buy(shares, cost)
            bookNow.spentUsd += cost
            stats.buys += 1
        } else {
            val proceeds = shares * price - fee
            leg.sell(shares)
            bookNow.proceedsUsd += proceeds
            stats.sells += 1
        }
        bookNow.feesUsd += fee
        stats.feesUsd += fee

        fills.add(
            PairFill(
                at = System.currentTimeMillis(),
                side = order.side,
                action = order.action,
                shares = shares,
                price = price,
                feeUsd = fee,
                dryRun = order.dryRun,
                note = order.note,
            ),
        )
        while (fills.size > MAX_FILLS) fills.removeAt(0)

        val tag = if (order.dryRun) "[ТЕСТ] " else ""
        val kind = if (taker) "тейкер" else "мейкер"
        log(
            "trade",
            tag + "Исполнено ${order.action} ${order.side} " + shares(shares) +
                " по ${cents(price)} ($kind) · пара ${cents(bookNow.pairAvg)} · " +
                "связано ${shares(bookNow.pairs)}",
        )
        journal.record(fillRecord(bookNow, order, shares, price, fee, taker))

        if (order.rotation && order.action == "SELL") recycle(bookNow, order, shares)
        onStateChanged()
    }

    /**
     * Put a rotation's proceeds into the other side, right away.
     *
     * Selling the leg that ran and buying the one that fell is one move. The
     * two sides are complements, so the moment one is worth selling the other
     * is worth buying — waiting for the next scheduled lot would give that
     * back. The buy still has to clear the pair ceiling; if it cannot, the cash
     * simply stays put.
     */
    private fun recycle(bookNow: PairBook, sold: PairOrder, shares: Double) {
        val market = bookNow.market ?: return
        val side = other(sold.side)
        if (liveOrder(side, "BUY") != null) return

        val size = maxOf(shares, market.minimumOrderSize)
        val price = bidFor(bookNow, market, side) ?: return
        if (!withinCaps(bookNow, side, price, size)) return

        place(market, side, "BUY", price, size, "перекладка из ${sold.side}")
    }

    // ---------------------------------------------------------- settlement

    private fun closeOut(bookNow: PairBook) {
        if (bookNow.settled) return
        cancelAllLive("окно закрыто")
        sweepMarket(bookNow)

        val winner = winnerFor(bookNow)
        bookNow.winner = winner
        if (winner != null) {
            bookNow.proceedsUsd += PairMath.settlementProceeds(bookNow.up, bookNow.down, winner)
        }
        val pnl = bookNow.proceedsUsd - bookNow.spentUsd
        bookNow.pnlUsd = pnl
        bookNow.settled = true

        stats.windows += 1
        stats.pairsLocked += bookNow.pairs
        stats.realisedPnlUsd += pnl

        history.add(0, bookNow)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)

        log(
            if (pnl >= 0) "trade" else "warn",
            "Окно ${formatWindow(bookNow.windowStart)} закрыто: " +
                (if (pnl >= 0) "+" else "") + String.format("%.3f", pnl) + " $ · " +
                "пар ${shares(bookNow.pairs)} по ${cents(bookNow.pairAvg)} · " +
                "победа ${winner ?: "?"}",
        )
        journal.record(windowRecord(bookNow))
        onStateChanged()
    }

    /**
     * Which side paid out. The venue is authoritative once it has resolved;
     * until then the settlement feed says the same thing sooner.
     */
    private fun winnerFor(bookNow: PairBook): String? {
        if (!settings.dryRun) {
            bookNow.market?.let { market ->
                try {
                    ClobApi.resolvedWinner(market.conditionId)?.let { return it }
                } catch (e: Exception) {
                    // Fall through to the feed.
                }
            }
        }
        val strike = feed.firstTickAtOrAfter(bookNow.windowStart * 1000L)?.value ?: return null
        val endMs = bookNow.windowEnd * 1000L
        val ticks = feed.ticksBetween(endMs - (TWAP_WINDOW_SECONDS * 1000).toLong(), endMs)
        if (ticks.isEmpty()) return null
        val mean = ticks.sumOf { it.value } / ticks.size
        return if (mean >= strike) "Up" else "Down"
    }

    // ------------------------------------------------------------- output

    private fun fillRecord(
        bookNow: PairBook,
        order: PairOrder,
        shares: Double,
        price: Double,
        fee: Double,
        taker: Boolean,
    ): String = buildString {
        append("{\"kind\":\"pair_fill\"")
        append(",\"window\":").append(bookNow.windowStart)
        append(",\"dryRun\":").append(order.dryRun)
        append(",\"side\":\"").append(order.side).append('"')
        append(",\"action\":\"").append(order.action).append('"')
        append(",\"shares\":").append(String.format("%.4f", shares))
        append(",\"price\":").append(String.format("%.4f", price))
        append(",\"fee\":").append(String.format("%.5f", fee))
        append(",\"taker\":").append(taker)
        append(",\"upShares\":").append(String.format("%.4f", bookNow.up.shares))
        append(",\"upAvg\":").append(String.format("%.4f", bookNow.up.avg))
        append(",\"downShares\":").append(String.format("%.4f", bookNow.down.shares))
        append(",\"downAvg\":").append(String.format("%.4f", bookNow.down.avg))
        append(",\"pairAvg\":").append(String.format("%.4f", bookNow.pairAvg))
        append(",\"note\":\"").append(order.note).append('"')
        append('}')
    }

    private fun windowRecord(bookNow: PairBook): String = buildString {
        append("{\"kind\":\"pair_window\"")
        append(",\"window\":").append(bookNow.windowStart)
        append(",\"dryRun\":").append(settings.dryRun)
        append(",\"pairs\":").append(String.format("%.4f", bookNow.pairs))
        append(",\"pairAvg\":").append(String.format("%.4f", bookNow.pairAvg))
        append(",\"upShares\":").append(String.format("%.4f", bookNow.up.shares))
        append(",\"downShares\":").append(String.format("%.4f", bookNow.down.shares))
        append(",\"spent\":").append(String.format("%.4f", bookNow.spentUsd))
        append(",\"proceeds\":").append(String.format("%.4f", bookNow.proceedsUsd))
        append(",\"fees\":").append(String.format("%.4f", bookNow.feesUsd))
        append(",\"winner\":\"").append(bookNow.winner ?: "").append('"')
        append(",\"pnl\":").append(String.format("%.4f", bookNow.pnlUsd ?: 0.0))
        append('}')
    }

    private fun cents(price: Double): String = String.format("%.0f", price * 100) + "¢"
    private fun pct(x: Double): String = String.format("%.0f", x * 100) + "%"
    private fun shares(x: Double): String = String.format("%.1f", x)

    private fun formatWindow(windowStart: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        return fmt.format(java.util.Date(windowStart * 1000L))
    }
}
