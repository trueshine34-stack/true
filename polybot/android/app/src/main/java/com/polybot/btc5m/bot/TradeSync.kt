package com.polybot.btc5m.bot

/**
 * The exchange's own record of what actually traded, folded into the log.
 *
 * An order's fate can be learned three ways and only this one is unambiguous.
 * The open-orders listing drops an order the moment it stops working, which is
 * what a fill and a cancel both look like. Asking after a single order answers
 * for the ones the venue still remembers. The trade feed says what changed
 * hands, in shares, whatever placed it — including sales made in the Polymarket
 * app itself, and orders placed before this process started.
 *
 * It used to be read only inside the sell rule's sweep, so with the rule off,
 * or asleep, a sale that filled was never written down: the panel went on
 * showing the purchase as open and the round's profit was missing from the
 * total. Now the desk asks too, on its own timer, and both share this.
 */
object TradeSync {

    private val seen = HashSet<String>()

    /** Trades seen but not yet acted on by the rules. */
    private val fresh = ArrayList<DataApi.Trade>()

    private val windows = HashMap<String, Long>()

    @Volatile
    private var seeded = false

    @Volatile
    private var lastAt = 0L

    /** Why the last poll could not run, for the panel to show. */
    @Volatile
    var lastFault: String? = null
        private set

    /**
     * Ask the feed, unless it was asked too recently, and write what is new
     * into the order log.
     *
     * @return true if the feed was actually read.
     */
    @Synchronized
    fun poll(user: String, minGapMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAt < minGapMs) return false
        lastAt = now

        val trades = try {
            DataApi.trades(user)
        } catch (e: Exception) {
            lastFault = e.message ?: "сделки недоступны"
            return false
        }
        lastFault = null

        // The first pass only learns what already happened: every sale in
        // recent history would otherwise queue a buy-back.
        val first = !seeded
        seeded = true

        // Oldest first, so several fills of one order land in order.
        for (trade in trades.sortedBy { it.at }) {
            if (!seen.add(trade.key)) continue

            val meta = metaFor(trade.conditionId)
            val leftover = OrderLog.applyTrade(
                asset = trade.asset,
                action = trade.side,
                price = trade.price,
                size = trade.size,
                tick = meta?.tickSize ?: 0.01,
            )
            // Nothing here to match it against, and it happened anyway.
            if (leftover > 1e-6) {
                OrderLog.recordFill(
                    asset = trade.asset,
                    conditionId = trade.conditionId,
                    outcome = trade.outcome.ifEmpty { meta?.outcomes?.get(trade.asset) ?: "" },
                    action = trade.side,
                    price = trade.price,
                    size = leftover,
                    windowStart = meta?.windowStart ?: 0L,
                    at = trade.at,
                )
            }
            if (!first) fresh.add(trade)
        }

        // The key set only needs to outlive the newest page it can see.
        if (seen.size > 400) seen.retainAll(trades.map { it.key }.toSet())
        return true
    }

    /**
     * Is there anything the rules have not looked at yet?
     *
     * The desk polls this feed too, on its own timer, and whichever side asks
     * first is the one that sees a trade. If the desk saw it, the sell rule's
     * loop must stay awake long enough to drain it — otherwise a sale noticed
     * by the screen would settle the log, leave nothing uncovered, put the loop
     * to sleep, and the buy-back it should have started would never exist.
     */
    @Synchronized
    fun hasFresh(): Boolean = fresh.isNotEmpty()

    /** Trades the rules have not handled yet. Draining them marks them handled. */
    @Synchronized
    fun drain(): List<DataApi.Trade> {
        if (fresh.isEmpty()) return emptyList()
        val out = ArrayList(fresh)
        fresh.clear()
        return out
    }

    @Synchronized
    fun reset() {
        seen.clear()
        fresh.clear()
        windows.clear()
        seeded = false
        lastAt = 0L
        lastFault = null
    }

    private val metaCache = HashMap<String, Pair<Long, ClobApi.MarketMeta>>()

    private fun metaFor(conditionId: String): ClobApi.MarketMeta? {
        val now = System.currentTimeMillis()
        metaCache[conditionId]?.let { (at, meta) ->
            if (now - at < 60_000L) return meta
        }
        return try {
            ClobApi.marketMeta(conditionId).also { metaCache[conditionId] = now to it }
        } catch (e: Exception) {
            null
        }
    }
}
