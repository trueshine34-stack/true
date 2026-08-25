package com.polybot.btc5m.bot

/**
 * Which rung of the auto-sell ladder applies right now.
 *
 * The ladder asks more as the window runs on: early, a modest price gets you
 * out quickly; later, the side that is winning is worth more, so it is worth
 * holding out for. One rung per minute of the five-minute window.
 *
 * The clock moves it a little early. A rung that flips exactly on the minute
 * puts the replacement order out at the moment everyone else's does, into the
 * book as it turns; moving fifteen seconds ahead of the boundary gets the offer
 * in place before that. The lead applies to every rung, so the sequence keeps
 * its one-minute spacing and only shifts.
 *
 * The clock is not the only thing that advances it. If the market has already
 * traded through a rung, resting there would be leaving money on the table for
 * no reason, so the ladder jumps past every rung the price has already cleared
 * — in the first minute or any other. Time and price each propose a rung and
 * the higher one wins.
 *
 * It only ever moves up. A price that spiked and fell back has still proved the
 * rung beneath it was too cheap, and dropping back down would sell into the dip
 * it just made.
 */
object SellLadder {

    val DEFAULT = listOf(0.77, 0.84, 0.89, 0.93, 0.97)

    /**
     * @param elapsedSec seconds since the window opened
     * @param highWater highest price this outcome has reached this window, or
     *   null if nothing has been seen yet
     * @param floor the rung already reached, so the ladder cannot slip back
     */
    /** Seconds before each minute boundary that the next rung takes over. */
    const val DEFAULT_LEAD_SEC = 15

    fun stepFor(
        elapsedSec: Long,
        highWater: Double?,
        ladder: List<Double> = DEFAULT,
        floor: Int = 0,
        leadSec: Int = DEFAULT_LEAD_SEC,
    ): Int {
        if (ladder.isEmpty()) return 0
        val last = ladder.size - 1

        // Before the window opens the elapsed time is negative, and integer
        // division truncates toward zero — so -30s would land on rung 0 either
        // way, but -90s would land on -1 and clamp wrong on some inputs. Floor
        // it first and let the range clamp do the rest.
        val byClock = ((elapsedSec.coerceAtLeast(0L) + leadSec) / 60L)
            .toInt()
            .coerceIn(0, last)
        // Every rung the price has already cleared is behind us; the next one up
        // is where the order belongs.
        val byPrice = if (highWater == null) 0 else ladder.count { highWater > it }

        return maxOf(byClock, byPrice, floor).coerceIn(0, last)
    }

    fun priceFor(
        elapsedSec: Long,
        highWater: Double?,
        ladder: List<Double> = DEFAULT,
        floor: Int = 0,
        leadSec: Int = DEFAULT_LEAD_SEC,
    ): Double = ladder[stepFor(elapsedSec, highWater, ladder, floor, leadSec)]

    /** Seconds into the current five-minute window, from the wall clock. */
    fun elapsedInWindow(nowSec: Long): Long = nowSec % WINDOW_SECONDS
}
