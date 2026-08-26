package com.polybot.btc5m.bot

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Selling by profit rather than by clock.
 *
 * The ladder asks a fixed price at a fixed minute, which ignores what the
 * position cost: 84c is a good sale bought at 60c and a poor one bought at 82c.
 * This rule prices off the buy instead — get out as soon as the position is up
 * by the asked-for margin, whenever that happens.
 *
 * The margin is measured on what the sale actually pays. Resting at `avg x 1.2`
 * and calling it twenty percent is wrong by the fee, which comes out of the
 * proceeds; solving for the price whose *net* is `avg x 1.2` is a quadratic
 * with a closed form, so the number asked for is the number received.
 *
 * Two things bend it. A position built out of several purchases is sold one
 * purchase at a time, a few seconds apart, each priced off its own cost. And in
 * the last minute of a window the margin gives way to a floor: ninety cents or
 * better, taken the instant the book reaches it. Not the smallest profit that
 * clears the fee — by then the winning side is walking to a dollar, and a
 * two-cent win sells a position that was about to be worth ninety-odd. The
 * fifty seconds before that carry a lower floor of their own, seventy-seven,
 * which only ever raises an offer and never lowers one.
 */
object SellPercent {

    const val DEFAULT_GAIN = 0.20

    /** Seconds between slices, so a rising price can carry the next one higher. */
    const val DEFAULT_SLICE_GAP_SEC = 2

    /** How near the close the rule stops holding out for its margin. */
    const val DEFAULT_PANIC_SEC = 60

    /**
     * The least the rule will sell for in the last minute.
     *
     * Not "any profit": a five-minute market in its last minute is nearly
     * decided, and the winning side is on its way to a dollar. Taking two cents
     * of profit there sells a position that was about to be worth ninety-odd —
     * so the offer sits at ninety and is taken the moment the book touches it.
     */
    const val DEFAULT_CLOSE_FLOOR = 0.90

    /**
     * The floor for the stretch just before that one.
     *
     * The same reasoning, one step earlier and one step lower. By the fourth
     * minute the window has usually picked a side, and a lot bought cheap is
     * worth more than its margin asks — so the offer stops going out at fifty-
     * odd cents and waits at seventy-seven.
     */
    const val DEFAULT_LATE_FLOOR = 0.77

    /** How long that stretch runs, ending where the last minute begins. */
    const val DEFAULT_LATE_BAND_SEC = 50

    private const val FEE_RATE = 0.07

    /** What one share pays out after the taker fee. */
    fun netSell(price: Double): Double =
        if (price <= 0.0 || price >= 1.0) price else price - FEE_RATE * price * (1 - price)

    /**
     * The price whose proceeds are the buy price plus [gain].
     *
     * `net(p) = 0.93p + 0.07p²`, so `0.07p² + 0.93p - target = 0`.
     */
    fun targetPrice(avgPrice: Double, gain: Double, tick: Double): Double {
        if (avgPrice <= 0.0) return tick
        val wanted = avgPrice * (1.0 + gain.coerceAtLeast(0.0))
        val b = 1.0 - FEE_RATE
        val price = (-b + sqrt(b * b + 4 * FEE_RATE * wanted)) / (2 * FEE_RATE)
        return snapUp(price, tick)
    }

    /**
     * Where the next lot should rest.
     *
     * @param resting the highest price already resting for this position, if any
     * @param secondsLeft until the window closes
     * @param bestBid what the book would pay right now
     * @param closeFloor the least the last minute will sell for
     */
    fun priceFor(
        avgPrice: Double,
        gain: Double,
        tick: Double,
        resting: Double?,
        secondsLeft: Long,
        panicSec: Int,
        bestBid: Double?,
        closeFloor: Double = DEFAULT_CLOSE_FLOOR,
        lateFloor: Double = DEFAULT_LATE_FLOOR,
        lateBandSec: Int = DEFAULT_LATE_BAND_SEC,
    ): Double {
        val target = targetPrice(avgPrice, gain, tick)

        // Out of time: ninety or better, taken the instant the book reaches it.
        // Not the smallest profit that clears the fee — in the last minute of a
        // five-minute market the winning side is walking to a dollar, and a
        // two-cent win sells a position that was about to be worth ninety-odd.
        if (!holdingOut(secondsLeft, panicSec)) {
            val floor = snapUp(closeFloor, tick)
            if (bestBid != null && bestBid >= floor) return snapDown(bestBid, tick)
            return floor
        }

        // The stretch before that one has a floor of its own, and it only ever
        // raises: a lot whose margin already asks more than seventy-seven keeps
        // asking it. A floor is a minimum, not a target.
        if (!holdingOut(secondsLeft, panicSec + lateBandSec.coerceAtLeast(0))) {
            return maxOf(target, snapUp(lateFloor, tick))
        }

        // Each slice above the last: the fill proved the price was there.
        if (resting != null && resting >= target) return snapUp(resting + tick, tick)
        return target
    }

    /**
     * The least this moment will sell for, or null while the margin alone
     * decides.
     *
     * The reason a resting offer can go stale in percent mode at all: each lot
     * is priced off its own cost and left alone, but a floor applies to every
     * lot at once, so an offer placed before the floor took effect is now too
     * cheap and has to be pulled.
     */
    fun floorFor(
        secondsLeft: Long,
        panicSec: Int,
        lateBandSec: Int = DEFAULT_LATE_BAND_SEC,
        closeFloor: Double = DEFAULT_CLOSE_FLOOR,
        lateFloor: Double = DEFAULT_LATE_FLOOR,
    ): Double? = when {
        !holdingOut(secondsLeft, panicSec) -> closeFloor
        !holdingOut(secondsLeft, panicSec + lateBandSec.coerceAtLeast(0)) -> lateFloor
        else -> null
    }

    /** True while the rule should still be holding out for its margin. */
    fun holdingOut(secondsLeft: Long, panicSec: Int): Boolean =
        secondsLeft < 0 || secondsLeft > panicSec

    /**
     * How much to offer in one slice.
     *
     * One buy's worth at a time, so a position built out of several clips is
     * sold the way it was bought. Anything that would leave a remainder too
     * small for the venue goes out whole instead — a dust remainder is a
     * position that cannot be sold at all.
     */
    fun sliceSize(uncovered: Double, lot: Double?, minimum: Double): Double {
        val clip = if (lot != null && lot > 0.0) maxOf(lot, minimum) else uncovered
        if (clip >= uncovered) return uncovered
        return if (uncovered - clip < minimum) uncovered else clip
    }

    private fun snapUp(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = ceil(price / tick - 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(tick, 1.0 - tick)
    }

    private fun snapDown(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = kotlin.math.floor(price / tick + 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(tick, 1.0 - tick)
    }
}
