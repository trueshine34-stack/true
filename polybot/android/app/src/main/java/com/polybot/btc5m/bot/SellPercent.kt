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
 * Two things bend it. Several buys are sold in slices a few seconds apart, each
 * at a higher price than the last — if the first slice filled, the market is
 * still climbing, and dumping the rest at the same price would be selling the
 * move to the person who noticed it. And in the last minute of a window, a
 * position that never reached its margin is sold at whatever the book will pay,
 * so long as that still clears the cost after the fee: a small win beats
 * holding a five-minute market to settlement on hope.
 */
object SellPercent {

    const val DEFAULT_GAIN = 0.20

    /** Seconds between slices, so a rising price can carry the next one higher. */
    const val DEFAULT_SLICE_GAP_SEC = 2

    /** How near the close the rule stops holding out for its margin. */
    const val DEFAULT_PANIC_SEC = 60

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

    /** The cheapest sale that still comes out ahead once the fee is paid. */
    fun breakEven(avgPrice: Double, tick: Double): Double {
        val price = targetPrice(avgPrice, 0.0, tick)
        return if (netSell(price) > avgPrice) price else snapUp(price + tick, tick)
    }

    /**
     * Where the next slice should rest.
     *
     * @param resting the highest price already resting for this position, if any
     * @param secondsLeft until the window closes
     * @param bestBid what the book would pay right now
     * @return the price to ask, or null to leave what is resting alone
     */
    fun priceFor(
        avgPrice: Double,
        gain: Double,
        tick: Double,
        resting: Double?,
        secondsLeft: Long,
        panicSec: Int,
        bestBid: Double?,
    ): Double {
        val target = targetPrice(avgPrice, gain, tick)

        // Out of time: take what the book pays, as long as it is still a win
        // after the fee. Below that, keep asking — a loss is not an exit.
        if (secondsLeft in 0..panicSec.toLong() && bestBid != null) {
            val floor = breakEven(avgPrice, tick)
            if (bestBid >= floor) return snapDown(bestBid, tick)
        }

        // Each slice above the last: the fill proved the price was there.
        if (resting != null && resting >= target) return snapUp(resting + tick, tick)
        return target
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
