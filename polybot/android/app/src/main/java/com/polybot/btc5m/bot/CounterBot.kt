package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A small bot that fades the desk's own side.
 *
 * It trades five dollars of its own, kept apart from the money the desk is
 * allowed to touch, and it only ever does one thing: in the first two minutes
 * of a window, whenever the side the user is *not* on is offered under thirty
 * cents, it takes a dollar of it — up to three times, and never twice at the
 * same price. Each clip is offered straight back at a quarter up, net of the
 * fee, and it holds nothing into the decision on purpose: the position it buys
 * is the one that is losing, and the last minute is where that gets settled.
 *
 * The rules themselves live in [CounterPlan] so they can be argued with in a
 * test. What is here is the loop, the money, and the record.
 */
class CounterBot(
    private val engine: BotEngine,
    private val store: CounterStore,
    private val onStateChanged: () -> Unit,
) {

    /** One clip, from the moment it is bought to the moment it is off the books. */
    data class Lot(
        val asset: String,
        val conditionId: String,
        val outcome: String,
        val shares: Double,
        /** What was paid per share. */
        val price: Double,
        val boughtAt: Long,
        var sellOrderId: String? = null,
        var sellPrice: Double = 0.0,
        var sold: Double = 0.0,
        var proceeds: Double = 0.0,
        var note: String? = null,
    ) {
        val cost: Double get() = shares * price
        val open: Double get() = (shares - sold).coerceAtLeast(0.0)
    }

    /** One window's work. */
    data class Round(
        val windowStart: Long,
        /** The side the desk is on, and the one this bot therefore buys. */
        val deskSide: String,
        val side: String,
        val asset: String,
        val conditionId: String,
        val lots: MutableList<Lot> = ArrayList(),
        var lastEntry: Double? = null,
        var lastAsk: Double? = null,
        var bestAsk: Double? = null,
        var checks: Int = 0,
        var note: String? = null,
    ) {
        val spent: Double get() = lots.sumOf { it.cost }
        val got: Double get() = lots.sumOf { it.proceeds }
    }

    /** Everything it has ever done, in one line. */
    data class Totals(
        val rounds: Int = 0,
        val buys: Int = 0,
        val sells: Int = 0,
        val spent: Double = 0.0,
        val got: Double = 0.0,
        /** What shares held to the close were worth. */
        val settled: Double = 0.0,
        val wins: Int = 0,
        val losses: Int = 0,
    ) {
        val pnl: Double get() = got + settled - spent
    }

    /** A finished window, kept so the stats card can show more than a number. */
    data class Past(
        val windowStart: Long,
        val side: String,
        val shares: Double,
        val spent: Double,
        val got: Double,
        val pnl: Double,
        val note: String,
    )

    @Volatile
    var settings: CounterPlan.Settings = store.loadSettings()
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
    var round: Round? = null
        private set

    val past = CopyOnWriteArrayList<Past>().also { it.addAll(store.loadPast()) }

    /** What is left of the bot's own money. */
    val cash: Double
        get() = settings.bankUsd + totals.got + totals.settled - totals.spent -
            (round?.spent ?: 0.0) + (round?.got ?: 0.0)

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var lastOrdersAt = 0L
    private var openOrders: List<ClobApi.OpenOrder> = emptyList()

    private companion object {
        /** How often to look at the price while entries are still allowed. */
        const val HUNT_MS = 2_000L

        /** And once they are not: only the exits still need attention. */
        const val IDLE_MS = 5_000L

        /** The book listing is shared between passes; it moves slower than a price. */
        const val ORDERS_TTL_MS = 5_000L
    }

    /** Shares of an outcome this bot is holding, so nothing else sells them. */
    fun heldShares(asset: String): Double {
        val current = round ?: return 0.0
        if (current.asset != asset) return 0.0
        return current.lots.sumOf { it.open }
    }

    fun update(next: CounterPlan.Settings) {
        settings = next
        store.saveSettings(next)
        when {
            next.enabled && !running -> start()
            !next.enabled && running -> stop()
            else -> onStateChanged()
        }
    }

    /** Start the money over, keeping nothing but the settings. */
    fun resetBank() {
        totals = Totals()
        store.saveTotals(totals)
        past.clear()
        store.savePast(emptyList())
        round = null
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
                val nowSec = Clock.nowSec()
                val windowStart = nowSec - SellLadder.elapsedInWindow(nowSec)
                val elapsed = nowSec - windowStart

                try {
                    tick(windowStart, elapsed)
                    backoffMs = 0L
                } catch (e: Exception) {
                    lastFault = e.message ?: "сбой контр-бота"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }

                delay(
                    when {
                        backoffMs > 0L -> backoffMs
                        elapsed < settings.entryWindowSec -> HUNT_MS
                        else -> IDLE_MS
                    },
                )
            }
        }
        engine.log(
            "info",
            "Контр-бот включён: $${String.format("%.0f", settings.bankUsd)}, " +
                "по $${String.format("%.0f", settings.clipUsd)} до " +
                "${(settings.entryUnder * 100).toInt()}¢, цель +" +
                "${(settings.gainPct * 100).toInt()}%",
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
        engine.log("info", "Контр-бот выключен")
        onStateChanged()
    }

    private fun tick(windowStart: Long, elapsed: Long) {
        val session = engine.session()
        if (session == null) {
            lastFault = "кошелёк не подключён"
            return
        }
        lastFault = null

        // A window that has rolled is a window to score and put away.
        round?.let { if (it.windowStart != windowStart) closeRound(it) }

        refreshOrders(session)
        round?.let { workExits(it) }

        if (elapsed in 0 until settings.entryWindowSec) hunt(windowStart, elapsed)
        onStateChanged()
    }

    /** The open-order listing, shared between the passes that need it. */
    private fun refreshOrders(session: BotEngine.Session) {
        val now = System.currentTimeMillis()
        if (now - lastOrdersAt < ORDERS_TTL_MS && openOrders.isNotEmpty()) return
        openOrders = ClobApi.openOrders(session.creds, session.account.signerAddress)
        lastOrdersAt = now
        // Nothing else is guaranteed to be running, so the bot keeps its own
        // record of its orders current: that is how it learns a sell filled.
        OrderLog.reconcile(openOrders) { id ->
            ClobApi.order(session.creds, session.account.signerAddress, id)
        }
    }

    /**
     * Look for a clip.
     *
     * The desk's side comes from the app's own log rather than from the wallet:
     * a purchase is known the instant it is made, while the data API takes a
     * minute to admit it exists, and by then the entry band is over.
     */
    private fun hunt(windowStart: Long, elapsed: Long) {
        val market = engine.currentMarket()
        if (market == null || market.windowStart != windowStart) {
            round?.note = "нет рынка"
            return
        }
        if (!market.acceptingOrders) {
            round?.note = "рынок закрыт"
            return
        }

        val current = round ?: run {
            val deskSide = deskSideIn(windowStart) ?: return
            val side = CounterPlan.opposite(deskSide)
            val outcome = if (side == "Up") market.up else market.down
            Round(
                windowStart = windowStart,
                deskSide = deskSide,
                side = side,
                asset = outcome.tokenId,
                conditionId = market.conditionId,
            ).also { round = it }
        }

        val ask = try {
            ClobApi.bestAsk(current.asset)
        } catch (e: Exception) {
            current.note = "цена недоступна"
            return
        }
        current.lastAsk = ask
        current.checks += 1
        if (ask != null && ask > 0.0) {
            current.bestAsk = minOf(current.bestAsk ?: ask, ask)
        }

        val blocked = CounterPlan.blockedBecause(
            ask = ask,
            elapsedSec = elapsed,
            buys = current.lots.size,
            lastEntry = current.lastEntry,
            tick = market.tickSize,
            cashUsd = cash,
            settings = settings,
        )
        current.note = blocked
        if (blocked != null || ask == null) return

        buy(current, market, ask)
    }

    private fun buy(current: Round, market: Market, ask: Double) {
        val shares = CounterPlan.clipShares(ask, market.minimumOrderSize, settings)
        val limit = CounterPlan.crossPrice(ask, market.tickSize)

        val result = try {
            engine.placeManualOrder(
                tokenId = current.asset,
                conditionId = current.conditionId,
                side = "BUY",
                price = limit,
                size = shares,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            current.note = e.message ?: "ошибка сети"
            return
        }

        if (!result.success) {
            current.note = result.error ?: "отказ CLOB"
            engine.log("error", "Контр-бот: ${current.note}")
            return
        }

        val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
        if (fill.shares <= 1e-6) {
            // A crossing limit that did not take is a dip that moved. Pull it
            // rather than leave it resting at a price the bot no longer wants.
            result.orderId?.let { id ->
                engine.session()?.let { s ->
                    try {
                        ClobApi.cancelOrder(s.creds, s.account.signerAddress, id)
                    } catch (e: Exception) {
                        // It may have filled between the two calls; the log knows.
                    }
                }
            }
            current.note = "не налили по ${(ask * 100).toInt()}¢"
            return
        }

        val price = if (fill.usd > 0.0) fill.usd / fill.shares else limit
        current.lots.add(
            Lot(
                asset = current.asset,
                conditionId = current.conditionId,
                outcome = current.side,
                shares = fill.shares,
                price = price,
                boughtAt = System.currentTimeMillis(),
            ),
        )
        current.lastEntry = ask
        current.note = null
        totals = totals.copy(buys = totals.buys + 1, spent = totals.spent + fill.shares * price)
        store.saveTotals(totals)

        engine.log(
            "trade",
            "Контр-бот взял " + String.format("%.1f", fill.shares) + " ${current.side} по " +
                "${(price * 100).toInt()}¢ (моя сторона ${current.deskSide})",
        )
    }

    /**
     * Offer every clip back, and notice when one is taken.
     *
     * The offer goes out as soon as the venue will accept it — which is not the
     * instant of purchase, and how long it actually is has been measured rather
     * than assumed.
     */
    private fun workExits(current: Round) {
        val tick = engine.currentMarket()?.tickSize ?: 0.01
        val now = System.currentTimeMillis()

        for (lot in current.lots) {
            val id = lot.sellOrderId
            if (id != null) {
                val entry = OrderLog.all().firstOrNull { it.orderId == id }
                if (entry != null && entry.matched > lot.sold + 1e-9) {
                    val gained = entry.matched - lot.sold
                    lot.sold = entry.matched
                    lot.proceeds += gained * lot.sellPrice
                    totals = totals.copy(
                        sells = totals.sells + 1,
                        got = totals.got + gained * lot.sellPrice,
                    )
                    store.saveTotals(totals)
                    engine.log(
                        "trade",
                        "Контр-бот продал " + String.format("%.1f", gained) + " по " +
                            "${(lot.sellPrice * 100).toInt()}¢",
                    )
                }
                if (lot.open <= 1e-6) lot.note = null
                continue
            }
            if (lot.open <= 1e-6) continue

            // Wait out the venue's lock on fresh shares rather than firing
            // refusals at it. Zero until the app has timed that lock.
            val hold = Timings.holdMs(lot.boughtAt, now)
            if (hold > 0L) {
                lot.note = "жду ${(hold + 999) / 1000} с"
                continue
            }

            val target = CounterPlan.exitPrice(lot.price, tick, settings)
            Timings.sellTried(lot.asset, lot.boughtAt, now)
            val result = try {
                engine.placeManualOrder(
                    tokenId = lot.asset,
                    conditionId = lot.conditionId,
                    side = "SELL",
                    price = target,
                    size = lot.open,
                    orderType = "GTC",
                    auto = true,
                )
            } catch (e: Exception) {
                Timings.sellDropped(lot.asset)
                lot.note = e.message ?: "ошибка сети"
                continue
            }

            if (result.success) {
                Timings.sellAccepted(lot.asset, lot.boughtAt, System.currentTimeMillis())
                lot.sellOrderId = result.orderId
                lot.sellPrice = target
                lot.note = null
            } else {
                Timings.sellRefused(lot.asset, lot.boughtAt)
                lot.note = result.error ?: "отказ CLOB"
            }
        }
    }

    /**
     * Put a finished window away.
     *
     * Anything still held when the window closed settles at a dollar or at
     * nothing, decided by the same price series Polymarket settles on. A round
     * that ended holding the losing side is a round that lost its stake, and
     * the record says so rather than leaving it open forever.
     */
    private fun closeRound(current: Round) {
        round = null

        // Pull anything still resting: its market is gone.
        engine.session()?.let { session ->
            for (lot in current.lots) {
                val id = lot.sellOrderId ?: continue
                if (lot.open <= 1e-6) continue
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
                } catch (e: Exception) {
                    // Already gone, which is the outcome wanted anyway.
                }
            }
        }

        val held = current.lots.sumOf { it.open }
        val winner = EventStats.winnerFor(current.windowStart, Clock.nowSec())
        val settlement = when {
            held <= 1e-6 -> 0.0
            winner.isEmpty() -> 0.0
            winner == current.side -> held
            else -> 0.0
        }
        val pnl = current.got + settlement - current.spent

        totals = totals.copy(
            rounds = totals.rounds + 1,
            settled = totals.settled + settlement,
            wins = totals.wins + if (pnl > 0) 1 else 0,
            losses = totals.losses + if (pnl < 0) 1 else 0,
        )
        store.saveTotals(totals)

        past.add(
            0,
            Past(
                windowStart = current.windowStart,
                side = current.side,
                shares = current.lots.sumOf { it.shares },
                spent = current.spent,
                got = current.got + settlement,
                pnl = pnl,
                note = when {
                    current.lots.isEmpty() -> "не входил"
                    held <= 1e-6 -> "продал"
                    winner.isEmpty() -> "исход неизвестен"
                    winner == current.side -> "досидел в плюс"
                    else -> "сгорело"
                },
            ),
        )
        while (past.size > 24) past.removeAt(past.size - 1)
        store.savePast(past.toList())

        if (current.lots.isNotEmpty()) {
            engine.log(
                if (pnl >= 0) "trade" else "warn",
                "Контр-бот закрыл окно: " + (if (pnl >= 0) "+" else "−") +
                    "$" + String.format("%.2f", kotlin.math.abs(pnl)),
            )
        }
    }

    /**
     * The side the desk is on this window, or null while it is on neither.
     *
     * Read from the app's own order log and only from orders placed by hand —
     * the bot's own clips are in there too, and fading itself would be a
     * machine arguing with a machine.
     */
    private fun deskSideIn(windowStart: Long): String? {
        val mine = OrderLog.forWindow(windowStart).filter {
            it.action == "BUY" && !it.auto && it.outcome.isNotEmpty()
        }
        if (mine.isEmpty()) return null
        // The biggest commitment wins if both sides were touched; a resting
        // limit counts, because it is a side the user has chosen.
        return mine
            .groupBy { it.outcome }
            .mapValues { (_, rows) -> rows.sumOf { maxOf(it.matched, it.size) * it.price } }
            .maxByOrNull { it.value }
            ?.key
    }
}
