package com.polybot.btc5m.bot

/**
 * Buying the favourite when it is still cheaper than the exit.
 *
 * The sell ladder already says what a position is worth at each minute of the
 * window. This turns that into an entry rule: fifteen seconds before a minute
 * ends — the moment the ladder steps to its next rung — look at the side the
 * market has picked, and if it can still be bought below the price the ladder
 * will ask for it, buy a clip. The exit is that same rung, so the trade is
 * arranged before it is made.
 *
 * It only ever buys the leading side. The cheap side is cheap because it is
 * losing, and a ladder that asks eighty-four cents for it is asking for a
 * reversal, not a rung.
 */
object LadderPlan {

    const val DEFAULT_BANK_USD = 5.0
    const val DEFAULT_SHARES = 5.0

    /** How often the check comes round, and when the first one is. */
    const val DEFAULT_EVERY_SEC = 60L

    /**
     * Fifteen seconds before the *second* minute ends.
     *
     * The first minute is skipped on purpose. A window that has just opened
     * has not picked a side yet — the dearer outcome at forty-five seconds is
     * as often the one that loses as the one that wins — and the first rung is
     * the cheapest the ladder ever asks for, so it is the worst exit to buy
     * into. From a hundred and five seconds the market has had time to mean
     * something.
     */
    const val DEFAULT_FIRST_AT_SEC = 105L

    /** Stop entering once the last minute begins; the ladder needs room to run. */
    const val DEFAULT_UNTIL_SEC = 285L

    /**
     * The shortest gap between two clips.
     *
     * A filled clip frees its money and its attention, and sitting out the
     * rest of the minute for a condition that is true now is sitting out for
     * nothing — so the rule may buy again as soon as it suits. This is only
     * long enough to keep one moment from being bought twice while the fill is
     * still being confirmed; the real limit on how much it can buy is the
     * money in its container.
     */
    const val DEFAULT_PAUSE_SEC = 4L

    private const val FEE_RATE = 0.07

    data class Settings(
        val enabled: Boolean = false,
        val bankUsd: Double = DEFAULT_BANK_USD,
        /** Shares per clip. */
        val shares: Double = DEFAULT_SHARES,
        val everySec: Long = DEFAULT_EVERY_SEC,
        val firstAtSec: Long = DEFAULT_FIRST_AT_SEC,
        val untilSec: Long = DEFAULT_UNTIL_SEC,
        /** The shortest gap between two clips, once one has gone through. */
        val pauseSec: Long = DEFAULT_PAUSE_SEC,
    )

    /**
     * Which check this moment belongs to, or −1 outside the entry band.
     *
     * Slots rather than an exact second: the loop ticks on its own schedule and
     * a rule that only fired on an exact equality would miss its moment
     * whenever a request ran long. The slot is what schedules the *first* look
     * of each minute; between them the rule may still buy the moment the
     * conditions suit, which is what [readyAfter] is for.
     */
    fun slotFor(elapsedSec: Long, settings: Settings): Int {
        if (elapsedSec < settings.firstAtSec) return -1
        if (elapsedSec > settings.untilSec) return -1
        val every = settings.everySec.coerceAtLeast(1L)
        return ((elapsedSec - settings.firstAtSec) / every).toInt()
    }

    /**
     * May this moment be acted on at all?
     *
     * True inside the entry band once the last clip's pause has run out. The
     * rule used to answer only on the minute, so a clip that filled at the
     * start of one waited out the rest of it while the same condition that
     * bought it stayed true.
     */
    fun readyAfter(elapsedSec: Long, sinceLastBuyMs: Long, settings: Settings): Boolean {
        if (elapsedSec < settings.firstAtSec || elapsedSec > settings.untilSec) return false
        return sinceLastBuyMs >= settings.pauseSec.coerceAtLeast(1L) * 1000L
    }

    /** The side the market has picked: the dearer of the two. */
    fun leadingSide(askUp: Double?, askDown: Double?): String? = when {
        askUp == null && askDown == null -> null
        askUp == null -> "Down"
        askDown == null -> "Up"
        askUp > askDown -> "Up"
        askDown > askUp -> "Down"
        // Dead level, so the market has picked nothing and neither does this.
        else -> null
    }

    /** What one share pays out after the taker fee. */
    fun netSell(price: Double): Double =
        if (price <= 0.0 || price >= 1.0) price else price - FEE_RATE * price * (1 - price)

    /** Why it is not buying at this check — or null, meaning it is. */
    fun blockedBecause(
        side: String?,
        ask: Double?,
        /** The rung the ladder is about to ask for. */
        rung: Double,
        elapsedSec: Long,
        cashUsd: Double,
        settings: Settings,
    ): String? = when {
        !settings.enabled -> "выключен"
        elapsedSec < settings.firstAtSec -> "ждёт ${settings.firstAtSec} с"
        elapsedSec > settings.untilSec -> "поздно входить"
        side == null -> "стороны вровень"
        ask == null || ask <= 0.0 -> "нет цены"
        ask >= rung -> "дороже ступени ${(rung * 100).toInt()}¢"
        cashUsd < ask * settings.shares - 1e-9 -> "нет денег в контейнере"
        // The rung is above the ask but not by enough to pay the fee, which
        // makes it a losing trade dressed as a winning one.
        netSell(rung) <= ask -> "не покрывает комиссию"
        else -> null
    }

    /** What a clip is worth at this price. */
    fun clipCost(ask: Double, settings: Settings): Double = ask * settings.shares

    /** Crossing the offer: the check is a moment, not a resting order. */
    fun crossPrice(ask: Double, tick: Double): Double =
        minOf(ask + tick * 2, 1.0 - tick)
}
