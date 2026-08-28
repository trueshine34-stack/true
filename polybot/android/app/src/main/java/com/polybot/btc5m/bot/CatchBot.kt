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
 * Works one side, armed by hand, until it is disarmed.
 *
 * `CatchPlan` holds the rule and is tested on its own; this watches the book
 * fast enough to act on it, sends the orders and keeps the books. The reason
 * it polls rather than rests its buys is the whole idea: a limit at the target
 * is filled the moment price touches it, while watching the offer and taking
 * it buys the bottom of the move.
 *
 * It trades its own container and its shares are hidden from the desk's sell
 * rule, so the two never pull each other's orders.
 */
class CatchBot(
    private val engine: BotEngine,
    private val store: CatchStore,
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
        var sellPlacedAt: Long = 0L,
        var sold: Double = 0.0,
        var proceeds: Double = 0.0,
        var note: String? = null,
    ) {
        val cost: Double get() = shares * price
        val open: Double get() = (shares - sold).coerceAtLeast(0.0)
    }

    data class Totals(
        val buys: Int = 0,
        val sells: Int = 0,
        val spent: Double = 0.0,
        val got: Double = 0.0,
        val settled: Double = 0.0,
    ) {
        val pnl: Double get() = got + settled - spent
    }

    @Volatile
    var settings: CatchPlan.Settings = store.loadSettings()
        private set

    @Volatile
    var totals: Totals = store.loadTotals()
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** Which side is being worked, or null when the rule is not armed. */
    @Volatile
    var side: String? = null
        private set

    /**
     * The price the next entry is measured from.
     *
     * It starts as the price the side was armed at and becomes the price of
     * every sale after that, which is what makes the cycle start again from
     * where it left off rather than from where it began.
     */
    @Volatile
    var reference: Double = 0.0
        private set

    @Volatile
    var lastFault: String? = null
        private set

    @Volatile
    var note: String? = null
        private set

    @Volatile
    var target: Double = 0.0
        private set

    @Volatile
    var ask: Double = 0.0
        private set

    val lots = java.util.concurrent.CopyOnWriteArrayList<Lot>()

    val cash: Double
        get() = settings.bankUsd + totals.got + totals.settled - totals.spent

    /** Shares this bot holds, so the desk's own rule leaves them alone. */
    fun heldShares(asset: String): Double =
        lots.filter { it.asset == asset }.sumOf { it.open }

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private companion object {
        /** Fast enough to take a dip that lasts a second, cheap enough to run. */
        const val TICK_MS = 700L
    }

    fun update(next: CatchPlan.Settings) {
        settings = next
        store.saveSettings(next)
        onStateChanged()
    }

    fun resetBank() {
        totals = Totals()
        store.saveTotals(totals)
        onStateChanged()
    }

    /**
     * Arm on a side, from the price it is at right now.
     *
     * That price is the reference the first entry is measured under, which is
     * why arming is a moment rather than a switch: six cents under where it was
     * when you pressed is a different price a minute later.
     */
    fun arm(which: String) {
        val market = engine.currentMarket()
        val token = when (which) {
            "Up" -> market?.up?.tokenId
            "Down" -> market?.down?.tokenId
            else -> null
        }
        val now = token?.let {
            try {
                ClobApi.bestAsk(it)
            } catch (e: Exception) {
                null
            }
        }
        if (now == null || now <= 0.0) {
            lastFault = "нет цены — не с чего считать"
            onStateChanged()
            return
        }

        side = which
        reference = now
        settings = settings.copy(enabled = true)
        target = CatchPlan.buyTarget(reference, null, settings)
        lastFault = null
        note = null
        engine.log(
            "info",
            "Ловец на $which: от ${(now * 100).toInt()}¢, первый вход " +
                "по ${(target * 100).toInt()}¢",
        )
        start()
    }

    /** Disarm. What is already held keeps its offers; nothing new is bought. */
    fun disarm() {
        side = null
        settings = settings.copy(enabled = false)
        note = null
        engine.log("info", "Ловец снят")
        if (lots.isEmpty()) stop() else onStateChanged()
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
                    lastFault = e.message ?: "сбой ловца"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }
                delay(if (backoffMs > 0L) backoffMs else TICK_MS)
            }
        }
        onStateChanged()
    }

    fun stop() {
        if (!running) return
        running = false
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
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
        val secondsLeft = windowStart + WINDOW_SECONDS - nowSec

        val market = engine.currentMarket()
        if (market == null || market.windowStart != windowStart) {
            note = "нет рынка"
            onStateChanged()
            return
        }

        // A window that has closed takes its lots with it: what is left settles.
        lots.filter { it.windowStart != windowStart }.forEach { settle(it) }

        val which = side
        val token = when (which) {
            "Up" -> market.up.tokenId
            "Down" -> market.down.tokenId
            else -> null
        }

        if (token != null) {
            ask = try {
                ClobApi.bestAsk(token) ?: 0.0
            } catch (e: Exception) {
                0.0
            }
        }

        work(market, secondsLeft)

        if (which != null && token != null) hunt(market, which, token, secondsLeft)
        else if (lots.isEmpty()) stop()

        onStateChanged()
    }

    /** Waits for the target, then takes the offer rather than resting on it. */
    private fun hunt(market: Market, which: String, token: String, secondsLeft: Long) {
        if (secondsLeft <= CatchPlan.LATE_SEC) {
            note = "окно кончается"
            return
        }

        val lastFill = lots.lastOrNull { it.windowStart == market.windowStart }?.price
        target = CatchPlan.buyTarget(reference, lastFill, settings)

        if (!CatchPlan.ready(ask.takeIf { it > 0.0 }, target)) {
            note = "жду ${(target * 100).toInt()}¢, сейчас ${(ask * 100).toInt()}¢"
            return
        }

        val ceiling = BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart))
        if (ask > ceiling + 1e-9) {
            note = BuyCap.reason(BuyCap.elapsedFor(market.windowStart))
            return
        }

        val shares = CatchPlan.clipShares(cash, ask, settings, market.minimumOrderSize)
        if (!CatchPlan.affordable(cash, ask, shares)) {
            note = "контейнер пуст"
            return
        }

        buy(market, which, token, ask, shares)
    }

    private fun buy(market: Market, which: String, token: String, ask: Double, shares: Double) {
        val limit = minOf(
            CatchPlan.crossPrice(ask, market.tickSize),
            BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart)),
        )

        val result = try {
            engine.placeManualOrder(
                tokenId = token,
                conditionId = market.conditionId,
                side = "BUY",
                price = limit,
                size = shares,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            note = e.message ?: "ошибка сети"
            return
        }

        if (!result.success) {
            note = result.error ?: "отказ CLOB"
            engine.log("error", "Ловец: $note")
            return
        }

        val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
        if (fill.shares <= 1e-6) {
            // The offer moved between the read and the send. Leaving the order
            // resting would be exactly the limit this rule exists to avoid.
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
        lots.add(
            Lot(
                asset = token,
                conditionId = market.conditionId,
                outcome = which,
                shares = fill.shares,
                price = price,
                boughtAt = System.currentTimeMillis(),
                windowStart = market.windowStart,
            ),
        )
        totals = totals.copy(buys = totals.buys + 1, spent = totals.spent + fill.shares * price)
        store.saveTotals(totals)
        note = null
        engine.log(
            "trade",
            "Ловец: взял " + String.format("%.1f", fill.shares) + " $which по " +
                "${(price * 100).toInt()}¢",
        )
    }

    /**
     * Keeps one offer per lot where the rule says it belongs.
     *
     * Which is the gain on the first clip and a step per lot above it, until
     * the last half minute, when the window is about to settle and the only
     * thing worth asking is a shade under par.
     */
    private fun work(market: Market, secondsLeft: Long) {
        if (lots.isEmpty()) return
        val late = CatchPlan.late(secondsLeft)
        val firstCost = lots.firstOrNull()?.price ?: return
        val now = System.currentTimeMillis()

        lots.forEachIndexed { index, lot ->
            collect(lot)
            if (lot.open <= 1e-6) return@forEachIndexed

            val want = if (late) {
                CatchPlan.latePrice(index, market.tickSize)
            } else {
                CatchPlan.sellPrice(firstCost, index, settings, market.tickSize)
            }

            val id = lot.sellOrderId
            if (id != null) {
                // Pulled by somebody — the desk cancels the rules' offers when
                // the person sells by hand, and their order outranks this one.
                if (engine.restingAt > lot.sellPlacedAt && engine.resting.none { it.id == id }) {
                    lot.sellOrderId = null
                } else if (abs(lot.sellPrice - want) <= market.tickSize / 2) {
                    return@forEachIndexed
                } else {
                    val session = engine.session() ?: return@forEachIndexed
                    try {
                        ClobApi.cancelOrder(session.creds, session.account.signerAddress, id)
                        lot.sellOrderId = null
                    } catch (e: Exception) {
                        lot.note = e.message ?: "не снять ордер"
                        return@forEachIndexed
                    }
                }
            }

            if (lot.open < market.minimumOrderSize - 1e-6) return@forEachIndexed

            // The venue locks freshly bought shares; how long for is measured.
            val hold = Timings.holdMs(lot.boughtAt, now)
            if (hold > 0L) {
                lot.note = "жду ${(hold + 999) / 1000} с"
                return@forEachIndexed
            }

            Timings.sellTried(lot.asset, lot.boughtAt, now)
            val result = try {
                engine.placeManualOrder(
                    tokenId = lot.asset,
                    conditionId = lot.conditionId,
                    side = "SELL",
                    price = want,
                    size = lot.open,
                    orderType = "GTC",
                    auto = true,
                )
            } catch (e: Exception) {
                Timings.sellDropped(lot.asset)
                lot.note = e.message ?: "ошибка сети"
                return@forEachIndexed
            }

            if (result.success) {
                Timings.sellAccepted(lot.asset, lot.boughtAt, System.currentTimeMillis())
                lot.sellOrderId = result.orderId
                lot.sellPrice = want
                lot.sellPlacedAt = System.currentTimeMillis()
                lot.note = null
            } else {
                Timings.sellRefused(lot.asset, lot.boughtAt)
                lot.note = result.error ?: "отказ CLOB"
            }
        }
    }

    /**
     * Reads the fill of our own offer, and starts the cycle again from it.
     *
     * The price a lot sold at is the new reference: the next entry is a drop
     * under where the last exit happened, not under where the side was armed.
     */
    private fun collect(lot: Lot) {
        val id = lot.sellOrderId ?: return
        val entry = OrderLog.all().firstOrNull { it.orderId == id } ?: return
        if (entry.matched <= lot.sold + 1e-9) return

        val gained = entry.matched - lot.sold
        val price = entry.realPrice
        lot.sold = entry.matched
        lot.proceeds += gained * price
        totals = totals.copy(sells = totals.sells + 1, got = totals.got + gained * price)
        store.saveTotals(totals)

        reference = price
        engine.log(
            "trade",
            "Ловец: продал " + String.format("%.1f", gained) + " по " +
                "${(price * 100).toInt()}¢ — считаю заново от неё",
        )

        if (lot.open <= 1e-6) {
            lots.remove(lot)
            lot.sellOrderId = null
        }
    }

    /** A lot whose window has closed: it settles at a dollar or at nothing. */
    private fun settle(lot: Lot) {
        lots.remove(lot)
        lot.sellOrderId?.let { id ->
            engine.session()?.let { s ->
                try {
                    ClobApi.cancelOrder(s.creds, s.account.signerAddress, id)
                } catch (e: Exception) {
                    // Already gone, which is what was wanted.
                }
            }
        }
        val winner = EventStats.winnerFor(lot.windowStart, Clock.nowSec())
        val settlement = if (lot.outcome == winner) lot.open else 0.0
        totals = totals.copy(settled = totals.settled + settlement)
        store.saveTotals(totals)
        val pnl = lot.proceeds + settlement - lot.cost
        engine.log(
            if (pnl >= 0) "trade" else "warn",
            "Ловец: окно закрылось, " + (if (pnl >= 0) "+" else "−") +
                "$" + String.format("%.2f", abs(pnl)),
        )
    }
}
