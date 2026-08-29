package com.polybot.btc5m.bot

import java.util.concurrent.ConcurrentHashMap

/**
 * The price a five-minute window has to beat.
 *
 * It is the window's very first sixty-second reading, which is the number
 * Polymarket prints as the target and settles the market against. Once the
 * window has opened it cannot change, so it is worth knowing exactly once and
 * then remembering — which is what makes a readout over the timer possible at
 * all: the expensive half of "how far from the open" is a constant, and the
 * cheap half is the socket tick that arrives every second.
 *
 * Two places can supply it and they are the same series. The socket's own
 * history has it whenever the app was running when the window opened, and
 * costs nothing to read; the price-history endpoint has it always, and is
 * asked only when the socket cannot answer.
 */
object WindowOpen {

    /** How close to the boundary a reading has to be to *be* the open. */
    const val GRACE_MS = 2_000L

    private val known = ConcurrentHashMap<Long, Double>()
    private val asked = ConcurrentHashMap<Long, Long>()

    /** Don't hammer the endpoint while a window is still filling in. */
    private const val RETRY_MS = 3_000L

    /**
     * The opening value out of a series, or null if the series does not reach
     * back to the boundary.
     *
     * A window the app joined late begins, as far as its own history is
     * concerned, in the middle — and charting that as the line to beat would
     * be a lie, so anything that does not start at the start is refused.
     */
    fun pick(points: List<Tick>, windowStart: Long): Double? =
        pickAt(points.map { it.timestamp to it.value }, windowStart)

    /** The same, over the price-history endpoint's own points. */
    fun pickPoints(points: List<PolyPriceApi.Point>, windowStart: Long): Double? =
        pickAt(points.map { it.timestamp to it.value }, windowStart)

    private fun pickAt(points: List<Pair<Long, Double>>, windowStart: Long): Double? {
        val fromMs = windowStart * 1000
        val first = points
            .filter { it.first >= fromMs && it.second > 0.0 }
            .minByOrNull { it.first }
            ?: return null
        return if (first.first <= fromMs + GRACE_MS) first.second else null
    }

    /**
     * What the window opened at, or null until it is known.
     *
     * Never blocks: a miss starts a background fetch and answers null, and the
     * caller asks again a moment later.
     */
    fun of(windowStart: Long, feed: ChainlinkFeed?): Double? {
        if (windowStart <= 0L) return null
        known[windowStart]?.let { return it }

        val fromMs = windowStart * 1000
        if (feed != null) {
            pick(feed.twap60Between(fromMs, fromMs + GRACE_MS + 1_000), windowStart)?.let {
                known[windowStart] = it
                return it
            }
        }

        fetchLater(windowStart)
        return null
    }

    private fun fetchLater(windowStart: Long) {
        val now = System.currentTimeMillis()
        val last = asked[windowStart] ?: 0L
        if (now - last < RETRY_MS) return
        asked[windowStart] = now

        Thread {
            try {
                pickPoints(PolyPriceApi.window(windowStart), windowStart)?.let {
                    known[windowStart] = it
                }
            } catch (e: Exception) {
                // The next ask will try again; a missing target only means the
                // readout shows the price without the change for a moment.
            }
        }.start()
    }

    /** Test seam. */
    fun forget() {
        known.clear()
        asked.clear()
    }
}
