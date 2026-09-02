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
 * A closed window can never change, so an answer is kept for good — and kept
 * across restarts, which is the point of writing it down: the chart shows the
 * last few hours, and asking the venue for every one of them again on every
 * launch is a dozen requests for answers that were settled before the app was
 * closed. Anything genuinely not known is fetched on a single background
 * thread, newest first, because the window a person is looking at is nearly
 * always one of the last few.
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

    /**
     * How far back the answers are kept on disk.
     *
     * A day. The five-minute chart reaches back four hours at rest and twice
     * that pinched out, and a day covers both with room for the app to have
     * been closed overnight — at twelve bytes a window it is a few kilobytes.
     */
    const val KEEP_HOURS = 24L

    /**
     * Where the answers are written.
     *
     * A seam rather than a Context: everything else in this object is pure,
     * and the one place that has a Context — the plugin — hands it one at
     * startup. Without a store it simply keeps them in memory, as before.
     */
    interface Store {
        fun read(key: String): String?
        fun write(key: String, value: String)
    }

    @Volatile
    var store: Store? = null

    /** One file per coin: a window's winner is that coin's window's winner. */
    private fun key(): String = "results." + Coins.current.id

    /**
     * Read back what was settled before the app was last closed.
     *
     * Called at startup and again whenever the desk changes coin, which is the
     * other moment everything in memory here stops being about the market on
     * the screen.
     */
    fun reload(nowSec: Long = Clock.nowSec()) {
        known.clear()
        val raw = store?.read(key()) ?: return
        val oldest = nowSec - KEEP_HOURS * 3600
        for (part in raw.split(',')) {
            val at = part.substringBefore(':').toLongOrNull() ?: continue
            val side = part.substringAfter(':', "")
            if (at < oldest || (side != "Up" && side != "Down")) continue
            known[at] = side
        }
    }

    /**
     * Write them down, pruned to the window worth keeping.
     *
     * The whole map each time: it is a few hundred entries at most, and one
     * string is a great deal simpler to be sure of than a set of edits.
     */
    private fun save(nowSec: Long = Clock.nowSec()) {
        val out = store ?: return
        val oldest = nowSec - KEEP_HOURS * 3600
        val stale = known.keys.filter { it < oldest }
        stale.forEach { known.remove(it) }
        out.write(
            key(),
            known.entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value}" },
        )
    }

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

    /**
     * Forget every answer.
     *
     * A window's winner is the winner of that window *on one coin*. Kept by
     * window alone, the arrows over the chart would say what bitcoin did over
     * solana's candles — so the whole map goes when the desk switches.
     */
    fun forget() {
        wanted.clear()
        asking.clear()
        // Not simply cleared: the coin has changed, and that coin's own
        // answers are on disk. Reading them back is the same work as
        // forgetting, and it is what stops the chart re-asking for a day of
        // windows every time the desk is switched over and back.
        reload()
    }

    private fun drain() {
        val windowStart = wanted.pollFirst() ?: return
        try {
            val winner = EventStats.winnerFor(windowStart, Clock.nowSec())
            if (winner.isNotEmpty()) {
                known[windowStart] = winner
                save()
            }
        } catch (e: Exception) {
            // The next ask queues it again; a window that never answers simply
            // never gets an arrow, which is better than a wrong one.
        } finally {
            asking.remove(windowStart)
        }
    }
}
