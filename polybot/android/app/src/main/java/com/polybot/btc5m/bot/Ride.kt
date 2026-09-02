package com.polybot.btc5m.bot

/**
 * Selling by watching instead of by resting an offer.
 *
 * A rung on the book is taken the instant the price touches it, which is the
 * whole point of resting one — and also its cost: a side walking from eighty
 * to ninety-four sells at the rung and the rest of the walk belongs to whoever
 * took it. Riding is the other trade: nothing rests, the bid is watched, and
 * the position is sold only once the climb stops.
 *
 * "Stops" needs a length, because a bid does not move every tick — so it is a
 * pause: no new high for this long and the run is over. Two seconds is the
 * default and the slider is there because the right answer depends on how the
 * book behaves, not on anything this file knows.
 *
 * Two prices override the pause, and both are about the same thing: near a
 * dollar there is very little left to ride for.
 *
 *  - **Ninety-eight and up is taken at once.** Two cents of upside against a
 *    whole dollar of downside is not a run worth waiting on.
 *  - **Ninety-three is taken too, while there is still time to lose it.** With
 *    more than half a minute on the clock a lot can still happen; inside that,
 *    the window is nearly decided and holding out for the settlement dollar is
 *    the better side of the trade — so there the pause decides again.
 */
object Ride {

    /** Taken immediately, whatever else is true. */
    const val TAKE_NOW = 0.98

    /** And this, while there is more than [PATIENT_SEC] left of the window. */
    const val TAKE_HIGH = 0.93

    /** Inside this much of the close the high price may be ridden after all. */
    const val PATIENT_SEC = 35L

    /**
     * The last seconds, where nothing is sold at all.
     *
     * A sale this late is a sale into whatever is left of the book against a
     * settlement that is seconds away and pays a whole dollar with no fee. So
     * the offers come off and the window is allowed to finish: what is held is
     * held to the end.
     */
    const val CLOSE_SEC = 6L

    /** How long without a new high counts as the run being over. */
    const val DEFAULT_WAIT_MS = 2_000L

    /** The slider's ends. Under half a second every pause in the tape is a sale. */
    const val MIN_WAIT_MS = 500L
    const val MAX_WAIT_MS = 10_000L

    /**
     * A buy under this price is not ridden at all.
     *
     * Riding is a bet that a run has more in it. A side bought at a quarter is
     * not a run — it is a side the market has written off, and what happens
     * when it comes back is that it comes back once. The rung it reaches is
     * the price it reaches, and waiting two seconds for a better one is how
     * the whole recovery is given back.
     */
    const val CHEAP = 0.30

    /** And neither is one that has been worth half of what it cost. */
    const val HALVED = 0.5

    /**
     * Whether this position is one to take the rung on rather than ride.
     *
     * Cheap when it was bought, or cheap since: a position that has traded at
     * half its own cost has already shown that the book will not hold it up,
     * and the rung it climbs back to is a price that was there for a moment.
     */
    fun fragile(cost: Double, lowWater: Double): Boolean {
        if (!cost.isFinite() || cost <= 0.0) return false
        if (cost < CHEAP) return true
        return lowWater.isFinite() && lowWater > 0.0 && lowWater < cost * HALVED
    }

    enum class Act {
        /** The rung has not been reached; nothing to do but watch. */
        WAIT,

        /** Too late to sell: pull everything and let the window pay. */
        SETTLE,

        /** Reached, and still climbing — hold. */
        RIDE,

        /** Sell now, across the book. */
        TAKE,
    }

    /**
     * What to do with a position right now.
     *
     * @param bid what the book would pay for it this instant
     * @param rung the ladder's price for this moment
     * @param secondsLeft until the window closes
     * @param sinceHighMs how long since the bid last made a new high
     * @param waitMs the pause that counts as the run being over
     */
    fun act(
        bid: Double?,
        rung: Double,
        secondsLeft: Long,
        sinceHighMs: Long,
        waitMs: Long = DEFAULT_WAIT_MS,
        /** False for a position that takes its rung the moment it is reached. */
        patient: Boolean = true,
    ): Act {
        // The close outranks every price: seconds from settlement there is
        // nothing worth selling into.
        if (secondsLeft in 0..CLOSE_SEC) return Act.SETTLE
        if (bid == null || bid <= 0.0) return Act.WAIT
        if (bid >= TAKE_NOW - 1e-9) return Act.TAKE
        if (bid >= TAKE_HIGH - 1e-9 && secondsLeft > PATIENT_SEC) return Act.TAKE
        if (bid < rung - 1e-9) return Act.WAIT
        // Nothing to ride: the rung is the price, and it is here now.
        if (!patient) return Act.TAKE
        return if (sinceHighMs >= waitMs) Act.TAKE else Act.RIDE
    }

    /** The slider's value, kept inside what the rule can do anything with. */
    fun waitOf(ms: Long): Long = ms.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
}
