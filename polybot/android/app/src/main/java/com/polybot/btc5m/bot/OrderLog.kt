package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Every order this app sent, and what became of it.
 *
 * The open-orders listing only shows what is still working, so an order that
 * filled simply vanishes from it — which is precisely the one you want to see
 * afterwards. This keeps the whole round: what was placed, what matched, and
 * what was pulled.
 *
 * Entries are stamped with the five-minute window they belong to, because that
 * is the unit being traded; the desk shows the current window and nothing else.
 */
object OrderLog {

    /** resting — working on the book; filled/partial — matched; cancelled — pulled. */
    data class Entry(
        val id: Long,
        val orderId: String?,
        val asset: String,
        val conditionId: String,
        val outcome: String,
        val action: String,
        val price: Double,
        val size: Double,
        val placedAt: Long,
        val windowStart: Long,
        var matched: Double,
        var status: String,
        var auto: Boolean = false,
    )

    private val entries = CopyOnWriteArrayList<Entry>()
    private val ids = AtomicLong(0)
    private const val MAX = 300

    fun record(
        orderId: String?,
        asset: String,
        conditionId: String,
        outcome: String,
        action: String,
        price: Double,
        size: Double,
        matched: Double,
        auto: Boolean,
        /**
         * The window this order's market belongs to. Stamping it from the clock
         * instead put an order placed into the next window under the current
         * one, where the desk could never show it.
         */
        windowStart: Long,
    ): Entry {
        val now = System.currentTimeMillis()
        val nowSec = now / 1000
        val entry = Entry(
            id = ids.incrementAndGet(),
            orderId = orderId,
            asset = asset,
            conditionId = conditionId,
            outcome = outcome,
            action = action,
            price = price,
            size = size,
            placedAt = now,
            windowStart = if (windowStart > 0L) windowStart else nowSec - (nowSec % WINDOW_SECONDS),
            matched = matched,
            status = statusFor(matched, size, resting = true),
            auto = auto,
        )
        entries.add(entry)
        while (entries.size > MAX) entries.removeAt(0)
        return entry
    }

    private fun statusFor(matched: Double, size: Double, resting: Boolean): String = when {
        matched >= size - 1e-9 -> "filled"
        matched > 1e-9 -> if (resting) "partial" else "filled"
        resting -> "resting"
        else -> "cancelled"
    }

    /**
     * Bring the still-working entries in line with the exchange.
     *
     * An entry missing from the listing has either filled or been pulled, and
     * only the venue knows which — so it is asked, once, per entry. There are a
     * handful of these per window, and guessing "filled" would paint a cancel
     * green.
     */
    fun reconcile(
        open: List<ClobApi.OpenOrder>,
        lookup: (String) -> ClobApi.OpenOrder?,
    ) {
        val byId = open.associateBy { it.id }
        for (entry in entries) {
            if (entry.status != "resting" && entry.status != "partial") continue
            val id = entry.orderId ?: continue

            val remote = byId[id]
            if (remote != null) {
                entry.matched = remote.sizeMatched
                entry.status = statusFor(entry.matched, entry.size, resting = true)
                continue
            }
            val resolved = try {
                lookup(id)
            } catch (e: Exception) {
                continue
            }
            // Nothing back means the venue no longer knows this order — which
            // is what both a fill and a cancel look like. Calling it cancelled
            // marked filled sells as cancels and silently killed the buy-back,
            // so an unresolved order is left alone and settled by the trade
            // feed instead.
            if (resolved == null) continue
            entry.matched = resolved.sizeMatched
            entry.status = statusFor(entry.matched, entry.size, resting = false)
        }
    }

    /**
     * Are any of our sells still working?
     *
     * While one is, the rule has to keep looking: that is the only way it can
     * notice the fill that a buy-back hangs on. Orders older than the previous
     * window are not counted — their market has closed, and nothing more will
     * happen to them.
     */
    fun hasWorkingSells(windowStart: Long): Boolean = working("SELL", windowStart)

    /**
     * Are any of our buys still working?
     *
     * A limit buy that rests and fills a minute later has to wake the rule just
     * as much as a sell does — it is a position about to exist, and nothing else
     * in the loop knows it is coming. Without this the rule went quiet the
     * moment the order was placed and never came back to cover the fill.
     */
    fun hasWorkingBuys(windowStart: Long): Boolean = working("BUY", windowStart)

    /** Is one particular asset's buy still working? */
    fun hasWorkingBuy(asset: String): Boolean = entries.any {
        it.asset == asset &&
            it.action == "BUY" &&
            (it.status == "resting" || it.status == "partial")
    }

    private fun working(action: String, windowStart: Long): Boolean = entries.any {
        it.action == action &&
            (it.status == "resting" || it.status == "partial") &&
            it.windowStart >= windowStart - WINDOW_SECONDS
    }

    /**
     * Mark volume against a still-working order from a trade that happened.
     *
     * Matched by outcome, side and price, oldest first — that is everything the
     * trade feed carries in common with an order. A trade with no order to
     * match (sold from the Polymarket app, say) simply finds nothing here; the
     * buy-back works off the trade itself, not off this.
     */
    @Synchronized
    fun applyTrade(asset: String, action: String, price: Double, size: Double, tick: Double) {
        var left = size
        for (entry in entries.sortedBy { it.placedAt }) {
            if (left <= 1e-9) break
            if (entry.asset != asset || entry.action != action) continue
            if (entry.status != "resting" && entry.status != "partial") continue
            if (kotlin.math.abs(entry.price - price) > tick) continue

            val room = entry.size - entry.matched
            if (room <= 1e-9) continue
            val take = minOf(room, left)
            entry.matched += take
            entry.status = statusFor(entry.matched, entry.size, resting = true)
            left -= take
        }
    }

    /**
     * The size a single buy of this outcome was made in.
     *
     * Positions here are built up in equal clips — three lots of five rather
     * than one of fifteen — and a buy-back that went in as one block would take
     * the whole size at the first price it saw. The smallest buy recorded is
     * that clip.
     */
    fun buyLotFor(asset: String): Double? = entries
        .filter { it.action == "BUY" && it.asset == asset && it.size > 0.0 }
        .minOfOrNull { it.size }

    fun forWindow(windowStart: Long): List<Entry> =
        entries.filter { it.windowStart == windowStart }.sortedByDescending { it.placedAt }

    /** Everything still remembered, for scoring windows that have closed. */
    fun all(): List<Entry> = entries.toList()

    fun clear() = entries.clear()
}
