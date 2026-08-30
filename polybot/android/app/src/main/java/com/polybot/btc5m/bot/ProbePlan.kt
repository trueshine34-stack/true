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
        if (cashUsd < settings.stakeUsd) return "контейнер пуст"
        return null
    }

    /** Five dollars' worth at that price, never under the venue's floor. */
    fun shares(stakeUsd: Double, ask: Double, minimumOrderSize: Double): Double {
        if (ask <= 0.0) return 0.0
        val floor = Orders.minShares(ask, minimumOrderSize)
        val wanted = stakeUsd / ask
        return maxOf(floor, Math.round(wanted * 10.0) / 10.0)
    }

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }
}
