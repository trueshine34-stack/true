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
 * Buys the favourite whenever it is still cheaper than the rung it will be
 * sold at, and sells it at that rung.
 *
 * The check comes fifteen seconds before each minute ends, which is exactly
 * when the sell ladder steps up: at that moment the price the position will be
 * offered at is known, and so is what the market is charging for it. If the
 * second number is below the first, the round is arranged before it is opened.
 *
 * It trades its own money, walled off from the desk's, and it manages its own
 * offers so the desk's rule and this one cannot pull each other's orders.
 */
class LadderBot(
    private val engine: BotEngine,
    private val store: LadderStore,
    /** The desk's sell ladder — one setting drives every exit in the app. */
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
        val conditionId: String,
        val lots: MutableList<Lot> = ArrayList(),
        /** The last check acted on, so one moment cannot buy twice. */
        var lastSlot: Int = -1,
        /** When the last clip went through, which paces the next one. */
        var lastBuyAt: Long = 0L,
        var lastSide: String? = null,
        var lastAsk: Double? = null,
        var lastRung: Double = 0.0,
        var note: String? = null,
        /** Highest each side has been, which is what walks the ladder. */
        var highWater: Double = 0.0,
        var step: Int = 0,
    ) {
        val spent: Double get() = lots.sumOf { it.cost }
        val got: Double get() = lots.sumOf { it.proceeds }
    }

    data class Totals(
        val rounds: Int = 0,
        val buys: Int = 0,
        val sells: Int = 0,
        val spent: Double = 0.0,
        val got: Double = 0.0,
        val settled: Double = 0.0,
    ) {
        val pnl: Double get() = got + settled - spent
    }

    @Volatile
    var settings: LadderPlan.Settings = store.loadSettings()
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

    val cash: Double
        get() = settings.bankUsd + totals.got + totals.settled - totals.spent -
            (round?.spent ?: 0.0) + (round?.got ?: 0.0)

    /** Shares of an outcome this bot holds, so the desk's rule leaves them be. */
    fun heldShares(asset: String): Double =
        round?.lots?.filter { it.asset == asset }?.sumOf { it.open } ?: 0.0

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var lastOrdersAt = 0L
    private var openOrders: List<ClobApi.OpenOrder> = emptyList()

    private companion object {
        const val TICK_MS = 2_000L
        const val ORDERS_TTL_MS = 5_000L
    }

    fun update(next: LadderPlan.Settings) {
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
                try {
                    tick()
                    backoffMs = 0L
                } catch (e: Exception) {
                    lastFault = e.message ?: "сбой бота лесенки"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }
                delay(if (backoffMs > 0L) backoffMs else TICK_MS)
            }
        }
        engine.log(
            "info",
            "Бот лесенки включён: по " + String.format("%.0f", settings.shares) +
                " долей, первая проверка на ${settings.firstAtSec} с, " +
                "дальше каждые ${settings.everySec} с или сразу после сделки",
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
        engine.log("info", "Бот лесенки выключен")
        onStateChanged()
    }

    private fun tick() {
        val session = engine.session()
        if (session == null) {
            lastFault = "кошелёк не подключён"
            return
        }
        lastFault = null

        val nowSec = Clock.nowSec()
        val windowStart = nowSec - SellLadder.elapsedInWindow(nowSec)
        val elapsed = nowSec - windowStart

        round?.let { if (it.windowStart != windowStart) closeRound(it) }

        refreshOrders(session)
        round?.let { workLadder(it, elapsed) }
        hunt(windowStart, elapsed)
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

    /** The rung the ladder is about to ask for, which is also the exit price. */
    private fun rungFor(elapsed: Long, highWater: Double?, step: Int): Pair<Int, Double> {
        val rungs = ladder().ifEmpty { SellLadder.DEFAULT }
        val next = SellLadder.stepFor(elapsed, highWater, rungs, step)
        return next to rungs.getOrElse(next) { rungs.last() }
    }

    private fun hunt(windowStart: Long, elapsed: Long) {
        val slot = LadderPlan.slotFor(elapsed, settings)
        val current = round
        if (slot < 0) {
            if (current != null && current.lastSlot >= 0) current.note = "ждёт следующей минуты"
            return
        }

        // Two ways in. The minute's scheduled check is one; the other is that
        // a clip has already gone through and the conditions still suit, in
        // which case waiting out the rest of the minute waits for nothing.
        // What actually limits this is the money in the container.
        val fresh = current == null || slot > current.lastSlot
        val again = current != null &&
            current.lots.isNotEmpty() &&
            LadderPlan.readyAfter(
                elapsedSec = elapsed,
                sinceLastBuyMs = System.currentTimeMillis() - current.lastBuyAt,
                settings = settings,
            )
        if (!fresh && !again) return

        val market = engine.currentMarket()
        if (market == null || market.windowStart != windowStart) {
            current?.note = "нет рынка"
            return
        }
        if (!market.acceptingOrders) {
            current?.note = "рынок закрыт"
            return
        }

        val askUp = try {
            ClobApi.bestAsk(market.up.tokenId)
        } catch (e: Exception) {
            current?.note = "цена недоступна"
            return
        }
        val askDown = try {
            ClobApi.bestAsk(market.down.tokenId)
        } catch (e: Exception) {
            current?.note = "цена недоступна"
            return
        }

        val live = current ?: Round(windowStart, market.conditionId).also { round = it }
        val side = LadderPlan.leadingSide(askUp, askDown)
        val ask = if (side == "Up") askUp else askDown
        if (ask != null && ask > live.highWater) live.highWater = ask

        val (step, rung) = rungFor(elapsed, live.highWater.takeIf { it > 0.0 }, live.step)
        live.step = step
        live.lastSide = side
        live.lastAsk = ask
        live.lastRung = rung

        val blocked = LadderPlan.blockedBecause(
            side = side,
            ask = ask,
            rung = rung,
            elapsedSec = elapsed,
            cashUsd = cash,
            settings = settings,
        )
        // The slot is used up either way: a check that found nothing is a check
        // that happened. Between slots the pause does the pacing instead.
        live.lastSlot = slot
        live.note = blocked
        if (blocked != null || side == null || ask == null) return

        buy(live, market, side, ask, rung)
    }

    private fun buy(
        current: Round,
        market: Market,
        side: String,
        ask: Double,
        rung: Double,
    ) {
        val token = if (side == "Up") market.up.tokenId else market.down.tokenId
        val size = maxOf(settings.shares, Orders.minShares(ask, market.minimumOrderSize))
        val limit = LadderPlan.crossPrice(ask, market.tickSize)

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
            current.note = e.message ?: "ошибка сети"
            return
        }

        if (!result.success) {
            current.note = result.error ?: "отказ CLOB"
            engine.log("error", "Бот лесенки: ${current.note}")
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
                asset = token,
                conditionId = market.conditionId,
                outcome = side,
                shares = fill.shares,
                price = price,
                boughtAt = System.currentTimeMillis(),
            ),
        )
        current.note = null
        current.lastBuyAt = System.currentTimeMillis()
        totals = totals.copy(buys = totals.buys + 1, spent = totals.spent + fill.shares * price)
        store.saveTotals(totals)

        engine.log(
            "trade",
            "Лесенка: взял " + String.format("%.1f", fill.shares) + " $side по " +
                "${(price * 100).toInt()}¢ под ступень ${(rung * 100).toInt()}¢",
        )
    }

    /** One offer per lot, at the rung the ladder is on, moved up as it steps. */
    private fun workLadder(current: Round, elapsed: Long) {
        if (current.lots.isEmpty()) return
        val market = engine.currentMarket()
        val tick = market?.tickSize ?: 0.01
        val minOrder = market?.minimumOrderSize ?: 5.0
        val now = System.currentTimeMillis()

        val (step, target) = rungFor(elapsed, current.highWater.takeIf { it > 0.0 }, current.step)
        current.step = step
        val price = snapUp(target, tick)

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
                        "Лесенка: продал " + String.format("%.1f", gained) + " по " +
                            "${(lot.sellPrice * 100).toInt()}¢",
                    )
                }
                if (lot.open <= 1e-6) continue

                if (abs(lot.sellPrice - price) <= tick / 2) continue
                val session = engine.session() ?: continue
                try {
                    ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
                    lot.sellOrderId = null
                    lot.note = "переставляю"
                } catch (e: Exception) {
                    lot.note = e.message ?: "не снять ордер"
                    continue
                }
            }
            if (lot.open < minOrder - 1e-6) continue

            // The venue locks freshly bought shares; how long for has been
            // measured rather than guessed.
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
                    price = price,
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
                lot.sellPrice = price
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

        val winner = EventStats.winnerFor(current.windowStart, Clock.nowSec())
        val settlement = current.lots
            .filter { it.open > 1e-6 && it.outcome == winner }
            .sumOf { it.open }
        val pnl = current.got + settlement - current.spent

        totals = totals.copy(
            rounds = totals.rounds + 1,
            settled = totals.settled + settlement,
        )
        store.saveTotals(totals)

        if (current.lots.isNotEmpty()) {
            engine.log(
                if (pnl >= 0) "trade" else "warn",
                "Лесенка закрыла окно: " + (if (pnl >= 0) "+" else "−") +
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
