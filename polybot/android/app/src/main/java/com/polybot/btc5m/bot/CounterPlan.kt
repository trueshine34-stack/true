package com.polybot.btc5m.bot

import kotlin.math.ceil

/**
 * When to fade the hand-trader's own side, and for how much.
 *
 * The idea is narrow on purpose. Early in a window the losing side is cheap
 * and the market has not decided anything yet: a side under thirty cents in
 * the first two minutes is priced as though the next three minutes cannot
 * happen. So the bot takes a dollar of whichever side the desk is *not* on,
 * and only when the price keeps sliding — one clip per tick down, never two at
 * the same price. Each clip is offered back at a quarter up the moment it can
 * be, and nothing is held into the decision.
 *
 * All of it is arithmetic on numbers the caller has already fetched, so the
 * rules can be argued with in a test rather than in a live window.
 */
object CounterPlan {

    /** What the bot is given to trade with, and what it does with it. */
    const val DEFAULT_BANK_USD = 5.0
    const val DEFAULT_CLIP_USD = 1.0
    const val DEFAULT_MAX_BUYS = 3
    const val DEFAULT_ENTRY_UNDER = 0.30
    const val DEFAULT_ENTRY_WINDOW_SEC = 120L
    const val DEFAULT_GAIN = 0.25

    data class Settings(
        val enabled: Boolean = false,
        /** The bot's own money, walled off from the desk's. */
        val bankUsd: Double = DEFAULT_BANK_USD,
        /** What one entry is worth, before the venue's own floor raises it. */
        val clipUsd: Double = DEFAULT_CLIP_USD,
        val maxBuys: Int = DEFAULT_MAX_BUYS,
        /** Only ever buys under this price. */
        val entryUnder: Double = DEFAULT_ENTRY_UNDER,
        /** How far into the window entries are allowed. */
        val entryWindowSec: Long = DEFAULT_ENTRY_WINDOW_SEC,
        /** The margin each clip is offered back at, net of the fee. */
        val gainPct: Double = DEFAULT_GAIN,
    )

    /** Why the bot is not buying right now — or null, meaning it is. */
    fun blockedBecause(
        ask: Double?,
        elapsedSec: Long,
        buys: Int,
        /** The price of the last clip taken this window, if any. */
        lastEntry: Double?,
        tick: Double,
        cashUsd: Double,
        settings: Settings,
    ): String? = when {
        !settings.enabled -> "выключен"
        ask == null || ask <= 0.0 -> "нет цены"
        elapsedSec < 0L -> "окно не началось"
        elapsedSec >= settings.entryWindowSec -> "первые 2 минуты прошли"
        buys >= settings.maxBuys -> "взял свои ${settings.maxBuys}"
        cashUsd < settings.clipUsd -> "нет денег в контейнере"
        ask >= settings.entryUnder -> "дороже ${(settings.entryUnder * 100).toInt()}¢"
        // Every clip after the first waits for another tick down. Without this
        // the same price is bought three times in six seconds, which is one
        // position in three pieces rather than an average that improves.
        lastEntry != null && ask > lastEntry - tick + 1e-9 -> "ждёт тик ниже"
        else -> null
    }

    /**
     * How many shares a clip is.
     *
     * A dollar at twenty-nine cents is three and a half shares, and the venue
     * will not take an order under five — so the floor decides, and the clip
     * costs what it costs. Rounded up rather than down for the same reason:
     * rounding a floor down puts the order back under it.
     */
    fun clipShares(ask: Double, minimumOrderSize: Double, settings: Settings): Double {
        if (ask <= 0.0) return 0.0
        val wanted = settings.clipUsd / ask
        val floor = Orders.minShares(ask, minimumOrderSize)
        return ceil(maxOf(wanted, floor) * 100.0) / 100.0
    }

    /** What that clip will actually cost. */
    fun clipCost(ask: Double, minimumOrderSize: Double, settings: Settings): Double =
        clipShares(ask, minimumOrderSize, settings) * ask

    /**
     * Two ticks through the offer, because this is meant to be taken now.
     *
     * The dip it is buying is moving; a limit resting exactly at the ask it saw
     * is a limit that misses.
     */
    fun crossPrice(ask: Double, tick: Double): Double =
        minOf(ask + tick * 2, 1.0 - tick)

    /** Where the clip is offered back: the buy plus the margin, after the fee. */
    fun exitPrice(cost: Double, tick: Double, settings: Settings): Double =
        SellPercent.targetPrice(cost, settings.gainPct, tick)

    /** The side the desk is not on, given whichever one it is. */
    fun opposite(side: String): String = if (side == "Up") "Down" else "Up"
}
