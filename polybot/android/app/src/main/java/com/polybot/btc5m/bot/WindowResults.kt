package com.polybot.btc5m.bot

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque

/**
 * How each five-minute window actually settled, by Polymarket's own reckoning.
 *
 * Which is not the same question as which way the candle closed. Polymarket
 * settles a window against its own price series — a sixty-second average, read
 * at the boundary and again at the close — so a Binance candle that finishes a
 * dollar green can settle Down, and the chart on the screen says one thing
 * while the market that paid out says another. Over a candle, only one of the
 * two is worth an arrow.
 *
 * A closed window can never change, so an answer is kept for good. Anything not
 * known yet is fetched on a single background thread, newest first, because the
 * window a person is looking at is nearly always one of the last few.
 */
object WindowResults {

    private val known = ConcurrentHashMap<Long, String>()
    private val wanted = LinkedBlockingDeque<Long>()
    private val asking = ConcurrentHashMap<Long, Boolean>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "window-results").apply { isDaemon = true }
    }

    /** How many unfetched windows are queued at once. Older ones can wait. */
    private const val QUEUE = 64

    /** What is already known — the map the screen draws from. */
    fun known(): Map<Long, String> = HashMap(known)

    /** The answer for one window, or empty while it is unknown. */
    fun of(windowStart: Long): String = known[windowStart].orEmpty()

    /**
     * Asks for these windows, newest first, and answers with what is already
     * known. Never blocks: the rest arrive over the next few seconds and the
     * caller asks again.
     */
    fun want(windows: List<Long>, nowSec: Long): Map<Long, String> {
        for (windowStart in windows.sortedDescending()) {
            if (windowStart <= 0L) continue
            // A window still running has not settled and has no answer yet.
            if (nowSec < windowStart + WINDOW_SECONDS) continue
            if (known.containsKey(windowStart)) continue
            if (asking.putIfAbsent(windowStart, true) != null) continue
            if (wanted.size >= QUEUE) wanted.pollLast()
            wanted.addFirst(windowStart)
            worker.execute(::drain)
        }
        return known()
    }

    private fun drain() {
        val windowStart = wanted.pollFirst() ?: return
        try {
            val winner = EventStats.winnerFor(windowStart, Clock.nowSec())
            if (winner.isNotEmpty()) known[windowStart] = winner
        } catch (e: Exception) {
            // The next ask queues it again; a window that never answers simply
            // never gets an arrow, which is better than a wrong one.
        } finally {
            asking.remove(windowStart)
        }
    }
}
