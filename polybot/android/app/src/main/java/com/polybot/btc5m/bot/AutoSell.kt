package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
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
 * A standing sell order on everything held.
 *
 * Shares are not sellable the instant a buy matches — the venue rejects a sell
 * that arrives too early — so this cannot be a one-shot. It sweeps every open
 * position on a timer and keeps trying until each one is covered by a resting
 * sell at the chosen price, which is what makes it useful for hand trading: buy
 * whenever, and the exit is already arranged.
 *
 * It is deliberately idempotent. Each sweep works out how much of a position is
 * *not* yet covered by a live sell and only asks for the difference, so a slow
 * or duplicated sweep cannot stack two orders on the same shares.
 */
class AutoSell(
    private val engine: BotEngine,
    /**
     * Shares of an outcome a running bot is holding right now.
     *
     * Those are left alone: the terminal bot's ladder and the pair bot's legs
     * are part of strategies that decide their own exits, and blanketing them
     * with a sell at one price would break both. Everything else in the same
     * position is the user's and gets sold — the wallet is shared, so a
     * market-wide skip would silently ignore a hand-placed buy for as long as a
     * bot happened to be trading that window.
     */
    private val botShares: (String) -> Double,
    private val onStateChanged: () -> Unit,
) {
    data class Settings(
        val enabled: Boolean = false,
        /** Sell price by minute of the window, cheapest rung first. */
        val ladder: List<Double> = SellLadder.DEFAULT,
        /** How often to try again while the venue is still refusing. */
        val retryEverySec: Int = 7,
        /** How long to keep trying on one purchase before giving up. */
        val watchSec: Int = 60,
        /** How far ahead of each minute the next rung takes over. */
        val ladderLeadSec: Int = SellLadder.DEFAULT_LEAD_SEC,
        /** Buy the same size back if the price falls far enough after a sale. */
        val rebuyEnabled: Boolean = false,
        /** How far below the sale price the buy-back triggers, as a fraction. */
        val rebuyDropPct: Double = 0.20,
        /** Pause between buy-back slices, so a deeper dip can still be caught. */
        val rebuySlicePauseSec: Int = 3,
        /** Price off what the position cost instead of off the clock. */
        val percentMode: Boolean = false,
        /** The margin to hold out for, net of the fee. */
        val profitPct: Double = SellPercent.DEFAULT_GAIN,
        /** Seconds between slices when a position was built out of several buys. */
        val sliceGapSec: Int = SellPercent.DEFAULT_SLICE_GAP_SEC,
        /** Inside this much of the close, any profit will do. */
        val panicSec: Int = SellPercent.DEFAULT_PANIC_SEC,
    )

    /** One position and what the rule has managed to do about it. */
    data class Row(
        val asset: String,
        val title: String,
        val outcome: String,
        val size: Double,
        val resting: Double,
        val restingPrice: Double?,
        val status: String,
        val attempts: Int,
        /** When the last attempt on this position ran, and what came back. */
        val lastTryAt: Long,
        val lastError: String?,
        /** Rung the ladder is on for this position, and its price. */
        val step: Int,
        val target: Double,
    )

    @Volatile
    var settings: Settings = Settings()
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastSweepAt: Long = 0
        private set

    /** Why the last sweep could not run at all, if it could not. */
    @Volatile
    var lastFault: String? = null
        private set

    /** How many purchases are still being chased. */
    val watchingCount: Int get() = watching.size

    val rows = CopyOnWriteArrayList<Row>()

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val attempts = HashMap<String, Int>()
    private val lastTry = HashMap<String, Long>()
    private val lastError = HashMap<String, String?>()
    private val metaCache = HashMap<String, Pair<Long, ClobApi.MarketMeta>>()

    /** Per-outcome ladder state, reset when the window rolls. */
    private data class Rung(val windowStart: Long, var highWater: Double, var step: Int)

    private val rungs = HashMap<String, Rung>()

    /** When the last percent-mode slice went out, per outcome. */
    private val lastSlice = HashMap<String, Long>()

    /**
     * Trades already turned into buy-backs, by transaction. The venue can only
     * be asked what trades happened, not what is new since last time.
     */
    private val seenTrades = HashSet<String>()

    @Volatile
    private var tradesSeeded = false

    /**
     * Outcomes bought recently, with the moment to stop chasing them.
     *
     * The rule used to sweep on a timer forever, which meant polling the data
     * API every few seconds around the clock whether or not anything had been
     * bought — enough to earn a 429 and stop working altogether. A sell is only
     * ever needed just after a purchase, so that is the only time it looks.
     */
    private val watching = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Sold shares waiting for a cheap enough price to be bought back.
     *
     * Bought back in slices the size of the original clips, with a pause
     * between them: taking the whole size at the first price that clears the
     * trigger throws away the rest of the dip.
     */
    data class Rebuy(
        val asset: String,
        val conditionId: String,
        val shares: Double,
        val soldAt: Double,
        val trigger: Double,
        val windowStart: Long,
        val lot: Double,
        var remaining: Double,
        var nextAtMs: Long = 0L,
        /** Why it is still waiting, shown on the desk. */
        var note: String? = null,
        /** Last price seen, and when — so the desk can show it is alive. */
        var lastAsk: Double? = null,
        var lastCheckAt: Long = 0L,
        var checks: Int = 0,
        /** Cheapest the offer has been since the sale; how close it ever came. */
        var bestAsk: Double? = null,
    )

    /** A buy-back that is over, kept so the desk can say how it ended. */
    data class RebuyDone(
        val outcome: String,
        val shares: Double,
        val soldAt: Double,
        val trigger: Double,
        val bestAsk: Double?,
        val result: String,
        val at: Long,
    )

    val recentRebuys = CopyOnWriteArrayList<RebuyDone>()

    val rebuys = CopyOnWriteArrayList<Rebuy>()

    @Volatile
    private var sweepRequested = false

    private companion object {
        const val META_TTL_MS = 60_000L

        /** How long to sit still when there is nothing to watch. */
        const val IDLE_MS = 5_000L

        /** How often to check the price while a buy-back is waiting for a dip. */
        const val REBUY_POLL_MS = 2_000L
    }

    /**
     * Something was just bought: watch it until it is covered, or until the
     * window of attention runs out.
     */
    fun watch(asset: String) {
        if (asset.isEmpty()) return
        watching[asset] = System.currentTimeMillis() + settings.watchSec.coerceAtLeast(5) * 1000L
        sweepRequested = true
    }

    /**
     * Idempotent on purpose. The UI re-sends the settings whenever it suspects
     * the service may have been restarted, and a naive stop-then-start on every
     * push made the rule flap — the log filled with "off/on" pairs and each
     * restart threw away the watch it was in the middle of.
     */
    fun update(next: Settings) {
        settings = next
        // The buy-back needs the loop as much as the sell ladder does — it is
        // how a filled sell gets noticed at all. Tying the loop to the ladder
        // alone left "buy-back on, ladder off" as a switch that did nothing.
        val shouldRun = next.enabled || next.rebuyEnabled
        when {
            shouldRun && !running -> start()
            !shouldRun && running -> stop()
            else -> onStateChanged()
        }
    }

    fun start() {
        if (running) return
        running = true
        // Anything already held when the rule is switched on gets one window of
        // attention too, or a position opened before the app was would never be
        // covered.
        sweepRequested = true

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            var backoffMs = 0L
            var lastFullMs = 0L
            while (isActive && running) {
                val now = System.currentTimeMillis()
                val nowSec = Clock.nowSec()

                // Watching a price is one small request; a full sweep asks the
                // data API for positions and trades. Running the second at the
                // cadence the first needs is what earns a rate limit, so they
                // are paced apart.
                if (rebuys.isNotEmpty()) {
                    try {
                        runRebuys()
                    } catch (e: Exception) {
                        lastFault = e.message ?: "сбой докупа"
                    }
                }

                val windowNow = nowSec - SellLadder.elapsedInWindow(nowSec)
                val busy = sweepRequested ||
                    watching.isNotEmpty() ||
                    rebuys.isNotEmpty() ||
                    OrderLog.hasWorkingSells(windowNow) ||
                    OrderLog.hasWorkingBuys(windowNow)
                val due = now - lastFullMs >= settings.retryEverySec.coerceAtLeast(1) * 1000L

                if (busy && due && backoffMs <= 0L) {
                    lastFullMs = now
                    try {
                        sweep()
                        backoffMs = 0L
                    } catch (e: Exception) {
                        lastFault = e.message ?: "сбой обхода"
                        engine.log("error", "Автопродажа: ${e.message}")
                        onStateChanged()
                        // A rate limit answered at the same cadence stays a rate
                        // limit; back off and let it clear.
                        backoffMs = if (backoffMs == 0L) 15_000L else minOf(backoffMs * 2, 120_000L)
                    }
                } else if (backoffMs > 0L && now - lastFullMs >= backoffMs) {
                    backoffMs = 0L
                    lastFullMs = 0L
                }

                delay(
                    when {
                        // A dip can come and go inside one retry interval, so a
                        // pending buy-back keeps the loop ticking fast — but only
                        // the price check runs at that pace.
                        rebuys.isNotEmpty() -> REBUY_POLL_MS
                        busy -> 1_000L
                        else -> IDLE_MS
                    },
                )
            }
        }

        engine.log(
            "info",
            if (!settings.enabled) {
                "Автодокуп включён: следим за исполнением продаж"
            } else "Автопродажа лесенкой " +
                settings.ladder.joinToString("/") { "${(it * 100).toInt()}" } +
                "¢: ${settings.watchSec} с после покупки, повтор каждые " +
                "${settings.retryEverySec} с",
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
        attempts.clear()
        rungs.clear()
        lastSlice.clear()
        rebuys.clear()
        recentRebuys.clear()
        watching.clear()
        seenTrades.clear()
        tradesSeeded = false
        engine.log("info", "Автопродажа выключена")
        onStateChanged()
    }

    private fun metaFor(conditionId: String): ClobApi.MarketMeta? {
        val now = System.currentTimeMillis()
        metaCache[conditionId]?.let { (at, meta) ->
            if (now - at < META_TTL_MS) return meta
        }
        return try {
            ClobApi.marketMeta(conditionId).also { metaCache[conditionId] = now to it }
        } catch (e: Exception) {
            null
        }
    }

    private fun sweep() {
        // A one-shot pass, requested when the rule is switched on, looks at
        // everything held. Ordinary passes look only at what was just bought.
        val fullSweep = sweepRequested
        sweepRequested = false
        expireWatches()

        val session = engine.session()
        if (session == null) {
            lastFault = "кошелёк не подключён"
            rows.clear()
            onStateChanged()
            return
        }

        val open = ClobApi.openOrders(session.creds, session.account.signerAddress)
        val nowMs = System.currentTimeMillis()
        val nowSec = Clock.nowSec()

        // Fills and buy-backs read the exchange, not the data API. Doing them
        // first means a rate-limited position lookup — which is a different
        // host with its own limits — can no longer take them down with it. That
        // is exactly what happened: every 429 on positions threw before either
        // of these ran, so sales went unnoticed and buy-backs never triggered.
        OrderLog.reconcile(open) { id ->
            ClobApi.order(session.creds, session.account.signerAddress, id)
        }
        noteFills(session, nowSec - SellLadder.elapsedInWindow(nowSec))
        runRebuys()

        val positions = try {
            if (settings.enabled) DataApi.positions(session.account.funderAddress) else emptyList()
        } catch (e: Exception) {
            lastFault = e.message ?: "позиции недоступны"
            lastSweepAt = nowMs
            onStateChanged()
            throw e
        }
        val now = Clock.nowSec()
        val windowStart = now - SellLadder.elapsedInWindow(now)
        lastSweepAt = System.currentTimeMillis()
        lastFault = null

        val next = ArrayList<Row>()
        // With the ladder off, the loop is only here for the buy-back; it must
        // not start selling on its own.
        for (position in if (settings.enabled) positions else emptyList()) {
            if (position.redeemable || position.size <= 0.0) continue
            if (!fullSweep && !watching.containsKey(position.asset)) continue

            // Only the part of the position no bot is holding.
            val mine = position.size - botShares(position.asset)

            val meta = metaFor(position.conditionId)

            // The ladder counts minutes of *this position's* window, not of
            // whatever window the clock is in. Buying into the next window
            // before it opens used to read the current window's elapsed time
            // and start four rungs up.
            val rung = trackRung(position, meta?.windowStart ?: windowStart, now)
            val ladderTarget = settings.ladder.getOrElse(rung.step) { settings.ladder.last() }

            // In percent mode the price comes from what the position cost, so
            // it is worked out per position rather than per minute. Null means
            // the cost is not known yet, and a price cannot be invented.
            val percentPrice = if (settings.percentMode && meta != null) {
                percentTarget(position, open, meta, windowStart)
            } else {
                null
            }
            val target = if (settings.percentMode) percentPrice ?: 0.0 else ladderTarget

            val status = when {
                meta == null -> "нет данных рынка"
                meta.closed || !meta.acceptingOrders -> "рынок закрыт"
                mine < meta.minimumOrderSize -> "у бота"
                // Waiting for the average, not failing: data-api reports a fresh
                // position with its size already right and its cost basis still
                // at zero, and pricing a margin off zero asks a cent.
                settings.percentMode && percentPrice == null -> "ждём среднюю цену"
                settings.percentMode ->
                    reconcilePercent(position, open, meta, percentPrice!!, mine)
                else -> reconcile(position, open, meta, target, mine)
            }
            // Covered, settled, or the bot's: nothing left to chase here.
            if (status == "покрыто" || status == "рынок закрыт" || status == "у бота") {
                watching.remove(position.asset)
            } else if (fullSweep) {
                // Found on the opening pass; give it the same window of
                // attention a fresh purchase would get.
                watching.putIfAbsent(
                    position.asset,
                    System.currentTimeMillis() + settings.watchSec.coerceAtLeast(5) * 1000L,
                )
            }
            next.add(rowFor(position, open, status, rung.step, target))
        }

        val live = next.map { it.asset }.toSet()
        attempts.keys.retainAll(live)
        rungs.keys.retainAll(live)
        lastSlice.keys.retainAll(live)
        lastTry.keys.retainAll(live)
        lastError.keys.retainAll(live)
        rows.clear()
        rows.addAll(next)
        onStateChanged()
    }

    /**
     * Stop chasing a purchase that has had its minute.
     *
     * Giving up is the point: a sell that has been refused for a minute is
     * being refused for a reason the next attempt will not change, and trying
     * forever is what turned this into a rate limit.
     */
    private fun expireWatches() {
        val now = System.currentTimeMillis()
        val done = watching.filterValues { it <= now }.keys
        for (asset in done) {
            // A buy still on the book is not a lost cause — there is nothing to
            // sell yet. Renew rather than give up, or a limit that takes longer
            // than the watch to fill would never be covered.
            if (OrderLog.hasWorkingBuy(asset)) {
                watching[asset] = now + settings.watchSec.coerceAtLeast(5) * 1000L
                continue
            }
            watching.remove(asset)
            val row = rows.firstOrNull { it.asset == asset }
            engine.log(
                "warn",
                "Автопродажа сдалась по ${row?.outcome ?: "позиции"} после " +
                    "${settings.watchSec} с" +
                    (row?.lastError?.let { ": $it" } ?: ""),
            )
        }
        if (done.isNotEmpty()) onStateChanged()
    }

    /**
     * Turn sells that have matched into pending buy-backs.
     *
     * The volume comes from the order log, which records every order the app
     * sends. The rule used to track only the sells it placed itself, so a limit
     * sell put on by hand — the ordinary way of taking profit here — filled
     * without the buy-back ever hearing about it.
     */
    private fun noteFills(session: BotEngine.Session, windowStart: Long) {
        val trades = try {
            DataApi.trades(session.account.funderAddress)
        } catch (e: Exception) {
            lastFault = e.message ?: "сделки недоступны"
            return
        }

        // The first pass only learns what already happened. Without it, turning
        // the rule on would queue a buy-back for every sale in recent history.
        if (!tradesSeeded) {
            trades.forEach { seenTrades.add(it.key) }
            tradesSeeded = true
            return
        }

        // Oldest first, so several fills of one order land in order.
        for (trade in trades.sortedBy { it.at }) {
            if (!seenTrades.add(trade.key)) continue

            val tick = metaFor(trade.conditionId)?.tickSize ?: 0.01
            OrderLog.applyTrade(trade.asset, trade.side, trade.price, trade.size, tick)

            // A buy that has actually happened gets its own window of attention,
            // whatever put it there: a limit that rested past the watch it was
            // given, a fill in slices, or a purchase made outside the app. This
            // is the only signal that is true after the fact.
            if (trade.side == "BUY") {
                if (settings.enabled) watch(trade.asset)
                continue
            }
            if (!settings.rebuyEnabled) continue

            val drop = settings.rebuyDropPct.coerceIn(0.0, 0.95)
            val lot = OrderLog.buyLotFor(trade.asset)?.coerceAtMost(trade.size) ?: trade.size
            rebuys.add(
                Rebuy(
                    asset = trade.asset,
                    conditionId = trade.conditionId,
                    shares = trade.size,
                    soldAt = trade.price,
                    trigger = trade.price * (1.0 - drop),
                    windowStart = windowStart,
                    lot = lot,
                    remaining = trade.size,
                ),
            )
            engine.log(
                "trade",
                "Продано " + String.format("%.1f", trade.size) + " по " +
                    "${(trade.price * 100).toInt()}¢ · докуп при " +
                    "${(trade.price * (1.0 - drop) * 100).toInt()}¢",
            )
        }

        // The key set only needs to outlive the newest page it can see.
        if (seenTrades.size > 400) {
            seenTrades.retainAll(trades.map { it.key }.toSet())
        }
        onStateChanged()
    }

    private fun runRebuys() {
        if (rebuys.isEmpty()) return
        if (!settings.rebuyEnabled) {
            rebuys.clear()
            return
        }

        val now = System.currentTimeMillis()
        val done = ArrayList<Rebuy>()

        for (rebuy in rebuys) {
            // Every branch below leaves a note. The previous version dropped out
            // silently on a missing quote, missing market data, or — worst — a
            // rejected order, so a buy-back that never happened looked exactly
            // like one that was still patiently waiting.
            if (now < rebuy.nextAtMs) continue

            // Just the price. The whole book was more than watching a price
            // needs, and pulling it on a timer is what got the request refused
            // at the moment the buy-back depended on it.
            val ask = try {
                ClobApi.bestAsk(rebuy.asset)
            } catch (e: Exception) {
                rebuy.note = "цена недоступна"
                continue
            }
            if (ask == null || ask <= 0.0) {
                rebuy.note = "нет предложений"
                continue
            }

            rebuy.lastAsk = ask
            rebuy.lastCheckAt = now
            rebuy.checks += 1
            rebuy.bestAsk = minOf(rebuy.bestAsk ?: ask, ask)
            if (ask > rebuy.trigger) {
                rebuy.note = null
                continue
            }

            val meta = metaFor(rebuy.conditionId)
            if (meta == null) {
                rebuy.note = "нет данных рынка"
                continue
            }
            if (meta.closed || !meta.acceptingOrders) {
                engine.log(
                    "warn",
                    "Автодокуп отменён: рынок закрылся раньше, чем цена дошла до " +
                        "${(rebuy.trigger * 100).toInt()}¢",
                )
                finish(rebuy, "рынок закрылся")
                done.add(rebuy)
                continue
            }

            // Buying at a few cents, five shares is well under the venue's
            // dollar floor and would simply be refused.
            val floor = Orders.minShares(ask, meta.minimumOrderSize)
            val slice = minOf(rebuy.lot, rebuy.remaining)
                .coerceAtLeast(floor)
                .coerceAtMost(maxOf(rebuy.remaining, floor))

            // Crossing rather than resting: this is meant to be taken now. Two
            // ticks through the offer covers the top of book moving between the
            // read and the send, and caps what it can pay.
            val limit = minOf(ask + meta.tickSize * 2, 1.0 - meta.tickSize)

            val result = try {
                engine.placeManualOrder(
                    tokenId = rebuy.asset,
                    conditionId = rebuy.conditionId,
                    side = "BUY",
                    price = limit,
                    size = slice,
                    orderType = "GTC",
                    auto = true,
                )
            } catch (e: Exception) {
                rebuy.note = e.message ?: "ошибка сети"
                engine.log("error", "Автодокуп не прошёл: ${e.message}")
                continue
            }

            if (!result.success) {
                val reason = result.error ?: "отказ CLOB"
                rebuy.note = reason
                engine.log("error", "Автодокуп отклонён: $reason")
                continue
            }

            rebuy.remaining -= slice
            // A pause before the next slice, so a dip that is still falling is
            // not all bought at its first price.
            rebuy.nextAtMs = now + settings.rebuySlicePauseSec.coerceAtLeast(1) * 1000L
            rebuy.note = null

            engine.log(
                "trade",
                "Автодокуп " + String.format("%.1f", slice) + " по " +
                    "${(ask * 100).toInt()}¢ (продано по ${(rebuy.soldAt * 100).toInt()}¢)" +
                    if (rebuy.remaining > 1e-9) {
                        ", осталось " + String.format("%.1f", rebuy.remaining)
                    } else {
                        ""
                    },
            )

            if (rebuy.remaining < meta.minimumOrderSize) {
                finish(rebuy, "куплено")
                done.add(rebuy)
            }
        }
        rebuys.removeAll(done)
        if (done.isNotEmpty()) onStateChanged()
    }

    private fun trackRung(position: Position, windowStart: Long, now: Long): Rung {
        val existing = rungs[position.asset]
        val rung = if (existing == null || existing.windowStart != windowStart) {
            Rung(windowStart, position.curPrice, 0).also { rungs[position.asset] = it }
        } else {
            existing
        }
        if (position.curPrice > rung.highWater) rung.highWater = position.curPrice
        rung.step = SellLadder.stepFor(
            // Negative before the window opens: the first rung, not the last.
            elapsedSec = (now - windowStart).coerceAtLeast(0L),
            highWater = rung.highWater,
            ladder = settings.ladder,
            floor = rung.step,
            leadSec = settings.ladderLeadSec,
        )
        return rung
    }

    /**
     * The price percent mode is asking for, right now.
     *
     * Off the position's own cost, raised a tick above whatever is already
     * resting — a slice that filled proves the market was there — and dropped
     * to what the book will pay once the window is nearly out, provided that
     * still clears the cost after the fee.
     */
    private fun percentTarget(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        windowStart: Long,
    ): Double? {
        // Everything here is measured from what the position cost, so without
        // that number there is no price to ask — least of all the tick floor,
        // which is what solving for a margin over zero produces. The app's own
        // record of its fills covers the gap while data-api indexes the trade.
        val avgPrice = position.avgPrice.takeIf { it > 0.0 }
            ?: LocalFills.avgFor(position.asset)
            ?: return null

        val resting = open
            .filter { it.assetId == position.asset && it.side == "SELL" }
            .maxByOrNull { it.price }
            ?.price
        val closesAt = (meta.windowStart.takeIf { it > 0 } ?: windowStart) + WINDOW_SECONDS
        val secondsLeft = closesAt - Clock.nowSec()

        // The book is only worth a request when the answer can change what we
        // do — that is, in the last minute.
        val bid = if (!SellPercent.holdingOut(secondsLeft, settings.panicSec)) {
            try {
                ClobApi.bestBid(position.asset)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return SellPercent.priceFor(
            avgPrice = avgPrice,
            gain = settings.profitPct,
            tick = meta.tickSize,
            resting = resting,
            secondsLeft = secondsLeft,
            panicSec = settings.panicSec,
            bestBid = bid,
        )
    }

    /**
     * Offer one slice at a time, a few seconds apart.
     *
     * Unlike the ladder, a resting sell here is never stale: it was placed at a
     * price the position was worth then, and the next slice goes above it
     * rather than replacing it. The one exception is the last minute, where
     * everything left is repriced onto what the book will actually pay.
     */
    private fun reconcilePercent(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        target: Double,
        mine: Double,
    ): String {
        val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
        val covered = sells.sumOf { it.remaining }
        val uncovered = mine - covered

        val closesAt = (meta.windowStart.takeIf { it > 0 } ?: 0L) + WINDOW_SECONDS
        val lastMinute = closesAt > 0 &&
            !SellPercent.holdingOut(closesAt - Clock.nowSec(), settings.panicSec)

        // Out of time: whatever is resting above what the book pays will not
        // fill, so it is pulled and re-offered at a price that will.
        if (lastMinute && sells.any { it.price > target + meta.tickSize / 2 }) {
            val session = engine.session() ?: return "нет сессии"
            for (order in sells.filter { it.price > target + meta.tickSize / 2 }) {
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, order.id)
                } catch (e: Exception) {
                    return e.message ?: "не снять старый ордер"
                }
            }
            return tryPlace(position, mine, target)
        }

        if (uncovered < meta.minimumOrderSize) return "покрыто"

        // A pause between slices is the whole point: it gives the price room to
        // carry the next one higher.
        val now = System.currentTimeMillis()
        val since = now - (lastSlice[position.asset] ?: 0L)
        if (covered > 0.0 && since < settings.sliceGapSec.coerceAtLeast(0) * 1000L) {
            return "ждёт шага"
        }

        val slice = SellPercent.sliceSize(
            uncovered = uncovered,
            lot = OrderLog.buyLotFor(position.asset),
            minimum = meta.minimumOrderSize,
        )
        val status = tryPlace(position, slice, target)
        if (status == "выставлено") lastSlice[position.asset] = now
        return status
    }

    /**
     * Bring this position's resting sell in line with the rung.
     *
     * A sell left at a rung the ladder has moved past would quietly cap the
     * position at yesterday's price, so it is pulled and replaced. Everything
     * else is a top-up: only the shares not already covered are asked for, so a
     * slow or repeated sweep cannot stack two orders on the same shares.
     */
    private fun reconcile(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        target: Double,
        mine: Double,
    ): String {
        val price = snapToTick(target, meta.tickSize)
        val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
        val stale = sells.filter { abs(it.price - price) > meta.tickSize / 2 }

        if (stale.isNotEmpty()) {
            val session = engine.session() ?: return "нет сессии"
            for (order in stale) {
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, order.id)
                } catch (e: Exception) {
                    return e.message ?: "не снять старый ордер"
                }
            }
        }

        // Only orders already at the target count as cover; the stale ones were
        // just pulled. Replacing in the same pass matters — waiting for the next
        // sweep would leave the position naked for a whole retry interval.
        val covered = sells.filter { abs(it.price - price) <= meta.tickSize / 2 }
            .sumOf { it.remaining }
        val uncovered = mine - covered
        if (uncovered < meta.minimumOrderSize) return "покрыто"

        return tryPlace(position, uncovered, price)
    }

    /**
     * File how a buy-back ended, newest first.
     *
     * "It did not happen" is only useful next to why, and by the time the user
     * looks the pending entry is gone — so the ending is kept rather than the
     * absence of one.
     */
    private fun finish(rebuy: Rebuy, result: String) {
        recentRebuys.add(
            0,
            RebuyDone(
                outcome = rows.firstOrNull { it.asset == rebuy.asset }?.outcome
                    ?: OrderLog.forWindow(rebuy.windowStart)
                        .firstOrNull { it.asset == rebuy.asset }?.outcome
                    ?: "",
                shares = rebuy.shares,
                soldAt = rebuy.soldAt,
                trigger = rebuy.trigger,
                bestAsk = rebuy.bestAsk,
                result = result,
                at = System.currentTimeMillis(),
            ),
        )
        while (recentRebuys.size > 8) recentRebuys.removeAt(recentRebuys.size - 1)
    }

    private fun rowFor(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        status: String,
        step: Int,
        target: Double,
    ): Row {
        val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
        return Row(
            asset = position.asset,
            title = position.title,
            outcome = position.outcome,
            size = position.size,
            resting = sells.sumOf { it.remaining },
            restingPrice = sells.firstOrNull()?.price,
            status = status,
            attempts = attempts[position.asset] ?: 0,
            lastTryAt = lastTry[position.asset] ?: 0L,
            lastError = lastError[position.asset],
            step = step,
            target = target,
        )
    }

    private fun tryPlace(position: Position, size: Double, price: Double): String {
        attempts[position.asset] = (attempts[position.asset] ?: 0) + 1
        lastTry[position.asset] = System.currentTimeMillis()

        return try {
            val result = engine.placeManualOrder(
                tokenId = position.asset,
                conditionId = position.conditionId,
                side = "SELL",
                price = price,
                size = size,
                orderType = "GTC",
                auto = true,
            )
            if (result.success) {
                attempts.remove(position.asset)
                lastError.remove(position.asset)
                "выставлено"
            } else {
                // Almost always "shares not sellable yet"; the next sweep retries.
                val reason = result.error ?: "отказ CLOB"
                lastError[position.asset] = reason
                reason
            }
        } catch (e: Exception) {
            val reason = e.message ?: "ошибка сети"
            lastError[position.asset] = reason
            reason
        }
    }

    /** A sell must never round down onto a worse price than asked for. */
    private fun snapToTick(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = kotlin.math.ceil(price / tick - 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(tick, 1.0 - tick)
    }
}
