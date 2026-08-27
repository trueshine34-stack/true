package com.polybot.btc5m.bot

/**
 * The dearest a buy may be, early in a window.
 *
 * A side that already costs 54c in the first minute is being paid for a move
 * that has barely started, with the whole window left for it to come back:
 * there is little left to win on those shares and most of a dollar to lose.
 * The ceiling lifts as the window runs out of time to reverse — 77c through
 * the third minute, and nothing after it, when a dear side is dear because it
 * has very nearly won.
 *
 * It sits on the one function every order in the app goes through, by hand or
 * by rule, because a limit that only covers some of the ways to spend money is
 * not a limit.
 */
object BuyCap {

    const val FIRST_MINUTE_SEC = 60L
    const val EARLY_SEC = 180L
    const val FIRST_MINUTE_MAX = 0.54
    const val EARLY_MAX = 0.77

    /** Prices are cents; a hair of tolerance keeps 0.54 itself allowed. */
    private const val EPS = 1e-9

    fun ceiling(elapsedSec: Long): Double = when {
        elapsedSec < FIRST_MINUTE_SEC -> FIRST_MINUTE_MAX
        elapsedSec < EARLY_SEC -> EARLY_MAX
        else -> 1.0
    }

    fun blocked(price: Double, elapsedSec: Long): Boolean =
        price > ceiling(elapsedSec) + EPS

    /**
     * How far into its window a market is.
     *
     * A market with no window start is one this build cannot place in time, so
     * it is treated as the start of a window: the strict end of the rule, which
     * is the right way to be wrong about a limit.
     */
    fun elapsedFor(windowStart: Long, now: Long = Clock.nowSec()): Long =
        if (windowStart <= 0L) 0L else now - windowStart

    fun reason(elapsedSec: Long): String {
        val cents = Math.round(ceiling(elapsedSec) * 100)
        val span = if (elapsedSec < FIRST_MINUTE_SEC) "первую минуту" else "первые 3 минуты"
        return "В $span не покупаем дороже $cents¢"
    }
}
