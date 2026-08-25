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
    private val store: PairStore,
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

    /** Paper and live figures, kept apart and kept across restarts. */
    @Volatile
    var testStats: PairStats = store.loadStats(dryRun = true)
        private set

    @Volatile
    var liveStats: PairStats = store.loadStats(dryRun = false)
        private set

    /** Cash in the paper account, carried over every session. */
    @Volatile
    var paperCash: Double = store.loadPaperCash(PairSettings().paperStartUsd)
        private set

    val stats: PairStats get() = if (settings.dryRun) testStats else liveStats

    /** Mid prices over the last few minutes, used to see which way a side went. */
    private val trail = java.util.concurrent.ConcurrentHashMap<String, MutableList<Tick>>()

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

        /** Leave a resting order alone this long before moving it. */
        const val REPRICE_AFTER_MS = 5_000L

        /** How far back to look when deciding which side has been rising. */
        const val MOMENTUM_LOOKBACK_MS = 30_000L
        const val MAX_TRAIL = 200
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
        testStats = PairStats()
        liveStats = PairStats()
        store.clear(settings.paperStartUsd)
        paperCash = settings.paperStartUsd
        log("info", "Книга и статистика обнулены, тестовый баланс восстановлен")
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
                dryRun = settings.dryRun,
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
            accumulate(current, market, now)
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
            ).also {
                recordTrail(it, now)
                book?.let { b ->
                    it.up?.let { q -> b.trackUp.record(q, market.tickSize) }
                    it.down?.let { q -> b.trackDown.record(q, market.tickSize) }
                }
            }
        } catch (e: Exception) {
            quotes
        }
    }

    private fun recordTrail(q: Quotes, now: Long) {
        for ((side, quote) in listOf("Up" to q.up, "Down" to q.down)) {
            val mid = quote?.mid ?: continue
            val series = trail.getOrPut(side) { java.util.Collections.synchronizedList(ArrayList()) }
            synchronized(series) {
                series.add(Tick(now, mid))
                while (series.size > MAX_TRAIL) series.removeAt(0)
            }
        }
    }

    /** Mid this side was quoted at roughly `MOMENTUM_LOOKBACK_MS` ago. */
    private fun midEarlier(side: String, now: Long): Double? {
        val series = trail[side] ?: return null
        synchronized(series) {
            val cutoff = now - MOMENTUM_LOOKBACK_MS
            return series.firstOrNull { it.timestamp >= cutoff }?.value
                ?: series.firstOrNull()?.value
        }
    }

    /**
     * Which side has been going up.
     *
     * This decides who gets bought first, and it is the difference between the
     * completion order filling and sitting there all window. Buying the rising
     * side first sets the budget for the other leg *below* where that leg is
     * heading, so the trend brings the second fill to us. Buying the falling
     * side first does the opposite: the cheap leg keeps getting cheaper while
     * the order waiting on the other side drifts further out of reach.
     *
     * With no meaningful move either way, price decides instead.
     */
    private fun leadSide(now: Long): String? {
        val upNow = quoteFor("Up")?.mid ?: return null
        val upThen = midEarlier("Up", now)
        val move = if (upThen != null) upNow - upThen else 0.0
        // Up and Down are complements, so one number describes both. Anything
        // under half a tick is noise, not a move.
        if (abs(move) < 0.005) return cheaperSide()
        return if (move > 0) "Up" else "Down"
    }

    private fun quoteFor(side: String): Quote? {
        val q = quotes ?: return null
        if (System.currentTimeMillis() - q.atMs > 15_000) return null
        return if (side == "Up") q.up else q.down
    }

    private fun statsFor(dryRun: Boolean): PairStats = if (dryRun) testStats else liveStats

    private fun adjustPaperCash(delta: Double) {
        paperCash += delta
        store.savePaperCash(paperCash)
    }

    /** Paper cash plus what the open legs would fetch at the bid right now. */
    fun paperEquity(): Double {
        val b = book ?: return paperCash
        if (!b.dryRun) return paperCash
        val up = (quoteFor("Up")?.bestBid ?: b.up.avg) * b.up.shares
        val down = (quoteFor("Down")?.bestBid ?: b.down.avg) * b.down.shares
        return paperCash + up + down
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
    /**
     * Buy both sides, in turn, never letting either run away.
     *
     * The rule that matters is the lead cap: a side may only get ahead of the
     * other by a single lot. Without it "always buy the cheaper side" is a trap
     * — the cheaper side is cheaper because it is losing, and it keeps getting
     * cheaper, so the bot pours the whole balance into the leg heading for zero
     * and never assembles a single pair.
     *
     * The cheaper side does get a bigger lot, so it also gets a bigger lead.
     * That is deliberate: if the book has to be lopsided, being long the cheap
     * leg risks a few cents a share, while being long the dear one risks most
     * of a dollar.
     */
    private fun accumulate(bookNow: PairBook, market: Market, now: Long) {
        val first = leadSide(now) ?: return
        val cheap = cheaperSide()

        for (side in listOf(first, other(first))) {
            accumulateSide(
                bookNow, market, side, now,
                cheap = side == cheap,
                lead = side == first,
            )
        }
    }

    private fun cheaperSide(): String? {
        val upMid = quoteFor("Up")?.mid ?: return null
        val downMid = quoteFor("Down")?.mid ?: return null
        return if (upMid <= downMid) "Up" else "Down"
    }

    /** Lot for one side. The cheaper leg is bought in larger size. */
    private fun lotFor(market: Market, cheap: Boolean): Double = PairMath.lotFor(
        lotShares = settings.lotShares,
        minOrder = market.minimumOrderSize,
        cheap = cheap,
        bonusPct = settings.cheapSideBonusPct,
    )

    /**
     * How many more shares this side may hold before it is too far ahead.
     *
     * Measured on filled shares only. Counting resting orders here would let a
     * large unfilled order on one side unlock unlimited buying on the other,
     * which is the runaway this cap exists to stop.
     */
    private fun allowanceFor(bookNow: PairBook, side: String, lot: Double): Double =
        PairMath.allowance(
            myShares = legOf(bookNow, side).shares,
            theirShares = legOf(bookNow, other(side)).shares,
            lot = lot,
        )

    private fun accumulateSide(
        bookNow: PairBook,
        market: Market,
        side: String,
        now: Long,
        cheap: Boolean,
        lead: Boolean,
    ) {
        val lot = lotFor(market, cheap)
        val allowance = allowanceFor(bookNow, side, lot)
        val existing = liveOrder(side, "BUY")

        if (allowance < market.minimumOrderSize) {
            // Already a full lot ahead; wait for the other side to catch up.
            existing?.let { cancel(it, "перекос: ждём вторую сторону") }
            return
        }

        val size = min(lot, allowance)
        val mine = legOf(bookNow, side).shares
        val theirs = legOf(bookNow, other(side)).shares
        val behind = mine < theirs - 1e-9
        val ahead = mine > theirs + 1e-9
        val price = bidFor(bookNow, market, side, urgent = behind) ?: return

        // Behind means this order completes a pair: it goes up at once, and the
        // pair budget is already its price ceiling.
        //
        // Otherwise the buy is the bot's own idea, and only the side that has
        // been rising gets to have it. Resting a bid on the falling side while
        // the book is level is precisely how the balance ends up in the leg
        // heading for zero — the trend walks the price down into that order,
        // fills it, and leaves the other leg further out of reach than before.
        if (!behind) {
            if (!lead) {
                existing?.let { cancel(it, "ждём сторону, которая пошла вверх") }
                return
            }
            if (existing == null && now < bookNow.nextSeedAtMs) return
        }
        if (ahead && price > settings.maxSeedPrice) return

        if (existing != null) {
            val samePrice = abs(existing.price - price) < market.tickSize / 2
            val sameSize = abs(existing.remaining - size) < 0.51
            if (samePrice && sameSize) return
            if (now < existing.placedAt + REPRICE_AFTER_MS) return
            cancel(existing, "перестановка")
        }
        if (!withinCaps(bookNow, side, price, size)) return

        val note = if (behind) {
            "добор пары под ${cents(PairMath.maxPairCost(settings.minPairProfitPct, settings.maxPairAvg))}"
        } else if (cheap) {
            "набор дешёвой стороны"
        } else {
            "набор второй стороны"
        }
        place(market, side, "BUY", price, size, note)
        if (existing == null && !behind) scheduleNextSeed(bookNow, now)
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
    private fun bidFor(
        bookNow: PairBook,
        market: Market,
        side: String,
        urgent: Boolean,
    ): Double? {
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
        // Anchor to where the price has actually been this window. A bid set
        // only from the budget either sits under everything that traded all
        // window or pays up for no reason; the cheapest offer seen is the one
        // price we know a buy could have been done at.
        val anchored = bookNow.track(side).lowAsk?.let { low ->
            PairMath.anchoredBid(low, settings.lowBiasCents, urgent)
        }

        val price = PairMath.snapDown(
            minOf(budgeted, ceiling, anchored ?: budgeted),
            market.tickSize,
        )
        return if (price >= market.tickSize) price else null
    }

    private fun scheduleNextSeed(bookNow: PairBook, now: Long) {
        val lo = settings.minIntervalSec.coerceAtLeast(1)
        val hi = settings.maxIntervalSec.coerceAtLeast(lo)
        val wait = lo + if (hi > lo) random.nextInt(hi - lo + 1) else 0
        bookNow.nextSeedAtMs = now + wait * 1000L
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
            if (action == "BUY" && price * size > paperCash + 1e-9) {
                log(
                    "warn",
                    "[ТЕСТ] Не хватает баланса на $action $side: нужно " +
                        String.format("%.2f", price * size) + " $, есть " +
                        String.format("%.2f", paperCash) + " $",
                )
                return
            }
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
        val matched = Orders.filled(action, result.makingAmount, result.takingAmount)
        if (matched.shares > 1e-9) {
            // It fills at the far side of the book, not at our limit, so take
            // the price the amounts actually imply.
            val actual = if (matched.usd > 0.0) matched.usd / matched.shares else price
            fill(order, min(matched.shares, size), actual, taker = true)
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

        val ledger = statsFor(order.dryRun)
        if (order.action == "BUY") {
            val cost = shares * price + fee
            leg.buy(shares, cost)
            bookNow.spentUsd += cost
            ledger.buys += 1
            if (order.dryRun) adjustPaperCash(-cost)
        } else {
            val proceeds = shares * price - fee
            leg.sell(shares)
            bookNow.proceedsUsd += proceeds
            ledger.sells += 1
            if (order.dryRun) adjustPaperCash(proceeds)
        }
        bookNow.feesUsd += fee
        ledger.feesUsd += fee
        store.saveStats(order.dryRun, ledger)

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

        // The lead cap applies here too: a rotation must not become the way one
        // side runs away from the other.
        val lot = lotFor(market, cheap = side == cheaperSide())
        val allowance = allowanceFor(bookNow, side, lot)
        if (allowance < market.minimumOrderSize) return

        val size = min(maxOf(shares, market.minimumOrderSize), allowance)
        // A recycle is filling the gap a sale just opened, so it is urgent.
        val price = bidFor(bookNow, market, side, urgent = true) ?: return
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

        val ledger = statsFor(bookNow.dryRun)
        ledger.windows += 1
        ledger.pairsLocked += bookNow.pairs
        ledger.realisedPnlUsd += pnl
        // Settlement pays out in cash: the paper account has to receive it or
        // the balance would only ever go down.
        if (bookNow.dryRun && winner != null) {
            adjustPaperCash(PairMath.settlementProceeds(bookNow.up, bookNow.down, winner))
        }
        store.saveStats(bookNow.dryRun, ledger)

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
