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
        /** Matched volume already turned into a buy-back, so it counts once. */
        var rebuyAccounted: Double = 0.0,
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
            windowStart = nowSec - (nowSec % WINDOW_SECONDS),
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
            entry.matched = resolved?.sizeMatched ?: entry.matched
            entry.status = statusFor(entry.matched, entry.size, resting = false)
        }
    }

    /**
     * Sell volume that has matched since the last time this was asked.
     *
     * Keyed off every sell the app sent, not just the ones a rule placed: a
     * limit sell put on by hand fills exactly the same way, and a buy-back that
     * only reacted to the rule's own orders ignored the case the user actually
     * meant.
     */
    @Synchronized
    fun takeSellFills(): List<Entry> {
        val out = ArrayList<Entry>()
        for (entry in entries) {
            if (entry.action != "SELL") continue
            if (entry.matched - entry.rebuyAccounted <= 1e-9) continue
            out.add(entry.copy(matched = entry.matched - entry.rebuyAccounted))
            entry.rebuyAccounted = entry.matched
        }
        return out
    }

    /**
     * Are any of our sells still working?
     *
     * While one is, the rule has to keep looking: that is the only way it can
     * notice the fill that a buy-back hangs on. Orders older than the previous
     * window are not counted — their market has closed, and nothing more will
     * happen to them.
     */
    fun hasWorkingSells(windowStart: Long): Boolean = entries.any {
        it.action == "SELL" &&
            (it.status == "resting" || it.status == "partial") &&
            it.windowStart >= windowStart - WINDOW_SECONDS
    }

    fun forWindow(windowStart: Long): List<Entry> =
        entries.filter { it.windowStart == windowStart }.sortedByDescending { it.placedAt }

    fun clear() = entries.clear()
}
