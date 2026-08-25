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
        /** Buy the same size back if the price falls far enough after a sale. */
        val rebuyEnabled: Boolean = false,
        /** How far below the sale price the buy-back triggers, as a fraction. */
        val rebuyDropPct: Double = 0.20,
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

    val rows = CopyOnWriteArrayList<Row>()

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val attempts = HashMap<String, Int>()
    private val metaCache = HashMap<String, Pair<Long, ClobApi.MarketMeta>>()

    /** Per-outcome ladder state, reset when the window rolls. */
    private data class Rung(val windowStart: Long, var highWater: Double, var step: Int)

    private val rungs = HashMap<String, Rung>()

    /** A sell we placed, so its fill can be recognised when it happens. */
    private data class Placed(
        val asset: String,
        val conditionId: String,
        val price: Double,
        val size: Double,
        var matched: Double = 0.0,
    )

    private val placed = HashMap<String, Placed>()

    /** Sold shares waiting for a cheap enough price to be bought back. */
    data class Rebuy(
        val asset: String,
        val conditionId: String,
        val shares: Double,
        val soldAt: Double,
        val trigger: Double,
        val windowStart: Long,
    )

    val rebuys = CopyOnWriteArrayList<Rebuy>()

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
            "Автопродажа лесенкой " +
                settings.ladder.joinToString("/") { "${(it * 100).toInt()}" } +
                "¢, повтор каждые ${settings.retryEverySec} с",
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
        placed.clear()
        rebuys.clear()
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
        val now = Clock.nowSec()
        val windowStart = now - SellLadder.elapsedInWindow(now)
        lastSweepAt = System.currentTimeMillis()

        val next = ArrayList<Row>()
        for (position in positions) {
            if (position.redeemable || position.size <= 0.0) continue

            val rung = trackRung(position, windowStart, now)
            val target = settings.ladder.getOrElse(rung.step) { settings.ladder.last() }

            // Only the part of the position no bot is holding.
            val mine = position.size - botShares(position.asset)

            val meta = metaFor(position.conditionId)
            val status = when {
                meta == null -> "нет данных рынка"
                meta.closed || !meta.acceptingOrders -> "рынок закрыт"
                mine < meta.minimumOrderSize -> "у бота"
                else -> reconcile(position, open, meta, target, mine)
            }
            next.add(rowFor(position, open, status, rung.step, target))
        }

        noteFills(session, open, windowStart)
        runRebuys(windowStart)

        val live = next.map { it.asset }.toSet()
        attempts.keys.retainAll(live)
        rungs.keys.retainAll(live)
        rows.clear()
        rows.addAll(next)
        onStateChanged()
    }

    /**
     * Notice when one of our own sells actually filled.
     *
     * A position simply shrinking is not enough to go on — it shrinks when the
     * user sells by hand too, and buying that back would be the opposite of
     * what they meant. Only orders this rule placed are tracked, and only their
     * matched size counts.
     */
    private fun noteFills(
        session: BotEngine.Session,
        open: List<ClobApi.OpenOrder>,
        windowStart: Long,
    ) {
        val byId = open.associateBy { it.id }
        val finished = ArrayList<String>()

        for ((id, order) in placed) {
            val remote = byId[id]
            val matched = if (remote != null) {
                remote.sizeMatched
            } else {
                // Gone from the book: filled, or cancelled. Only the venue knows.
                val resolved = try {
                    ClobApi.order(session.creds, session.account.signerAddress, id)
                } catch (e: Exception) {
                    continue
                }
                finished.add(id)
                resolved?.sizeMatched ?: 0.0
            }

            val delta = matched - order.matched
            if (delta <= 1e-9) continue
            order.matched = matched

            if (!settings.rebuyEnabled) continue
            rebuys.add(
                Rebuy(
                    asset = order.asset,
                    conditionId = order.conditionId,
                    shares = delta,
                    soldAt = order.price,
                    trigger = order.price * (1.0 - settings.rebuyDropPct.coerceIn(0.0, 0.95)),
                    windowStart = windowStart,
                ),
            )
            engine.log(
                "trade",
                "Продано " + String.format("%.1f", delta) + " по " +
                    "${(order.price * 100).toInt()}¢ · докуп при " +
                    "${(order.price * (1.0 - settings.rebuyDropPct) * 100).toInt()}¢",
            )
        }
        finished.forEach { placed.remove(it) }
    }

    /**
     * Buy back what was sold, once it is cheap enough again.
     *
     * A buy-back only makes sense inside the window it was sold in: after the
     * close the outcome is settled and the price means something else entirely,
     * so anything left over is dropped rather than carried across.
     */
    private fun runRebuys(windowStart: Long) {
        if (rebuys.isEmpty()) return
        if (!settings.rebuyEnabled) {
            rebuys.clear()
            return
        }

        val done = ArrayList<Rebuy>()
        for (rebuy in rebuys) {
            if (rebuy.windowStart != windowStart) {
                done.add(rebuy)
                continue
            }
            val ask = try {
                ClobApi.quote(rebuy.asset).bestAsk
            } catch (e: Exception) {
                continue
            } ?: continue
            if (ask > rebuy.trigger) continue

            val meta = metaFor(rebuy.conditionId) ?: continue
            if (meta.closed || !meta.acceptingOrders) {
                done.add(rebuy)
                continue
            }
            val size = maxOf(rebuy.shares, meta.minimumOrderSize)
            val result = try {
                engine.placeManualOrder(
                    tokenId = rebuy.asset,
                    conditionId = rebuy.conditionId,
                    side = "BUY",
                    price = ask,
                    size = size,
                    orderType = "GTC",
                )
            } catch (e: Exception) {
                engine.log("error", "Автодокуп не прошёл: ${e.message}")
                continue
            }
            if (result.success) {
                engine.log(
                    "trade",
                    "Автодокуп " + String.format("%.1f", size) + " по " +
                        "${(ask * 100).toInt()}¢ (продано по ${(rebuy.soldAt * 100).toInt()}¢)",
                )
                done.add(rebuy)
            }
        }
        rebuys.removeAll(done)
    }

    /**
     * Advance this outcome's rung. The high-water mark is what lets the ladder
     * skip ahead of the clock, and it is per window: a new window starts the
     * climb again from the bottom.
     */
    private fun trackRung(position: Position, windowStart: Long, now: Long): Rung {
        val existing = rungs[position.asset]
        val rung = if (existing == null || existing.windowStart != windowStart) {
            Rung(windowStart, position.curPrice, 0).also { rungs[position.asset] = it }
        } else {
            existing
        }
        if (position.curPrice > rung.highWater) rung.highWater = position.curPrice
        rung.step = SellLadder.stepFor(
            elapsedSec = now - windowStart,
            highWater = rung.highWater,
            ladder = settings.ladder,
            floor = rung.step,
        )
        return rung
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
            step = step,
            target = target,
        )
    }

    private fun tryPlace(position: Position, size: Double, price: Double): String {
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
                result.orderId?.let {
                    placed[it] = Placed(position.asset, position.conditionId, price, size)
                }
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
