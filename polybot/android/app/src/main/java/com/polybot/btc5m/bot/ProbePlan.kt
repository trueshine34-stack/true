package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * The experiment: buy the way the chart's line points, just before the window
 * opens, and leave by the ladder.
 *
 * It is deliberately the simplest thing that can be measured. One entry per
 * window, always the same money, always the direction the five-minute chart is
 * already drawing — so what the record afterwards says is whether following
 * that line pays, and not whether some cleverness on top of it did.
 *
 * Ten seconds before the open, because that is late enough for the line to
 * include everything the last window did and early enough that the price has
 * not yet moved on the new one.
 */
object ProbePlan {

    /** What each window is worth risking, in dollars. */
    const val DEFAULT_STAKE = 5.0

    /** What the paper account starts with. */
    const val DEFAULT_BANK = 100.0

    /** How long before the window opens the entry goes in. */
    const val DEFAULT_LEAD_SEC = 10L

    /** Nothing is bought this dear, whatever the line says. */
    const val MAX_PRICE = 0.80

    /**
     * How much room to the level ahead a window needs, as a share of what a
     * window usually travels.
     *
     * A trend that is about to arrive at a price the market has already turned
     * at twice is a trend with one candle left in it. The bet is five minutes
     * long, so the question is not "is there a level somewhere above" but "can
     * this window reach it" — and that is what a typical window's travel
     * measures. At six tenths, the line has to have more room in front of it
     * than a normal five minutes covers before its direction is worth paying
     * for.
     *
     * Zero switches the check off.
     *
     * Kept low on purpose. The rule is meant to trade nearly every window, so
     * this is for the few where price is genuinely up against something — and
     * "something" means a price the market has turned at more than once, not
     * every wiggle the minute chart has left behind.
     */
    const val DEFAULT_ROOM = 0.35

    data class Settings(
        val enabled: Boolean = false,
        val stakeUsd: Double = DEFAULT_STAKE,
        val leadSec: Long = DEFAULT_LEAD_SEC,
        /** Room to the level ahead, against a typical window's travel. */
        val roomShare: Double = DEFAULT_ROOM,
        /**
         * Paper money. On by default, because the point of this rule is to
         * find out whether the line pays before any real money is asked to
         * find out.
         *
         * Nothing is sent to the venue: it reads the same live book, takes
         * the same offers at the same prices, pays the same fee, and leaves
         * by the same ladder — only the money is imaginary.
         */
        val demo: Boolean = true,
        /** What that imaginary money starts at. */
        val bankUsd: Double = DEFAULT_BANK,
    )

    /**
     * Whether the reversal is close enough to be this window's problem.
     *
     * Distances are meaningless bare: forty dollars from resistance is nothing
     * in a market moving two hundred an hour and everything in one moving
     * thirty. So the room in front of the trend is measured in windows, not in
     * dollars.
     */
    fun tooClose(
        price: Double,
        level: Double?,
        typical: Double,
        share: Double = DEFAULT_ROOM,
    ): Boolean {
        if (level == null || share <= 0.0) return false
        if (price <= 0.0 || typical <= 0.0) return false
        return abs(level - price) < typical * share
    }

    /**
     * The window this moment is trying to buy, or null when it is neither.
     *
     * Two moments, not one. The lead before a window opens is where the entry
     * belongs — late enough to have seen the last window, early enough to be
     * in from the first tick. But the venue does not always publish the next
     * market in time, and a chance missed for that reason used to cost the
     * whole window; so the same lead again just after the open buys the window
     * that is already running. Two seconds late is still that window's bet.
     */
    fun targetWindow(
        windowStart: Long,
        elapsedSec: Long,
        settings: Settings,
        windowSec: Long = 300L,
    ): Long? {
        val left = windowSec - elapsedSec
        return when {
            left in 1..settings.leadSec -> windowStart + windowSec
            elapsedSec in 0..settings.leadSec -> windowStart
            else -> null
        }
    }

    /**
     * Why the entry is not going in, or null when it is.
     *
     * The order is the order a person would check them in, so what shows up on
     * the card is the first thing actually wrong.
     */
    fun blockedBecause(
        way: String,
        ask: Double?,
        cashUsd: Double,
        settings: Settings,
        /** Where BTC is, and the level the line is heading into. */
        price: Double = 0.0,
        level: Double? = null,
        typical: Double = 0.0,
    ): String? {
        if (!settings.enabled) return "выключен"
        // The line is read off the minute candles, so an empty answer means
        // the stream has not arrived rather than that the market is quiet.
        if (way.isEmpty()) return "нет свечей"
        if (tooClose(price, level, typical, settings.roomShare)) {
            return "у разворота " + Math.round(level ?: 0.0)
        }
        if (ask == null || ask <= 0.0) return "нет цены"
        if (ask > MAX_PRICE + 1e-9) return "дорого " + "${Math.round(ask * 100)}¢"
        if (cashUsd < settings.stakeUsd) {
            return if (settings.demo) "тестовый счёт пуст" else "на счету пусто"
        }
        return null
    }

    /** Five dollars' worth at that price, never under the venue's floor. */
    fun shares(stakeUsd: Double, ask: Double, minimumOrderSize: Double): Double {
        if (ask <= 0.0) return 0.0
        val floor = Orders.minShares(ask, minimumOrderSize)
        val wanted = stakeUsd / ask
        return maxOf(floor, Math.round(wanted * 10.0) / 10.0)
    }

    /** The venue's taker fee, which paper money pays too. */
    const val FEE_RATE = 0.07

    /**
     * What one share actually costs when an offer is taken.
     *
     * The quote is what the seller asks; the fee is what the venue takes on
     * top, and on a buy it comes out in shares. Paper money pays it because a
     * demo that ignored the fee would report a profit the same trade would not
     * have made — which is the one thing a demo must not do.
     */
    fun takenPrice(ask: Double): Double {
        if (ask <= 0.0 || ask >= 1.0) return ask
        return ask + FEE_RATE * ask * (1 - ask)
    }

    /**
     * What the desk's sell rule is asking for a position right now.
     *
     * The demo places no orders, so nothing on the desk can see its position
     * and arrange an exit for it — but "exits by the ladder" has to mean the
     * ladder that is actually running, or the report is about a rule nobody
     * uses. So the choice is made here, out of the same two pieces the real
     * rule is made of: the rung for the clock and the high-water mark, or the
     * margin over what the lot cost, with the late floors that stop a winning
     * side riding into the close for nothing.
     *
     * Which of the two is the desk's own setting, not this rule's.
     */
    fun exitPrice(
        cost: Double,
        elapsedSec: Long,
        secondsLeft: Long,
        highWater: Double,
        rung: Int,
        bestBid: Double?,
        exit: AutoSell.Settings,
        tick: Double = 0.01,
    ): Double {
        if (exit.percentMode) {
            return SellPercent.priceFor(
                avgPrice = cost,
                gain = exit.profitPct,
                tick = tick,
                // One lot per window, so there is never a slice to step over.
                resting = null,
                secondsLeft = secondsLeft,
                panicSec = exit.panicSec,
                bestBid = bestBid,
                closeFloor = exit.closeFloor,
                lateFloor = exit.lateFloor,
                lateBandSec = exit.lateBandSec,
            )
        }
        val rungs = exit.ladder.ifEmpty { SellLadder.DEFAULT }
        val step = SellLadder.stepFor(
            elapsedSec = elapsedSec.coerceAtLeast(0L),
            highWater = highWater.takeIf { it > 0.0 },
            ladder = rungs,
            floor = rung,
            leadSec = exit.ladderLeadSec,
            stepSec = exit.ladderStepSec,
        )
        return rungs[step.coerceIn(0, rungs.size - 1)]
    }

    /**
     * The rung this position has reached, which it may not slip back down.
     */
    fun exitStep(
        elapsedSec: Long,
        highWater: Double,
        rung: Int,
        exit: AutoSell.Settings,
    ): Int = SellLadder.stepFor(
        elapsedSec = elapsedSec.coerceAtLeast(0L),
        highWater = highWater.takeIf { it > 0.0 },
        ladder = exit.ladder.ifEmpty { SellLadder.DEFAULT },
        floor = rung,
        leadSec = exit.ladderLeadSec,
        stepSec = exit.ladderStepSec,
    )

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }
}
