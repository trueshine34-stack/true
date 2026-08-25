package com.polybot.btc5m.bot

/**
 * What each five-minute event came to.
 *
 * A window is the unit this app trades, so it is the unit worth scoring. Every
 * order the app sent is filed under the window of the market it went to, which
 * makes the arithmetic simple: money out on buys, money in on sells, and
 * whatever shares were still held when the window closed settle at a dollar or
 * at nothing depending on which side won.
 *
 * The winner comes from the price series Polymarket itself charts and settles
 * on — the same thirty-second TWAP — rather than from anything this app
 * modelled. Up wins when the window closes above where it opened.
 */
object EventStats {

    private const val FEE_RATE = 0.07

    data class Event(
        val windowStart: Long,
        /** "Up", "Down", or empty while the window is still running. */
        val winner: String,
        val settled: Boolean,
        /** Money paid for shares, fees included. */
        val spent: Double,
        /** Money taken for shares, fees deducted. */
        val got: Double,
        /** Shares still held per outcome when the window closed. */
        val held: Double,
        /** What those shares settled for. */
        val settlement: Double,
        val pnl: Double,
        val trades: Int,
    )

    private fun fee(price: Double, size: Double): Double =
        if (price <= 0.0 || price >= 1.0) 0.0 else FEE_RATE * price * (1 - price) * size

    /** Which side a window closed on, or empty if it is not decided yet. */
    fun winnerFor(windowStart: Long, nowSec: Long): String {
        if (nowSec < windowStart + WINDOW_SECONDS) return ""
        return try {
            val points = PolyPriceApi.window(windowStart)
            if (points.size < 2) return ""
            val open = points.first().value
            val close = points.last().value
            if (close > open) "Up" else "Down"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Score the windows the order log still remembers, newest first.
     *
     * Only matched volume counts. An order that rested and was pulled moved no
     * money and belongs in neither column.
     */
    fun recent(limit: Int = 12, nowSec: Long = Clock.nowSec()): List<Event> {
        val byWindow = OrderLog.all()
            .filter { it.matched > 1e-9 }
            .groupBy { it.windowStart }

        return byWindow.keys
            .sortedDescending()
            .take(limit)
            .map { windowStart ->
                val entries = byWindow.getValue(windowStart)
                val winner = winnerFor(windowStart, nowSec)
                val settled = nowSec >= windowStart + WINDOW_SECONDS

                var spent = 0.0
                var got = 0.0
                val heldByOutcome = HashMap<String, Double>()

                for (entry in entries) {
                    val notional = entry.price * entry.matched
                    if (entry.action == "BUY") {
                        spent += notional + fee(entry.price, entry.matched)
                        heldByOutcome[entry.outcome] =
                            (heldByOutcome[entry.outcome] ?: 0.0) + entry.matched
                    } else {
                        got += notional - fee(entry.price, entry.matched)
                        heldByOutcome[entry.outcome] =
                            (heldByOutcome[entry.outcome] ?: 0.0) - entry.matched
                    }
                }

                // Shares still held when the window closed pay a dollar on the
                // winning side and nothing on the other.
                val held = heldByOutcome.values.sumOf { maxOf(it, 0.0) }
                val settlement = if (settled && winner.isNotEmpty()) {
                    heldByOutcome.entries.sumOf { (outcome, size) ->
                        if (outcome == winner) maxOf(size, 0.0) else 0.0
                    }
                } else {
                    0.0
                }

                Event(
                    windowStart = windowStart,
                    winner = winner,
                    settled = settled,
                    spent = spent,
                    got = got,
                    held = held,
                    settlement = settlement,
                    // While a window is still running its held shares have no
                    // settled value, so the figure is what has been realised.
                    pnl = got + settlement - spent,
                    trades = entries.size,
                )
            }
    }

    /** Everything the session has made, by the same arithmetic. */
    fun sessionPnl(nowSec: Long = Clock.nowSec()): Double =
        recent(limit = 500, nowSec = nowSec).sumOf { it.pnl }
}
