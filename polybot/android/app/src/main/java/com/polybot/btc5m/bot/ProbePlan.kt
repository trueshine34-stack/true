package com.polybot.btc5m.bot

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

    data class Settings(
        val enabled: Boolean = false,
        val stakeUsd: Double = DEFAULT_STAKE,
        val leadSec: Long = DEFAULT_LEAD_SEC,
    )

    /** Whether now is the moment: inside the lead, before the window turns. */
    fun due(secondsLeft: Long, settings: Settings): Boolean =
        secondsLeft in 1..settings.leadSec

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
    ): String? {
        if (!settings.enabled) return "выключен"
        if (way.isEmpty()) return "тренд вбок"
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
