package com.polybot.btc5m.bot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Closes a position the moment the book is paying enough, whatever the
 * standing offer is asking.
 *
 * The sell ladder prices an exit and waits for it. When the side runs, that is
 * exactly right. When it goes up, stops short of the rung and comes back, the
 * offer sat above the entire move — and this is the rule for that: it watches
 * the bid rather than the ask, and when what the bid pays after the fee is a
 * good enough gain on what the shares cost, it pulls the offer and sells into
 * the book.
 *
 * It works the desk's own positions, from the app's own record of them, which
 * is true the instant a buy fills rather than a minute later. Shares belonging
 * to the other rules are left alone: they price their own exits and two rules
 * cancelling each other's orders leaves a position naked between them.
 */
class TakeBot(
    private val engine: BotEngine,
    private val store: TakeStore,
    /** Shares the other bots are holding, which are theirs to exit. */
    private val botShares: (String) -> Double,
    private val onStateChanged: () -> Unit,
) {

    data class Watch(
        val asset: String,
        val outcome: String,
        val shares: Double,
        val cost: Double,
        val bid: Double,
        val gain: Double,
    )

    data class Totals(
        val takes: Int = 0,
        val shares: Double = 0.0,
        val got: Double = 0.0,
    )

    @Volatile
    var settings: TakePlan.Settings = store.load()
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastFault: String? = null
        private set

    @Volatile
    var watching: List<Watch> = emptyList()
        private set

    @Volatile
    var totals: Totals = Totals()
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private companion object {
        /** The move this exists to catch can be over inside a few seconds. */
        const val TICK_MS = 1_000L
    }

    fun update(next: TakePlan.Settings) {
        settings = next
        store.save(next)
        when {
            next.enabled && !running -> start()
            !next.enabled && running -> stop()
            else -> onStateChanged()
        }
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
                    lastFault = e.message ?: "сбой правила"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }
                delay(if (backoffMs > 0L) backoffMs else TICK_MS)
            }
        }
        engine.log(
            "info",
            "Забираю плюс: продаю по рынку от +" +
                Math.round(settings.gain * 100) + "% над ценой покупки",
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
        watching = emptyList()
        engine.log("info", "Забираю плюс: выключено")
        onStateChanged()
    }

    private fun tick() {
        val session = engine.session()
        if (session == null) {
            lastFault = "кошелёк не подключён"
            return
        }
        lastFault = null

        val market = engine.currentMarket()
        if (market == null) {
            watching = emptyList()
            onStateChanged()
            return
        }

        val next = ArrayList<Watch>()
        for ((asset, outcome) in listOf(
            market.up.tokenId to "Up",
            market.down.tokenId to "Down",
        )) {
            val lots = OrderLog.heldLots(asset)
            if (lots.isEmpty()) continue

            // What the other rules are holding is theirs to sell.
            val mine = lots.sumOf { it.shares } - botShares(asset)
            if (mine < market.minimumOrderSize - 1e-6) continue

            val spent = lots.sumOf { it.shares * it.price }
            val held = lots.sumOf { it.shares }
            val cost = if (held > 0.0) spent / held else 0.0

            val bid = try {
                ClobApi.bestBid(asset)
            } catch (e: Exception) {
                null
            }

            next.add(
                Watch(
                    asset = asset,
                    outcome = outcome,
                    shares = mine,
                    cost = cost,
                    bid = bid ?: 0.0,
                    gain = TakePlan.gainAt(cost, bid),
                ),
            )

            if (TakePlan.ready(cost, bid, settings) && bid != null) {
                take(session, market, asset, outcome, mine, cost, bid)
            }
        }

        watching = next
        onStateChanged()
    }

    /**
     * Pulls our own offers on that side and sells into the book.
     *
     * The offers have to go first: the shares under a resting sell are spoken
     * for, and asking for them again is refused for "not enough balance" —
     * which is true and useless.
     */
    private fun take(
        session: BotEngine.Session,
        market: Market,
        asset: String,
        outcome: String,
        shares: Double,
        cost: Double,
        bid: Double,
    ) {
        val open = try {
            ClobApi.openOrders(session.creds, session.account.signerAddress)
        } catch (e: Exception) {
            lastFault = e.message ?: "не прочитать ордера"
            return
        }
        for (order in open.filter { it.assetId == asset && it.side == "SELL" }) {
            try {
                ClobApi.cancelOrder(session.creds, session.account.signerAddress, order.id)
            } catch (e: Exception) {
                // It may have filled in between, which the next pass will see.
            }
        }

        val price = TakePlan.takePrice(bid, market.tickSize)
        val result = try {
            engine.placeManualOrder(
                tokenId = asset,
                conditionId = market.conditionId,
                side = "SELL",
                price = price,
                size = shares,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            lastFault = e.message ?: "ошибка сети"
            return
        }

        if (!result.success) {
            lastFault = result.error ?: "отказ CLOB"
            engine.log("error", "Забираю плюс: $lastFault")
            return
        }

        val fill = Orders.filled("SELL", result.makingAmount, result.takingAmount)
        totals = totals.copy(
            takes = totals.takes + 1,
            shares = totals.shares + fill.shares,
            got = totals.got + fill.usd,
        )
        engine.log(
            "trade",
            "Забрал плюс: " + String.format("%.1f", fill.shares) + " $outcome по " +
                "${(price * 100).toInt()}¢ — куплено по ${(cost * 100).toInt()}¢, " +
                "+" + Math.round(TakePlan.gainAt(cost, bid) * 100) + "%",
        )
    }
}
