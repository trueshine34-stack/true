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
        /** The price asked for. What was paid can be better — see below. */
        val price: Double,
        val size: Double,
        val placedAt: Long,
        val windowStart: Long,
        var matched: Double,
        /**
         * The average price actually paid or received for [matched] shares.
         *
         * A marketable limit at 81c that sweeps offers at 78 and 79 is filled
         * at neither 81 nor one of them but at their average — and that is the
         * number every later decision rests on, because the exit is priced off
         * what the position cost. Null until something has actually traded.
         */
        var fillPrice: Double? = null,
        var status: String,
        var auto: Boolean = false,
    ) {
        /** What this entry cost per share, as well as it is known. */
        val realPrice: Double get() = fillPrice ?: price
    }

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
        /** The average price the matched part actually went at, if it is known. */
        fillPrice: Double? = null,
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
            fillPrice = fillPrice?.takeIf { it > 0.0 && matched > 1e-9 },
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

    /**
     * Shares bought in a recent window that no sell covers yet, per outcome.
     *
     * The app's own record of what it did, which is the only thing that can
     * answer "did every purchase get an exit?" without asking the exchange.
     * A buy counts once it has matched; a sell counts whether it has filled or
     * is merely resting, since a resting sell *is* the exit.
     *
     * This is what keeps the rule looking. Attention used to be a one-minute
     * timer from the moment of purchase, and a sell blocked for that minute —
     * by a rate limit, by the trade not being indexed yet, by a refusal — was
     * then never attempted again: the position dropped out of the sweep and the
     * panel said only that it was waiting for a purchase.
     */
    fun uncovered(windowStart: Long): Map<String, Double> {
        val recent = entries.filter { it.windowStart >= windowStart - WINDOW_SECONDS }
        val out = HashMap<String, Double>()
        for (entry in recent) {
            val covered = when {
                entry.action == "BUY" -> entry.matched
                entry.status == "cancelled" -> -entry.matched
                // A resting sell is an exit already arranged.
                else -> -maxOf(entry.matched, entry.size)
            }
            out[entry.asset] = (out[entry.asset] ?: 0.0) + covered
        }
        return out.filterValues { it > 1e-6 }
    }

    fun hasUncovered(windowStart: Long): Boolean = uncovered(windowStart).isNotEmpty()

    /** A purchase that no sell covers yet, at what it cost. */
    data class Lot(val shares: Double, val price: Double, val at: Long)

    /**
     * The buys of one outcome that still have no sell against them, oldest
     * first, each with its own price.
     *
     * A position's average is not what any single purchase cost. Pricing an
     * exit off the average puts every offer at one price — near the first buy's
     * — which is right for none of them: the lot bought at 32¢ is asked to wait
     * for the same price as the lot bought at 52¢, so one leaves money behind
     * and the other never fills. Each purchase deserves its own exit, and that
     * needs the purchases themselves, not their mean.
     *
     * Sells consume lots oldest first, resting ones included: an offer already
     * on the book is an exit already arranged for those shares.
     */
    fun uncoveredLots(asset: String): List<Lot> {
        val mine = entries.filter { it.asset == asset }.sortedBy { it.placedAt }
        val lots = ArrayList<Lot>()

        for (entry in mine) {
            if (entry.action != "BUY") continue
            if (entry.matched > 1e-9) {
                lots.add(Lot(entry.matched, entry.realPrice, entry.placedAt))
            }
        }

        for (entry in mine) {
            if (entry.action != "SELL") continue
            if (entry.status == "cancelled") continue
            var left = maxOf(entry.matched, entry.size)
            var i = 0
            while (left > 1e-9 && i < lots.size) {
                val lot = lots[i]
                val take = minOf(lot.shares, left)
                lots[i] = lot.copy(shares = lot.shares - take)
                left -= take
                if (lots[i].shares <= 1e-9) i += 1
            }
        }

        return lots.filter { it.shares > 1e-6 }
    }

    /** Is one particular asset's buy still working? */
    fun hasWorkingBuy(asset: String): Boolean = entries.any {
        it.asset == asset &&
            it.action == "BUY" &&
            (it.status == "resting" || it.status == "partial")
    }

    /**
     * Outcomes with an order of ours still on the book.
     *
     * A position covered by a resting sell counts as finished business
     * everywhere else — which is why the sweep stopped looking at it, and why a
     * floor that came into force afterwards never reached the offer sitting
     * under it. While one of our orders is working, its position is still the
     * rule's to manage.
     */
    fun workingAssets(action: String, windowStart: Long): Set<String> = entries
        .filter {
            it.action == action &&
                (it.status == "resting" || it.status == "partial") &&
                it.windowStart >= windowStart - WINDOW_SECONDS
        }
        .map { it.asset }
        .toSet()

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
    fun applyTrade(
        asset: String,
        action: String,
        price: Double,
        size: Double,
        tick: Double,
    ): Double {
        var left = size
        for (entry in entries.sortedBy { it.placedAt }) {
            if (left <= 1e-9) break
            if (entry.asset != asset || entry.action != action) continue
            if (entry.status != "resting" && entry.status != "partial") continue
            if (kotlin.math.abs(entry.price - price) > tick) continue

            val room = entry.size - entry.matched
            if (room <= 1e-9) continue
            val take = minOf(room, left)
            // The trade carries the price it actually went at, so the entry's
            // average is re-weighted rather than left at what was asked for.
            val had = entry.matched
            val was = entry.fillPrice ?: entry.price
            entry.matched = had + take
            entry.fillPrice = (was * had + price * take) / entry.matched
            entry.status = statusFor(entry.matched, entry.size, resting = true)
            left -= take
        }
        return left
    }

    /**
     * File a fill that belongs to no order this log knows about.
     *
     * There are several ways to end up here and all of them are real: a sale
     * made in the Polymarket app, an order placed before this process started,
     * an order whose response never came back. The trade happened either way,
     * and a panel that leaves it out is wrong about both the position and the
     * money — so it goes in as what it is, already filled.
     */
    @Synchronized
    fun recordFill(
        asset: String,
        conditionId: String,
        outcome: String,
        action: String,
        price: Double,
        size: Double,
        windowStart: Long,
        at: Long,
    ): Entry {
        val nowSec = at / 1000
        val entry = Entry(
            id = ids.incrementAndGet(),
            orderId = null,
            asset = asset,
            conditionId = conditionId,
            // The feed does not always name the side. The token id does, and
            // anything already filed against it knows the name.
            outcome = outcome.ifEmpty {
                entries.firstOrNull { it.asset == asset && it.outcome.isNotEmpty() }
                    ?.outcome.orEmpty()
            },
            action = action,
            price = price,
            size = size,
            placedAt = at,
            windowStart = if (windowStart > 0L) windowStart else nowSec - (nowSec % WINDOW_SECONDS),
            matched = size,
            // A fill with no order behind it is the price it happened at.
            fillPrice = price,
            status = "filled",
            auto = false,
        )
        entries.add(entry)
        while (entries.size > MAX) entries.removeAt(0)
        return entry
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
