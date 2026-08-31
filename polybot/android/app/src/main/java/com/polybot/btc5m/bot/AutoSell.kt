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
     * Shares of an outcome a bot is holding right now.
     *
     * Those are left alone: the bot arranges its own exit at its own rung, and
     * blanketing them from here would mean two rules cancelling each other's
     * orders. Everything else in the same position is the user's and gets sold
     * — the wallet is shared, so a market-wide skip would silently ignore a
     * hand-placed buy for as long as a bot happened to be trading that window.
     */
    private val botShares: (String) -> Double,
    private val onStateChanged: () -> Unit,
) {
    data class Settings(
        val enabled: Boolean = false,
        /** Sell price by minute of the window, cheapest rung first. */
        val ladder: List<Double> = SellLadder.HALF_MINUTE,
        /** How often to try again while the venue is still refusing. */
        val retryEverySec: Int = 7,
        /** How long to keep trying on one purchase before giving up. */
        val watchSec: Int = 60,
        /**
         * Stop the ladder climbing over a side the book once wrote off.
         *
         * A side that traded under a third has been given up on by the
         * market, and if it comes back it comes back late. The ladder mean-
         * while has walked up with the clock and is asking ninety-six by the
         * fourth minute, so the price the recovery actually reaches is one
         * nothing is offered at. With this on, such a position asks the first
         * rung for the whole window and ninety-three in the last half minute.
         */
        val dipRescue: Boolean = true,
        /**
         * Whether a fill makes a sound.
         *
         * The window is decided while the phone is in a pocket, so a cue in
         * the headphones is the only way to know what happened when it
         * happened. Off is for when the phone is not in a pocket.
         */
        val chime: Boolean = true,
        /** How far ahead of each boundary the next rung takes over. */
        val ladderLeadSec: Int = SellLadder.DEFAULT_LEAD_SEC,
        /**
         * How long each rung holds before the clock moves on.
         *
         * Half a minute: five rungs spent by the halfway mark, which asks the
         * higher prices while there is still time for the market to reach
         * them, and holds the top one for the rest of the window.
         */
        val ladderStepSec: Long = 30L,
        /** Price off what the position cost instead of off the clock. */
        val percentMode: Boolean = false,
        /** The margin to hold out for, net of the fee. */
        val profitPct: Double = SellPercent.DEFAULT_GAIN,
        /** Seconds between slices when a position was built out of several buys. */
        val sliceGapSec: Int = SellPercent.DEFAULT_SLICE_GAP_SEC,
        /** Inside this much of the close, the floor below replaces the margin. */
        val panicSec: Int = SellPercent.DEFAULT_PANIC_SEC,
        /** The least the last minute will sell for. */
        val closeFloor: Double = SellPercent.DEFAULT_CLOSE_FLOOR,
        /** The least the stretch before that will sell for. */
        val lateFloor: Double = SellPercent.DEFAULT_LATE_FLOOR,
        /** How long that stretch runs, ending where the last minute begins. */
        val lateBandSec: Int = SellPercent.DEFAULT_LATE_BAND_SEC,
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
    private data class Rung(
        val windowStart: Long,
        var highWater: Double,
        /** And the worst it has been bid, which is what arms the rescue. */
        var lowWater: Double,
        var step: Int,
        /**
         * Whether the bid has ever given back six cents of its best. Until it
         * has, the move is one clean run and the ask is held at ninety rather
         * than at whatever rung the clock has reached.
         */
    )

    private val rungs = HashMap<String, Rung>()

    /** When the last percent-mode slice went out, per outcome. */
    private val lastSlice = HashMap<String, Long>()

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

        /** Pass interval while a fresh purchase is still waiting for its exit. */
        const val CHASE_GAP_MS = 1_000L

        /** How often to look at the balance while a sale's money is awaited. */
        const val CASH_PROBE_MS = 2_000L

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
        Chime.on = next.chime
        settings = next
        val shouldRun = next.enabled
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
            var lastCashProbeMs = 0L
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

                // Timing how long a sale's money takes to become spendable
                // needs the balance looked at more often than the desk's own
                // half-minute poll. It runs only while a sale is being timed,
                // and stops for good once a couple of sales have been.
                if (Timings.cashPending() && now - lastCashProbeMs >= CASH_PROBE_MS) {
                    lastCashProbeMs = now
                    try {
                        Timings.balanceRead(engine.usdcBalance(), System.currentTimeMillis())
                    } catch (e: Exception) {
                        // A missed reading only costs this sale's measurement.
                    }
                }

                val windowNow = nowSec - SellLadder.elapsedInWindow(nowSec)
                val busy = sweepRequested ||
                    watching.isNotEmpty() ||
                    rebuys.isNotEmpty() ||
                    // A trade the desk's own poll saw first and nobody has acted
                    // on yet. Without this the loop could sleep through the one
                    // fill a buy-back exists to answer.
                    TradeSync.hasFresh() ||
                    Timings.cashPending() ||
                    OrderLog.hasWorkingSells(windowNow) ||
                    OrderLog.hasWorkingBuys(windowNow) ||
                    // A purchase with no exit arranged is unfinished business,
                    // and stays unfinished until its market closes. This is what
                    // makes "every buy gets a sell" a guarantee rather than a
                    // hope: it is read from the app's own log, so it survives a
                    // refusal, a rate limit and an unindexed trade alike.
                    (settings.enabled && OrderLog.hasUncovered(windowNow))
                // A purchase with no exit yet is chased at the pace of the
                // thing being waited for, not at the retry interval. The
                // retry interval is for a venue that keeps refusing; here the
                // shares become sellable at a moment the app has measured, and
                // a seven-second sweep would sit on that moment for six of
                // them. It lasts only until the sell is placed, because a
                // covered position is no longer uncovered.
                val chasing = watching.isNotEmpty() &&
                    (Timings.measuring() || OrderLog.hasUncovered(windowNow))
                val gapMs = if (chasing) {
                    CHASE_GAP_MS
                } else {
                    settings.retryEverySec.coerceAtLeast(1) * 1000L
                }
                val due = now - lastFullMs >= gapMs

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
                        Timings.cashPending() -> CASH_PROBE_MS
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
        TradeSync.reset()
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

        // Positions the log says are still the rule's business: a purchase with
        // no exit yet, or an offer of ours still on the book. Both are looked at
        // on every pass, watch or no watch — the watch is a timer, and a timer
        // is exactly what let a blocked sell be forgotten. The resting offer
        // matters just as much: a floor that comes into force while it sits
        // there has to be able to reach it.
        val windowNow = nowSec - SellLadder.elapsedInWindow(nowSec)
        val pending = if (settings.enabled) {
            OrderLog.uncovered(windowNow).keys + OrderLog.workingAssets("SELL", windowNow)
        } else {
            emptySet()
        }

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
            if (!fullSweep &&
                !watching.containsKey(position.asset) &&
                position.asset !in pending
            ) {
                continue
            }

            // Only the part of the position no bot is holding.
            val mine = position.size - botShares(position.asset)

            val meta = metaFor(position.conditionId)

            // The ladder counts minutes of *this position's* window, not of
            // whatever window the clock is in. Buying into the next window
            // before it opens used to read the current window's elapsed time
            // and start four rungs up.
            val rung = trackRung(position, meta?.windowStart ?: windowStart, now)
            // A side the book once wrote off does not climb with the clock:
            // it asks the first rung all window and ninety-three at the end,
            // because the price its recovery reaches is not the price a
            // steady winner would have walked up to by now.

            // The venue locks freshly bought shares, and how long for is
            // something the app has measured rather than something the
            // settings guess at. Once it knows, the first offer waits for that
            // moment instead of firing a refusal at the exchange every few
            // seconds from the instant of purchase. Zero while nothing has
            // been measured — being refused is how the measurement is taken.
            val lotAt = OrderLog.uncoveredLots(position.asset).firstOrNull()?.at ?: 0L
            val closesAt = (meta?.windowStart?.takeIf { it > 0 } ?: windowStart) + WINDOW_SECONDS
            val ladderTarget =
                if (settings.dipRescue && SellLadder.dipped(rung.lowWater)) {
                    SellLadder.afterDip(settings.ladder, closesAt - now)
                } else {
                    settings.ladder.getOrElse(rung.step) { settings.ladder.last() }
                }
            val hold = if (meta == null || closesAt - now <= settings.panicSec) {
                // Near the close there is no time to be patient with.
                0L
            } else {
                Timings.holdMs(lotAt, nowMs)
            }

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
                // Too small for the venue to take an order for. Named rather
                // than skipped: a position dropped out of the sweep for being
                // awkward is a position that never gets an exit.
                mine < meta.minimumOrderSize - 1e-6 ->
                    "меньше минимума " + String.format("%.1f", meta.minimumOrderSize)
                hold > 0L -> "жду ${(hold + 999) / 1000} с по замеру"
                settings.percentMode ->
                    reconcilePercent(position, open, meta, percentPrice, mine)
                // The rung is an offer standing on the book for the whole
                // window rather than a price something has to be awake to
                // take. A bid that jumps clean past it still pays the rung —
                // that is what resting costs — and what it buys is a sale
                // that happens whether or not anything is watching that
                // second, which on a phone is most of them.
                else -> reconcile(position, open, meta, target, mine, lotAt)
            }
            // Covered, settled, or the bot's: nothing left to chase here.
            if (status == "покрыто" || status == "рынок закрыт") {
                watching.remove(position.asset)
            } else {
                // Anything else is unfinished, and the attention renews until
                // it is finished or the market closes. A minute's grace from
                // the moment of purchase was the whole bug: whatever blocked
                // the sell for that minute blocked it forever.
                watching[position.asset] =
                    System.currentTimeMillis() + settings.watchSec.coerceAtLeast(5) * 1000L
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
        // Reading the feed and folding it into the log is shared with the desk,
        // which does the same on its own timer — otherwise a sale that filled
        // while the rule was off or asleep was never written down anywhere.
        TradeSync.poll(session.account.funderAddress, minGapMs = 3_000L)
        TradeSync.lastFault?.let { lastFault = it }

        for (trade in TradeSync.drain()) {
            // A buy that has actually happened gets its own window of attention,
            // whatever put it there: a limit that rested past the watch it was
            // given, a fill in slices, or a purchase made outside the app. This
            // is the only signal that is true after the fact.
            if (trade.side == "BUY") {
                if (settings.enabled) watch(trade.asset)
                continue
            }
            // A sale is the end of the trade, not the start of the next one.
            //
            // Every sale used to queue a buy-back of the same side at a fifth
            // under what it sold for, and it fired on the rule's own sales as
            // well as on anything sold by hand — so a window the trend rule
            // had bought once, sold at seventy-five and finished with was
            // bought again at fifty-one three minutes later, by this. The
            // rule's own top-ups and its own buy-back are already gone; this
            // was the same thing under a different roof, and it is the reason
            // a window kept getting a second entry after both of those had
            // been removed.
            engine.log(
                "trade",
                "Продано " + String.format("%.1f", trade.size) + " по " +
                    "${(trade.price * 100).toInt()}¢",
            )
        }
        onStateChanged()
    }

    /**
     * Nothing queues a buy-back any more, so this only ever clears up.
     *
     * The queue and the machinery under it are left where they are rather
     * than picked out of a file this size in one go; with nothing adding to
     * [rebuys] none of it runs. It goes on the next pass through here.
     */
    private fun runRebuys() {
        if (rebuys.isEmpty()) return
        rebuys.clear()
        if (true) return

        val now = System.currentTimeMillis()
        val done = ArrayList<Rebuy>()

        for (rebuy in rebuys) {
            // Every branch below leaves a note. The previous version dropped out
            // silently on a missing quote, missing market data, or — worst — a
            // rejected order, so a buy-back that never happened looked exactly
            // like one that was still patiently waiting.
            if (now < rebuy.nextAtMs) continue

            // A buy-back belongs to the window it sold in, and dies with it: the
            // next five minutes are a different market with different tokens,
            // and there is nothing there to buy back. Judged by the clock, so it
            // costs no request and cannot be held up by one — the card used to
            // sit on a dead window forever, repeating the same numbers, because
            // the price lookup failed first and the closed-market check below
            // was never reached.
            if (Clock.nowSec() >= rebuy.windowStart + WINDOW_SECONDS) {
                engine.log(
                    "warn",
                    "Автодокуп отменён: окно закончилось раньше, чем цена дошла до " +
                        "${(rebuy.trigger * 100).toInt()}¢",
                )
                finish(rebuy, "окно закончилось")
                done.add(rebuy)
                continue
            }

            // Then the market itself, before anything that can fail: a closed
            // market is a finished buy-back, not a temporary problem.
            val meta = metaFor(rebuy.conditionId)
            if (meta != null && (meta.closed || !meta.acceptingOrders)) {
                engine.log(
                    "warn",
                    "Автодокуп отменён: рынок закрылся раньше, чем цена дошла до " +
                        "${(rebuy.trigger * 100).toInt()}¢",
                )
                finish(rebuy, "рынок закрылся")
                done.add(rebuy)
                continue
            }

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

            if (meta == null) {
                rebuy.note = "нет данных рынка"
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
            rebuy.nextAtMs = now + 3_000L
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
            Rung(windowStart, position.curPrice, position.curPrice, 0)
                .also { rungs[position.asset] = it }
        } else {
            existing
        }
        if (position.curPrice > rung.highWater) rung.highWater = position.curPrice
        if (position.curPrice > 0.0 && position.curPrice < rung.lowWater) {
            rung.lowWater = position.curPrice
        }
        rung.step = SellLadder.stepFor(
            // Negative before the window opens: the first rung, not the last.
            elapsedSec = (now - windowStart).coerceAtLeast(0L),
            highWater = rung.highWater,
            ladder = settings.ladder,
            floor = rung.step,
            leadSec = settings.ladderLeadSec,
            stepSec = settings.ladderStepSec,
        )
        return rung
    }

    /**
     * The price percent mode is asking for the next lot in line.
     *
     * Off that purchase's own cost — not the position's average. The average is
     * what no single purchase paid: pricing every exit off it put them all at
     * roughly one price, near the first buy's, which is too high for the cheap
     * lot and too low for the dear one.
     */
    private fun percentTarget(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        windowStart: Long,
    ): Double? {
        val lot = nextLot(position, open, meta)
        val closesAt = (meta.windowStart.takeIf { it > 0 } ?: windowStart) + WINDOW_SECONDS
        val secondsLeft = closesAt - Clock.nowSec()

        // With everything already offered there is no lot to price — but a
        // floor still has a price, and it is the one that says whether the
        // offer already on the book is too cheap to leave there.
        val floor = SellPercent.floorFor(
            secondsLeft = secondsLeft,
            panicSec = settings.panicSec,
            lateBandSec = settings.lateBandSec,
            closeFloor = settings.closeFloor,
            lateFloor = settings.lateFloor,
        )
        if (lot == null && floor == null) return null

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
            // Without a lot the margin is meaningless and the floor decides;
            // that case is only reached when a floor exists.
            avgPrice = lot?.price ?: 0.0,
            gain = settings.profitPct,
            tick = meta.tickSize,
            // Each lot carries its own price, so there is nothing to step over.
            resting = null,
            secondsLeft = secondsLeft,
            panicSec = settings.panicSec,
            bestBid = bid,
            closeFloor = settings.closeFloor,
            lateFloor = settings.lateFloor,
            lateBandSec = settings.lateBandSec,
        )
    }

    /**
     * The next purchase needing an exit: oldest first, capped by what is really
     * held.
     *
     * Falls back to the position as one lot at its average when the log knows
     * nothing about it — bought before this process started, or bought
     * elsewhere. An average is a poor price to sell at, but it beats not
     * selling.
     */
    private fun nextLot(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
    ): OrderLog.Lot? {
        val covered = open
            .filter { it.assetId == position.asset && it.side == "SELL" }
            .sumOf { it.remaining }
        val sellable = position.size - botShares(position.asset) - covered
        if (sellable < meta.minimumOrderSize - 1e-6) return null

        val lots = OrderLog.uncoveredLots(position.asset)
        val lot = lots.firstOrNull()
            ?: position.avgPrice.takeIf { it > 0.0 }?.let { OrderLog.Lot(sellable, it, 0L) }
            ?: return null

        // The log can believe in more shares than the wallet holds — a sale made
        // elsewhere, a fill counted twice — and an order for shares that are not
        // there is refused outright.
        return lot.copy(shares = minOf(lot.shares, sellable))
    }

    /**
     * Offer one purchase at a time, a few seconds apart.
     *
     * Unlike the ladder, a resting sell here is never stale: it was placed at
     * the price its own lot needs. The one exception is the last minute, where
     * an offer the book will never reach is pulled so the shares can go at what
     * it does pay.
     */
    private fun reconcilePercent(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        /** Null while the margin alone decides and no lot has a price yet. */
        target: Double?,
        mine: Double,
    ): String {
        val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
        val closesAt = (meta.windowStart.takeIf { it > 0 } ?: 0L) + WINDOW_SECONDS
        val secondsLeft = if (closesAt > 0) closesAt - Clock.nowSec() else Long.MAX_VALUE
        val lastMinute = !SellPercent.holdingOut(secondsLeft, settings.panicSec)

        // A floor applies to every lot at once, unlike a margin, which is why an
        // offer can go stale here at all: one placed before the floor took
        // effect is now under it. Too cheap gets pulled in either band; too high
        // to ever fill gets pulled only in the last minute.
        val floor = SellPercent.floorFor(
            secondsLeft = secondsLeft,
            panicSec = settings.panicSec,
            lateBandSec = settings.lateBandSec,
            closeFloor = settings.closeFloor,
            lateFloor = settings.lateFloor,
        )
        val stale = sells.filter {
            !held(it) &&
                ((floor != null && it.price < floor - meta.tickSize / 2) ||
                    (lastMinute && target != null && it.price > target + meta.tickSize / 2))
        }
        if (stale.isNotEmpty()) {
            val session = engine.session() ?: return "нет сессии"
            for (order in stale) {
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, order.id)
                } catch (e: Exception) {
                    return e.message ?: "не снять старый ордер"
                }
            }
            return "переставляю"
        }

        val lot = nextLot(position, open, meta) ?: return "покрыто"
        // A lot with no price is one whose cost is not known yet: data-api
        // reports a fresh position with its size already right and its basis
        // still at zero, and a margin over zero asks a single cent.
        if (target == null) return "ждём среднюю цену"

        // A pause between lots: the price has room to move, and the next lot is
        // priced off its own cost anyway.
        val now = System.currentTimeMillis()
        val since = now - (lastSlice[position.asset] ?: 0L)
        if (sells.isNotEmpty() && since < settings.sliceGapSec.coerceAtLeast(0) * 1000L) {
            return "ждёт шага"
        }

        val size = maxOf(lot.shares, meta.minimumOrderSize)
            .coerceAtMost(mine - sells.sumOf { it.remaining })
        if (size < meta.minimumOrderSize - 1e-6) return "покрыто"

        val status = tryPlace(position, size, target, lot.at)
        if (status == "выставлено") lastSlice[position.asset] = now
        return status
    }

    /**
     * Bring this position's resting sells in line with the rung.
     *
     * The first offer asks the rung; every further one sits a couple of cents
     * under the one before it. A position bought in two goes used to be
     * offered twice at the same price, which is one offer for twice the size
     * wearing two hats — the book fills the first and leaves the second
     * exactly where it was.
     *
     * A sell left at a rung the ladder has moved past would quietly cap the
     * position at yesterday's price, so anything not on its step is pulled and
     * replaced. Everything else is a top-up: only the shares not already
     * covered are asked for, so a slow or repeated sweep cannot stack two
     * orders on the same shares.
     */
    /**
     * Watches for the rung instead of sitting on it.
     *
     * A resting offer at the rung is a promise to sell at exactly that price:
     * when the book runs through it the fill comes back at the rung and the
     * rest of the move is somebody else's. So until the last minute nothing is
     * left on the book — the bid is read, and the moment it reaches the rung
     * the shares are sold into it at whatever it is paying. The rung becomes a
     * floor rather than a ceiling.
     *
     * Anything of ours already resting is pulled first: an offer left from the
     * last minute of a previous window, or from before this rule changed,
     * would fill at its own price and defeat the whole point. A price the user
     * pinned by hand is theirs and is left alone.
     */
    /** Crossing the book now, because the reason to wait has just gone. */
    private fun sellNow(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        mine: Double,
        lotAt: Long,
    ): String {
        val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
        val (pinned, ours) = sells.partition { held(it) }
        // A rung offer resting above the market is in the way of a sale that
        // is meant to happen at once.
        if (ours.isNotEmpty()) {
            val session = engine.session() ?: return "нет сессии"
            for (order in ours) {
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, order.id)
                } catch (e: Exception) {
                    return e.message ?: "не снять старый ордер"
                }
            }
        }
        val free = mine - pinned.sumOf { it.remaining }
        if (free < meta.minimumOrderSize - 1e-6) return "покрыто"
        val bid = try {
            ClobApi.bestBid(position.asset)
        } catch (e: Exception) {
            return "цена недоступна"
        } ?: return "цена недоступна"
        val limit = maxOf(meta.tickSize, snapToTick(bid - meta.tickSize, meta.tickSize))
        tryPlace(position, free, limit, lotAt)
        return "уровень — забираю"
    }

    private fun watchRung(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        rung: Double,
        mine: Double,
        lotAt: Long,
    ): String {
        val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
        val (pinned, ours) = sells.partition { held(it) }
        if (ours.isNotEmpty()) {
            val session = engine.session() ?: return "нет сессии"
            for (order in ours) {
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, order.id)
                } catch (e: Exception) {
                    return e.message ?: "не снять старый ордер"
                }
            }
            return "жду " + (rung * 100).toInt() + "¢"
        }

        val free = mine - pinned.sumOf { it.remaining }
        if (free < meta.minimumOrderSize - 1e-6) return "покрыто"

        val bid = try {
            ClobApi.bestBid(position.asset)
        } catch (e: Exception) {
            return "цена недоступна"
        }

        if (!SellLadder.reached(bid, rung)) {
            return "жду " + (rung * 100).toInt() + "¢"
        }

        // Crossing rather than resting: this is meant to be taken now. A tick
        // under the bid covers the top of book moving between the read and the
        // send without giving away more than one tick of it.
        val limit = maxOf(meta.tickSize, snapToTick(bid!! - meta.tickSize, meta.tickSize))
        return tryPlace(position, free, limit, lotAt)
    }

    private fun reconcile(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        meta: ClobApi.MarketMeta,
        target: Double,
        mine: Double,
        lotAt: Long,
    ): String {
        val base = snapToTick(target, meta.tickSize)
        // Best price first: that is the order the steps are counted in, and
        // the order the book will reach them in.
        val all = open
            .filter { it.assetId == position.asset && it.side == "SELL" }
            .sortedByDescending { it.price }

        // A price the user set is theirs until the window is nearly over.
        val (pinned, sells) = all.partition { held(it) }

        val onStep = { i: Int, order: ClobApi.OpenOrder ->
            abs(
                order.price -
                    SellLadder.stackedPrice(base, i + pinned.size, meta.tickSize),
            ) <= meta.tickSize / 2
        }
        val stale = sells.filterIndexed { i, order -> !onStep(i, order) }

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

        // Only orders on their own step count as cover; the stale ones were
        // just pulled. Replacing in the same pass matters — waiting for the next
        // sweep would leave the position naked for a whole retry interval.
        val standing = sells.filterIndexed(onStep)
        val covered = standing.sumOf { it.remaining } + pinned.sumOf { it.remaining }
        val uncovered = mine - covered
        if (uncovered < meta.minimumOrderSize) return "покрыто"

        // The new offer goes a step under the last one still standing.
        val price = SellLadder.stackedPrice(
            base,
            standing.size + pinned.size,
            meta.tickSize,
        )
        return tryPlace(position, uncovered, price, lotAt)
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

    /**
     * @param lotAt when the purchase being covered was made, so the wait the
     *   venue imposes on fresh shares can be timed rather than guessed.
     */
    private fun tryPlace(
        position: Position,
        size: Double,
        price: Double,
        lotAt: Long,
    ): String {
        attempts[position.asset] = (attempts[position.asset] ?: 0) + 1
        val startedAt = System.currentTimeMillis()
        lastTry[position.asset] = startedAt
        Timings.sellTried(position.asset, lotAt, startedAt)

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
                // The moment the venue stopped refusing: the one thing that
                // says how long shares stay locked after a purchase.
                Timings.sellAccepted(position.asset, lotAt, System.currentTimeMillis())
                "выставлено"
            } else {
                // Almost always "shares not sellable yet"; the next sweep retries.
                val reason = result.error ?: "отказ CLOB"
                lastError[position.asset] = reason
                Timings.sellRefused(position.asset, lotAt)
                reason
            }
        } catch (e: Exception) {
            // A network failure says nothing about the venue's lock, and
            // crediting it to the measurement would poison it.
            Timings.sellDropped(position.asset)
            val reason = e.message ?: "ошибка сети"
            lastError[position.asset] = reason
            reason
        }
    }

    /**
     * Is this offer's price the user's own, and still theirs?
     *
     * Everything the rules send is marked `auto`, so anything else standing on
     * the book was put there by the person — and the rule leaves it exactly
     * where they put it, for as long as it stands. A price set by hand is a
     * decision the rule does not have the information to overrule: not at the
     * floor, not in the last seconds, not ever. What the rule may still do is
     * cover whatever that order does not — a hand sale of ten out of
     * twenty-seven leaves seventeen for the ladder, at the ladder's own price.
     *
     * The question is asked as "did a rule place this", not "did the person",
     * so an order the log has never heard of — from before the app was last
     * opened, or from the Polymarket site — is left alone rather than moved on
     * the strength of not being recognised.
     */
    private fun held(order: ClobApi.OpenOrder): Boolean = !OrderLog.isAuto(order.id)

    /** A sell must never round down onto a worse price than asked for. */
    private fun snapToTick(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = kotlin.math.ceil(price / tick - 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(tick, 1.0 - tick)
    }
}
