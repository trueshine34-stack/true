package com.polybot.btc5m.bot

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The day's support and resistance, which do not move.
 *
 * A level is a price the market has turned at, and that fact does not expire
 * because the chart scrolled. Reading levels off the last four hours meant the
 * list was rebuilt every few minutes from a window that had slid — lines
 * appeared, moved a few dollars and vanished, and none of it was the market
 * changing its mind. A rule that refuses at a price which will not be there on
 * the next tick is a rule nobody can check against the screen.
 *
 * So this is a day's worth, kept rather than recomputed. Every reading over the
 * last twenty-four hours is merged into what is already known: a cluster near a
 * level that is already on the books confirms it — the level keeps its price,
 * so the line does not jitter, and gains whatever touches the new reading found
 * — and a cluster near nothing is a new level and is added. Nothing is dropped
 * for being old; a level goes only when a full day of readings has stopped
 * finding it, which is the market having actually left it behind.
 */
object DayLevels {

    /** How long a level survives without being seen again. */
    const val KEEP_SEC = 24 * 3600L

    /**
     * How close a fresh cluster has to be to count as the same level.
     *
     * A fifth of a percent — about a hundred and sixty dollars on eighty
     * thousand. It is the band the clustering itself works in, widened a
     * little: the same shelf read an hour apart comes back a few dollars
     * different, and treating that as a second level is how a shelf becomes
     * two lines and then four.
     */
    const val SAME = 0.002

    /** A level, and when a reading last confirmed it. */
    data class Held(
        val price: Double,
        val touches: Int,
        val low: Double,
        val high: Double,
        val seenAt: Long,
    )

    private val held = ConcurrentHashMap<Long, Held>()
    private var nextId = 0L

    @Volatile
    private var lastRead = 0L

    /** How often the day is re-read. Levels do not move faster than this. */
    const val EVERY_SEC = 60L

    /**
     * Reads the day again and merges what it finds, at most once a minute.
     *
     * Cheap enough to call from the tick: everything it reads is already in
     * memory, and between reads it does nothing at all.
     */
    fun refresh(nowSec: Long, force: Boolean = false) {
        if (!force && nowSec - lastRead < EVERY_SEC) return
        lastRead = nowSec

        val candles = BinanceCandles.day.list()
        val here = candles.lastOrNull()?.close ?: return
        if (here <= 0.0) return

        // Every tested price in the day, not a chart's pick of them: the
        // question here is "what is there", and the nearest one is chosen
        // later by whoever is asking.
        val fresh = Levels.tested(candles, here, keep = Int.MAX_VALUE)
        if (fresh.isEmpty()) return

        val near = here * SAME
        for (level in fresh) {
            val same = held.entries.firstOrNull { abs(it.value.price - level.price) <= near }
            if (same != null) {
                // The price it was first known at is the price it keeps. The
                // line on the screen staying where it is *is* the feature.
                held[same.key] = same.value.copy(
                    touches = maxOf(same.value.touches, level.touches),
                    low = minOf(same.value.low, level.low),
                    high = maxOf(same.value.high, level.high),
                    seenAt = nowSec,
                )
            } else {
                held[nextId++] = Held(
                    price = level.price,
                    touches = level.touches,
                    low = level.low,
                    high = level.high,
                    seenAt = nowSec,
                )
            }
        }

        // And a level a whole day of readings has stopped finding is one the
        // market has left behind.
        held.entries.removeAll { nowSec - it.value.seenAt > KEEP_SEC }
    }

    /** Everything known, as levels named by where price is now. */
    fun all(here: Double): List<Levels.Level> {
        if (here <= 0.0) return emptyList()
        return held.values
            .map {
                Levels.Level(
                    price = it.price,
                    touches = it.touches,
                    kind = if (it.price > here) "resistance" else "support",
                    low = it.low,
                    high = it.high,
                )
            }
            .sortedByDescending { it.price }
    }

    /** For the tests, and for a wipe when the series is replaced wholesale. */
    fun forget() {
        held.clear()
        lastRead = 0L
    }
}
