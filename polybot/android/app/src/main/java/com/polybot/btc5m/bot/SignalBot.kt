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
 * The bot that trades TradingView's five-minute read on Bitcoin.
 *
 * Six dollars of its own, and one rule: when the summary, the moving averages
 * and the oscillators all say buy — or all say sell — it takes a dollar of the
 * side they point at, three times, each time the price has come down another
 * tick, and never above sixty cents. It sits out every window the three do not
 * agree on, which is most of them.
 *
 * The exits are the desk's own sell ladder rather than a fixed margin: a
 * position taken on a directional call is worth holding up the rungs as the
 * window resolves, and the ladder is what already knows how to do that. It
 * therefore manages its own offers, repricing them as the ladder steps, and its
 * shares are kept out of the desk's sweep so the two cannot fight over a price.
 */
class SignalBot(
    private val engine: BotEngine,
    private val store: SignalStore,
    /** The desk's sell ladder, so one setting drives every exit in the app. */
    private val ladder: () -> List<Double>,
    private val onStateChanged: () -> Unit,
) {

    data class Lot(
        val asset: String,
        val conditionId: String,
        val outcome: String,
        val shares: Double,
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

    data class Round(
        val windowStart: Long,
        val side: String,
        val asset: String,
        val conditionId: String,
        val lots: MutableList<Lot> = ArrayList(),
        var lastEntry: Double? = null,
        var lastAsk: Double? = null,
        var bestAsk: Double? = null,
        /** Highest this side has been this window, which is what walks the ladder. */
        var highWater: Double = 0.0,
        var step: Int = 0,
        var note: String? = null,
    ) {
        val spent: Double get() = lots.sumOf { it.cost }
        val got: Double get() = lots.sumOf { it.proceeds }
    }

    @Volatile
    var settings: SignalPlan.Settings = store.loadSettings()
        private set

    @Volatile
    var totals: BotBook.Totals = store.loadTotals()
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

    /** The last read of the three gauges, kept so the panel can show them. */
    @Volatile
    var gauges: TradingView.Gauges? = null
        private set

    val past = CopyOnWriteArrayList<BotBook.Past>().also { it.addAll(store.loadPast()) }

    val cash: Double
        get() = settings.bankUsd + totals.got + totals.settled - totals.spent -
            (round?.spent ?: 0.0) + (round?.got ?: 0.0)

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var lastOrdersAt = 0L
    private var lastGaugesAt = 0L
    private var openOrders: List<ClobApi.OpenOrder> = emptyList()

    private companion object {
        const val HUNT_MS = 2_000L
        const val IDLE_MS = 5_000L
        const val ORDERS_TTL_MS = 5_000L

        /**
         * How often the gauges are re-read. They are a five-minute study, so
         * they cannot change faster than the candle underneath them — and the
         * scanner is somebody else's server.
         */
        const val GAUGES_TTL_MS = 20_000L
    }

    fun heldShares(asset: String): Double {
        val current = round ?: return 0.0
        if (current.asset != asset) return 0.0
        return current.lots.sumOf { it.open }
    }

    fun update(next: SignalPlan.Settings) {
        settings = next
        store.saveSettings(next)
        when {
            next.enabled && !running -> start()
            !next.enabled && running -> stop()
            else -> onStateChanged()
        }
    }

    fun resetBank() {
        totals = BotBook.Totals()
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
                    lastFault = e.message ?: "сбой бота по индикаторам"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }

                delay(
                    when {
                        backoffMs > 0L -> backoffMs
                        elapsed < settings.untilSec -> HUNT_MS
                        else -> IDLE_MS
                    },
                )
            }
        }
        engine.log(
            "info",
            "Бот по индикаторам включён: $${String.format("%.0f", settings.bankUsd)}, " +
                "до ${(settings.maxPrice * 100).toInt()}¢, выход лесенкой",
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
        engine.log("info", "Бот по индикаторам выключен")
        onStateChanged()
    }

    private fun tick(windowStart: Long, elapsed: Long) {
        val session = engine.session()
        if (session == null) {
            lastFault = "кошелёк не подключён"
            return
        }
        lastFault = null

        round?.let { if (it.windowStart != windowStart) closeRound(it) }

        refreshOrders(session)
        round?.let { workLadder(it, elapsed) }

        if (elapsed in 0 until settings.untilSec) hunt(windowStart, elapsed)
        onStateChanged()
    }

    private fun refreshOrders(session: BotEngine.Session) {
        val now = System.currentTimeMillis()
        if (now - lastOrdersAt < ORDERS_TTL_MS && openOrders.isNotEmpty()) return
        openOrders = ClobApi.openOrders(session.creds, session.account.signerAddress)
        lastOrdersAt = now
        OrderLog.reconcile(openOrders) { id ->
            ClobApi.order(session.creds, session.account.signerAddress, id)
        }
    }

    /** The three gauges, re-read no faster than they can move. */
    private fun readGauges(): TradingView.Gauges? {
        val now = System.currentTimeMillis()
        val held = gauges
        if (held != null && now - lastGaugesAt < GAUGES_TTL_MS) return held
        return try {
            TradingView.read().also {
                gauges = it
                lastGaugesAt = now
            }
        } catch (e: Exception) {
            lastFault = "TradingView недоступен"
            held
        }
    }

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

        // The gauges decide the side once per window and then stop mattering:
        // a position taken on a call is not re-argued halfway through it.
        val current = round
        val side = current?.side ?: SignalPlan.direction(readGauges())
        if (side == null) {
            // Still worth saying why nothing is happening.
            gauges?.let {
                round?.note = "индикаторы не согласны"
            }
            return
        }

        val outcome = if (side == "Up") market.up else market.down
        val live = current ?: Round(
            windowStart = windowStart,
            side = side,
            asset = outcome.tokenId,
            conditionId = market.conditionId,
        ).also { round = it }

        val ask = try {
            ClobApi.bestAsk(live.asset)
        } catch (e: Exception) {
            live.note = "цена недоступна"
            return
        }
        live.lastAsk = ask
        if (ask != null && ask > 0.0) {
            live.bestAsk = minOf(live.bestAsk ?: ask, ask)
            if (ask > live.highWater) live.highWater = ask
        }

        val blocked = SignalPlan.blockedBecause(
            side = side,
            ask = ask,
            elapsedSec = elapsed,
            buys = live.lots.size,
            lastEntry = live.lastEntry,
            tick = market.tickSize,
            cashUsd = cash,
            settings = settings,
        )
        live.note = blocked
        if (blocked != null || ask == null) return

        buy(live, market, ask)
    }

    private fun buy(current: Round, market: Market, ask: Double) {
        val shares = SignalPlan.clipShares(ask, market.minimumOrderSize, settings)
        val limit = SignalPlan.crossPrice(ask, market.tickSize)

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
            engine.log("error", "Бот по индикаторам: ${current.note}")
            return
        }

        val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
        if (fill.shares <= 1e-6) {
            result.orderId?.let { id ->
                engine.session()?.let { s ->
                    try {
                        ClobApi.cancelOrder(s.creds, s.account.signerAddress, id)
                    } catch (e: Exception) {
                        // It may have filled in between; the log will say so.
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

        val g = gauges
        engine.log(
            "trade",
            "Индикаторы: взял " + String.format("%.1f", fill.shares) + " ${current.side} по " +
                "${(price * 100).toInt()}¢" +
                (g?.let { " (${SignalPlan.verdict(it.summary)})" } ?: ""),
        )
    }

    /**
     * Keep one offer on the book at the rung the ladder is on.
     *
     * The rung only ever goes up — by the clock, and by any price the side has
     * already reached — so an offer left behind is one that would cap a winning
     * position at an early minute's price. It is pulled and replaced in the same
     * pass, because waiting for the next one leaves the shares unoffered.
     */
    private fun workLadder(current: Round, elapsed: Long) {
        val market = engine.currentMarket()
        val tick = market?.tickSize ?: 0.01
        val minOrder = market?.minimumOrderSize ?: 5.0
        val now = System.currentTimeMillis()

        current.step = SellLadder.stepFor(
            elapsedSec = elapsed,
            highWater = current.highWater.takeIf { it > 0.0 },
            ladder = ladder(),
            floor = current.step,
        )
        val rungs = ladder()
        val target = snapUp(rungs.getOrElse(current.step) { rungs.lastOrNull() ?: 0.9 }, tick)

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
                        "Индикаторы: продал " + String.format("%.1f", gained) + " по " +
                            "${(lot.sellPrice * 100).toInt()}¢",
                    )
                }
                if (lot.open <= 1e-6) continue

                // The ladder has moved on; the offer has to move with it.
                if (abs(lot.sellPrice - target) > tick / 2) {
                    val session = engine.session() ?: continue
                    try {
                        ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
                        lot.sellOrderId = null
                        lot.note = "переставляю"
                    } catch (e: Exception) {
                        lot.note = e.message ?: "не снять ордер"
                        continue
                    }
                } else {
                    continue
                }
            }
            if (lot.open < minOrder - 1e-6) continue

            val hold = Timings.holdMs(lot.boughtAt, now)
            if (hold > 0L) {
                lot.note = "жду ${(hold + 999) / 1000} с"
                continue
            }

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

    private fun closeRound(current: Round) {
        round = null

        engine.session()?.let { session ->
            for (lot in current.lots) {
                val id = lot.sellOrderId ?: continue
                if (lot.open <= 1e-6) continue
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
                } catch (e: Exception) {
                    // Already gone, which is what was wanted.
                }
            }
        }

        val held = current.lots.sumOf { it.open }
        val winner = EventStats.winnerFor(current.windowStart, Clock.nowSec())
        val settlement = if (held > 1e-6 && winner == current.side) held else 0.0
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
            BotBook.Past(
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
                "Индикаторы закрыли окно: " + (if (pnl >= 0) "+" else "−") +
                    "$" + String.format("%.2f", abs(pnl)),
            )
        }
    }

    /** A sell must never round down onto a worse price than the rung asks. */
    private fun snapUp(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = kotlin.math.ceil(price / tick - 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(tick, 1.0 - tick)
    }
}
