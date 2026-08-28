package com.polybot.btc5m.bot

/**
 * Catching a side on the way down, and letting it back up.
 *
 * Armed by hand on one side, this waits rather than buys: the price it was
 * armed at is a reference, not an entry, and nothing happens until the market
 * is six cents under it. From there every further clip is three cents cheaper
 * than the one before — and none of them is a resting order. A limit at the
 * target is filled the moment the price touches it; watching the ticks and
 * taking the offer buys the bottom of the move instead of its first touch.
 *
 * Exits are the mirror: the first is ten percent over what the first clip
 * cost, and every further lot sits at least two cents above the last, so a
 * side that runs sells the whole position in steps rather than all at one
 * price. When one fills, the whole cycle starts again from the price it sold
 * at — that sale is the new reference, and the next entry is six cents under
 * it.
 *
 * In the last half minute the exits stop being about profit. The window is
 * about to settle at a dollar or at nothing, so whatever is still held is
 * parked at 96, 97 and 98 cents: if the side wins, that is a fill just under
 * par instead of a wait for the redemption.
 */
object CatchPlan {

    /** How far under the reference the first clip goes in. */
    const val DROP = 0.06

    /** And how much cheaper each one after it. */
    const val STEP = 0.03

    /** What the first exit asks over what the first clip cost. */
    const val GAIN = 0.10

    /** The least an exit stands above the one before it. */
    const val SPREAD = 0.02

    /** Inside this, exits are parked near par instead of priced for profit. */
    const val LATE_SEC = 30L

    /** Where they are parked, in order, one per lot. */
    val LATE_PRICES = listOf(0.96, 0.97, 0.98)

    /** A clip is a quarter of what is free, and never fewer than five shares. */
    const val SHARE = 0.25
    const val MIN_SHARES = 5.0

    const val DEFAULT_BANK_USD = 10.0

    /** Nothing is bought this dear whatever the arithmetic says. */
    const val MAX_PRICE = 0.90

    data class Settings(
        val enabled: Boolean = false,
        val bankUsd: Double = DEFAULT_BANK_USD,
        val drop: Double = DROP,
        val step: Double = STEP,
        val gain: Double = GAIN,
        val spread: Double = SPREAD,
        val share: Double = SHARE,
        val minShares: Double = MIN_SHARES,
    )

    /**
     * The price the next clip is waiting for.
     *
     * Before anything is held that is the reference less the drop; after that
     * it is a step under the last fill, which is what makes the run cheaper
     * each time rather than cheaper than where it started.
     */
    fun buyTarget(reference: Double, lastFill: Double?, settings: Settings): Double {
        val from = lastFill ?: (reference - settings.drop + settings.step)
        return from - settings.step
    }

    /** Whether the offered price has reached that target. */
    fun ready(ask: Double?, target: Double): Boolean =
        ask != null && ask > 0.0 && ask <= target + 1e-9 && ask <= MAX_PRICE + 1e-9

    /**
     * Where lot number [index] is offered.
     *
     * The first is the gain on what the first clip cost; the rest step up from
     * it, so the position leaves in pieces as the side runs rather than all at
     * one price that either fills or does not.
     */
    fun sellPrice(firstCost: Double, index: Int, settings: Settings, tick: Double): Double {
        val base = firstCost * (1.0 + settings.gain)
        return snapUp(base + settings.spread * index, tick)
    }

    /** Where it is parked once the window is nearly over. */
    fun latePrice(index: Int, tick: Double): Double =
        snapUp(LATE_PRICES.getOrElse(index) { LATE_PRICES.last() }, tick)

    fun late(secondsLeft: Long): Boolean = secondsLeft in 0..LATE_SEC

    /** A quarter of what is free, in shares, floored at the venue's minimum. */
    fun clipShares(
        cashUsd: Double,
        price: Double,
        settings: Settings,
        minimumOrderSize: Double,
    ): Double {
        if (price <= 0.0) return 0.0
        val floor = maxOf(settings.minShares, Orders.minShares(price, minimumOrderSize))
        val wanted = cashUsd * settings.share / price
        return if (wanted >= floor) Math.round(wanted * 10.0) / 10.0 else floor
    }

    /** Whether that clip can be paid for at all. */
    fun affordable(cashUsd: Double, price: Double, shares: Double): Boolean =
        shares > 0.0 && price > 0.0 && cashUsd >= shares * price - 1e-9

    /** A sell must never round down onto a worse price than asked for. */
    fun snapUp(price: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        val snapped = kotlin.math.ceil(price / step - 1e-9) * step
        return (Math.round(snapped * 10_000.0) / 10_000.0).coerceIn(step, 1.0 - step)
    }

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }
}
