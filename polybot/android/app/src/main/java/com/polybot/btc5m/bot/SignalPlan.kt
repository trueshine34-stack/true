package com.polybot.btc5m.bot

import kotlin.math.ceil

/**
 * Trading the five-minute window off TradingView's own technical read.
 *
 * The rule is the one a person would follow watching that page: when all three
 * gauges — the summary, the moving averages and the oscillators — say buy or
 * strong buy, the next five minutes are being called up, and the Up side is
 * worth a dollar. When all three say sell, the same in reverse. Anything less
 * than unanimous is no signal at all, and the bot sits out; that is most
 * windows, and it is meant to be.
 *
 * Entries start ten seconds in, once the window's first prices have settled
 * down, and each of the three clips waits for the side to get cheaper. Nothing
 * is bought above sixty cents: past that the market has already agreed with the
 * indicators and there is no edge left to pay for.
 */
object SignalPlan {

    const val DEFAULT_BANK_USD = 6.0
    const val DEFAULT_CLIP_USD = 1.0
    const val DEFAULT_MAX_BUYS = 3
    const val DEFAULT_MAX_PRICE = 0.60
    const val DEFAULT_FROM_SEC = 10L

    /**
     * Entries stop with a minute to go: a clip taken there has no time to climb
     * the sell ladder, and the ladder is the only exit this bot has.
     */
    const val DEFAULT_UNTIL_SEC = 240L

    /** TradingView's own cut-offs on its −1..1 scale. */
    private const val BUY = 0.1
    private const val STRONG = 0.5

    data class Settings(
        val enabled: Boolean = false,
        val bankUsd: Double = DEFAULT_BANK_USD,
        val clipUsd: Double = DEFAULT_CLIP_USD,
        val maxBuys: Int = DEFAULT_MAX_BUYS,
        /** Never pays more than this for a share. */
        val maxPrice: Double = DEFAULT_MAX_PRICE,
        val fromSec: Long = DEFAULT_FROM_SEC,
        val untilSec: Long = DEFAULT_UNTIL_SEC,
    )

    /** One gauge, in the words TradingView prints next to it. */
    fun verdict(value: Double): String = when {
        value.isNaN() -> "—"
        value >= STRONG -> "Strong Buy"
        value >= BUY -> "Buy"
        value > -BUY -> "Neutral"
        value > -STRONG -> "Sell"
        else -> "Strong Sell"
    }

    /**
     * The side all three gauges agree on, or null when they do not.
     *
     * Unanimity is the whole filter. Two buys and a neutral is a market with no
     * opinion, and paying a spread to act on one is how a small bank is spent.
     */
    fun direction(gauges: TradingView.Gauges?): String? {
        if (gauges == null) return null
        val all = listOf(gauges.summary, gauges.movingAverages, gauges.oscillators)
        if (all.any { it.isNaN() }) return null
        return when {
            all.all { it >= BUY } -> "Up"
            all.all { it <= -BUY } -> "Down"
            else -> null
        }
    }

    /** Why it is not buying right now — or null, meaning it is. */
    fun blockedBecause(
        side: String?,
        ask: Double?,
        elapsedSec: Long,
        buys: Int,
        lastEntry: Double?,
        tick: Double,
        cashUsd: Double,
        settings: Settings,
    ): String? = when {
        !settings.enabled -> "выключен"
        side == null -> "индикаторы не согласны"
        elapsedSec < settings.fromSec -> "ждёт ${settings.fromSec} с"
        elapsedSec >= settings.untilSec -> "поздно входить"
        buys >= settings.maxBuys -> "взял свои ${settings.maxBuys}"
        cashUsd < settings.clipUsd -> "нет денег в контейнере"
        ask == null || ask <= 0.0 -> "нет цены"
        ask > settings.maxPrice + 1e-9 -> "дороже ${(settings.maxPrice * 100).toInt()}¢"
        // Each further clip waits for the side to get cheaper, which is what
        // "докупает когда цена опускается" means and what keeps the three
        // entries from being one entry in three pieces.
        lastEntry != null && ask > lastEntry - tick + 1e-9 -> "ждёт тик ниже"
        else -> null
    }

    /** A dollar's worth, raised to whatever the venue will actually accept. */
    fun clipShares(ask: Double, minimumOrderSize: Double, settings: Settings): Double {
        if (ask <= 0.0) return 0.0
        val wanted = settings.clipUsd / ask
        val floor = Orders.minShares(ask, minimumOrderSize)
        return ceil(maxOf(wanted, floor) * 100.0) / 100.0
    }

    /** Crossing the offer, because a signal acted on late is not a signal. */
    fun crossPrice(ask: Double, tick: Double): Double =
        minOf(ask + tick * 2, 1.0 - tick)
}
