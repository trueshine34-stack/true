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
     * The same shape at half a minute a rung, which spans the whole window.
     *
     * Five rungs at thirty seconds are spent by the halfway mark and the top
     * one then holds for the rest of the window — the ladder stops being a
     * ladder exactly when the window starts deciding. Ten rungs walk the same
     * distance in the same shape, one every thirty seconds, all the way to the
     * close.
     */
    val HALF_MINUTE = listOf(
        0.77, 0.80, 0.83, 0.86, 0.88, 0.90, 0.92, 0.94, 0.96, 0.97,
    )

    /**
     * @param elapsedSec seconds since the window opened
     * @param highWater highest price this outcome has reached this window, or
     *   null if nothing has been seen yet
     * @param floor the rung already reached, so the ladder cannot slip back
     */
    /** Seconds before each boundary that the next rung takes over. */
    const val DEFAULT_LEAD_SEC = 15

    /**
     * How long a rung holds before the clock moves to the next one.
     *
     * A minute here, which spreads five rungs over the whole window; the sell
     * rule asks for half that, spending them by the halfway mark so the higher
     * prices are asked while there is still time for the market to reach them.
     */
    const val DEFAULT_STEP_SEC = 60L

    /**
     * How far each further offer sits under the one before it.
     *
     * A position bought in two goes was offered twice at the same rung, which
     * is one offer for twice the size wearing two hats: the book fills the
     * first and leaves the second exactly where it was. Stepping each one down
     * means the second is reached before the price has to climb any further —
     * the first clip takes the rung, the rest take what the move on the way
     * there is worth.
     */
    const val STACK_STEP = 0.02

    /**
     * The price the [index]-th standing offer should be asking, best first.
     *
     * Never under one tick: a rung two cents from the floor cannot carry a
     * stack, and an offer at zero is not an offer.
     */
    fun stackedPrice(base: Double, index: Int, tick: Double = 0.01): Double {
        if (index <= 0) return base
        val stepped = base - STACK_STEP * index
        return maxOf(stepped, tick)
    }

    /**
     * How long before the close the ladder stops watching and starts resting.
     *
     * The last minute. Up to then the rung is a price to *wait for*: nothing
     * sits on the book, and the moment the bid reaches the rung the shares are
     * sold into it — so the rung is a floor rather than a ceiling, and a bid
     * that jumps straight past it pays what it jumped to. Inside the last
     * minute there is no longer time to wait for anything, so the offer goes
     * onto the book at the rung and takes whoever comes.
     */
    const val REST_LAST_SEC = 60L

    /**
     * Whether the offer belongs on the book now rather than in hand.
     *
     * [secondsLeft] is what is left of the position's own window, so a
     * position bought before its window opened is not treated as late.
     */
    fun restsNow(secondsLeft: Long, restSec: Long = REST_LAST_SEC): Boolean =
        secondsLeft <= restSec

    /** Whether the book is already paying the rung, so it can simply be taken. */
    fun reached(bid: Double?, rung: Double): Boolean =
        bid != null && bid > 0.0 && rung > 0.0 && bid >= rung - 1e-9

    fun stepFor(
        elapsedSec: Long,
        highWater: Double?,
        ladder: List<Double> = DEFAULT,
        floor: Int = 0,
        leadSec: Int = DEFAULT_LEAD_SEC,
        stepSec: Long = DEFAULT_STEP_SEC,
    ): Int {
        if (ladder.isEmpty()) return 0
        val last = ladder.size - 1

        // Before the window opens the elapsed time is negative, and integer
        // division truncates toward zero — so -30s would land on rung 0 either
        // way, but -90s would land on -1 and clamp wrong on some inputs. Floor
        // it first and let the range clamp do the rest.
        val every = if (stepSec > 0L) stepSec else DEFAULT_STEP_SEC
        val byClock = ((elapsedSec.coerceAtLeast(0L) + leadSec) / every)
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
        stepSec: Long = DEFAULT_STEP_SEC,
    ): Double = ladder[stepFor(elapsedSec, highWater, ladder, floor, leadSec, stepSec)]

    /** Seconds into the current five-minute window, from the wall clock. */
    fun elapsedInWindow(nowSec: Long): Long = nowSec % WINDOW_SECONDS
}
