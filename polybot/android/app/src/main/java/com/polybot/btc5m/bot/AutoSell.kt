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
     * Markets a bot is trading right now. Their positions are left alone: the
     * terminal bot's ladder and the pair bot's legs are part of strategies that
     * decide their own exits, and blanketing them with a sell at one price
     * would quietly break both.
     */
    private val busyMarkets: () -> Set<String>,
    private val onStateChanged: () -> Unit,
) {
    data class Settings(
        val enabled: Boolean = false,
        /** Limit price for every sell placed. */
        val price: Double = 0.97,
        /** How often to try again while the venue is still refusing. */
        val retryEverySec: Int = 7,
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

    val rows = CopyOnWriteArrayList<Row>()

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val attempts = HashMap<String, Int>()
    private val metaCache = HashMap<String, Pair<Long, ClobApi.MarketMeta>>()

    private companion object {
        const val META_TTL_MS = 60_000L
    }

    fun update(next: Settings) {
        settings = next
        if (next.enabled) start() else stop()
        onStateChanged()
    }

    fun start() {
        if (running) return
        running = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            while (isActive && running) {
                try {
                    sweep()
                } catch (e: Exception) {
                    engine.log("error", "Автопродажа: ${e.message}")
                }
                delay(settings.retryEverySec.coerceAtLeast(1) * 1000L)
            }
        }
        engine.log(
            "info",
            "Автопродажа включена по ${(settings.price * 100).toInt()}¢, " +
                "повтор каждые ${settings.retryEverySec} с",
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
        val session = engine.session() ?: return
        val positions = DataApi.positions(session.account.funderAddress)
        val open = ClobApi.openOrders(session.creds, session.account.signerAddress)
        val busy = busyMarkets()
        lastSweepAt = System.currentTimeMillis()

        val next = ArrayList<Row>()
        for (position in positions) {
            if (position.redeemable || position.size <= 0.0) continue
            if (position.conditionId in busy) {
                next.add(rowFor(position, open, "у бота"))
                continue
            }

            val sells = open.filter { it.assetId == position.asset && it.side == "SELL" }
            val resting = sells.sumOf { it.remaining }
            val uncovered = position.size - resting

            val meta = metaFor(position.conditionId)
            val status = when {
                meta == null -> "нет данных рынка"
                meta.closed || !meta.acceptingOrders -> "рынок закрыт"
                uncovered < meta.minimumOrderSize -> "покрыто"
                else -> tryPlace(position, uncovered, meta)
            }

            next.add(rowFor(position, open, status))
        }

        // Positions that closed take their retry counters with them.
        attempts.keys.retainAll(next.map { it.asset }.toSet())
        rows.clear()
        rows.addAll(next)
        onStateChanged()
    }

    private fun rowFor(
        position: Position,
        open: List<ClobApi.OpenOrder>,
        status: String,
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
        )
    }

    private fun tryPlace(
        position: Position,
        size: Double,
        meta: ClobApi.MarketMeta,
    ): String {
        val price = snapToTick(settings.price, meta.tickSize)
        attempts[position.asset] = (attempts[position.asset] ?: 0) + 1

        return try {
            val result = engine.placeManualOrder(
                tokenId = position.asset,
                conditionId = position.conditionId,
                side = "SELL",
                price = price,
                size = size,
                orderType = "GTC",
            )
            if (result.success) {
                attempts.remove(position.asset)
                "выставлено"
            } else {
                // Almost always "shares not sellable yet"; the next sweep retries.
                result.error ?: "отказ CLOB"
            }
        } catch (e: Exception) {
            e.message ?: "ошибка сети"
        }
    }

    /** A sell must never round down onto a worse price than asked for. */
    private fun snapToTick(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = kotlin.math.ceil(price / tick - 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(tick, 1.0 - tick)
    }
}
