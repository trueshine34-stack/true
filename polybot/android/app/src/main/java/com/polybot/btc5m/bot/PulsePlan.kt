package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * The rule the pulse bot trades by.
 *
 * A five-minute window settles on one question: is the price above where the
 * window opened. Everything else on the desk is a way of guessing whether the
 * answer it has right now will still be the answer at the end — so this asks
 * four independent sources whether the same side is winning, and only trades
 * when all four agree and the market has not already charged for the agreement.
 *
 *  - **The lead.** How far price has moved from the window's own open, in
 *    dollars, on the series the market settles against. Under a few dollars
 *    the window is a coin flip wearing a direction.
 *  - **Momentum.** Where the last few one-minute closes have gone. A lead that
 *    is being handed back is not a lead.
 *  - **Volume.** Whether the last completed minute traded more than the recent
 *    average. A move on thin volume is a move nobody is behind, and those are
 *    the ones that come back.
 *  - **The book.** Which way the resting size leans within a hair of the mid,
 *    which is what the next few dollars have to get through.
 *
 * And one thing that is not a signal but a price: the odds have to be in a
 * band. Above the ceiling the arithmetic stops working — most of a dollar
 * risked for a few cents — and far below it the market is saying the opposite
 * of the four signals, which is a disagreement to sit out rather than to take
 * the cheap end of.
 *
 * Exits are their own arithmetic: a fixed profit taken at rest, a cut when the
 * lead flips the other way, and — inside the last stretch, with the side still
 * ahead — no sale at all, because settlement pays a dollar and charges no fee.
 */
object PulsePlan {

    const val DEFAULT_BANK_USD = 100.0

    /** Every window is five minutes long. */
    const val WINDOW_SEC = 300L

    /** One clip, always. This bot's whole idea is repetition, not size. */
    const val DEFAULT_SHARES = 5.0

    /** The window has to have said something before it is worth trading. */
    const val DEFAULT_FROM_SEC = 45L

    /**
     * How late a window may still be entered — the whole of it, by default.
     *
     * A late entry has no room left to reach its own take price, which was the
     * reason this used to stop a minute before the close. But that is not the
     * only way a lot pays: from [DEFAULT_RIDE_SEC] a side that is still ahead
     * is carried into settlement, which pays a whole dollar and charges no
     * fee, and a lot bought at four and a half minutes on a side that is
     * winning is a lot bought for exactly that. The refusal cost those windows
     * and bought nothing.
     */
    const val DEFAULT_UNTIL_SEC = 300L

    /** From here a winning side is carried into settlement rather than sold. */
    const val DEFAULT_RIDE_SEC = 265L

    /** Dollars of lead that count as a direction rather than noise. */
    const val DEFAULT_MIN_EDGE = 6.0

    /** Share of the book's size, within the span, the winning side needs. */
    const val DEFAULT_MIN_LEAN = 0.55

    /** Last completed minute's volume against the ten before it. */
    const val DEFAULT_MIN_VOLUME = 0.7

    /** The odds band worth trading in. */
    const val DEFAULT_MIN_PRICE = 0.30
    const val DEFAULT_MAX_PRICE = 0.80

    /** What one round is trying to make, on the price paid. */
    const val DEFAULT_TAKE_PCT = 0.15

    /**
     * And the least it may ever be, whatever the setting says.
     *
     * A rule that takes a side the moment four readings agree is paying for
     * that agreement in the ask, so the margin has to be worth the crossing:
     * under this the round is a coin toss with a fee on it. It is a floor
     * rather than a default because a setting stored before the floor existed
     * would otherwise keep selling under it, quietly, forever.
     */
    const val MIN_TAKE_PCT = 0.15

    /**
     * How far under the entry the standing bids sit, in cents.
     *
     * The rule buys a side the moment four readings agree, which is the
     * moment it is dearest — and a five-minute window that goes the other way
     * first and comes back is the ordinary shape of one, not an exception. So
     * three more bids wait below, each for the same size, and each one that
     * fills buys the same conviction cheaper.
     *
     * Six cents apart because that is about what a window's ordinary swing is
     * worth on a side priced near the middle: close enough that the first one
     * is reached often, far enough that three of them are not all taken by the
     * same wobble.
     */
    val ADD_STEPS = listOf(0.06, 0.12, 0.18)

    /**
     * The bids under an entry, dearest first, dropped where they fall off the
     * bottom of the book.
     */
    fun addPrices(entry: Double, tick: Double): List<Double> {
        if (entry <= 0.0) return emptyList()
        val step = if (tick > 0) tick else 0.01
        return ADD_STEPS
            .map { Math.round((entry - it) / step) * step }
            .map { Math.round(it * 10_000.0) / 10_000.0 }
            .filter { it >= step }
    }

    /** Dollars the lead has to flip against the position before it is cut. */
    const val DEFAULT_CUT_USD = 3.0

    data class Settings(
        val enabled: Boolean = false,
        val bankUsd: Double = DEFAULT_BANK_USD,
        val shares: Double = DEFAULT_SHARES,
        val fromSec: Long = DEFAULT_FROM_SEC,
        val untilSec: Long = DEFAULT_UNTIL_SEC,
        val rideSec: Long = DEFAULT_RIDE_SEC,
        val minEdge: Double = DEFAULT_MIN_EDGE,
        val minLean: Double = DEFAULT_MIN_LEAN,
        val minVolume: Double = DEFAULT_MIN_VOLUME,
        val minPrice: Double = DEFAULT_MIN_PRICE,
        val maxPrice: Double = DEFAULT_MAX_PRICE,
        val takePct: Double = DEFAULT_TAKE_PCT,
        val cutUsd: Double = DEFAULT_CUT_USD,
        /**
         * Whether the wallet trades this rule as well.
         *
         * Paper is not a mode any more, it is the floor: the rule always
         * reads the same live book, takes the same offers at the same prices
         * and pays the same fee on a hundred imaginary dollars, and that
         * record is the only thing that can answer whether it deserves real
         * money. This says whether it has been given some — a second account
         * beside the paper one, on the same reads and the same windows, with
         * its own lot and its own totals.
         */
        val live: Boolean = false,
    )

    /** Everything the rule looks at, read once per check. */
    data class Read(
        val elapsedSec: Long,
        /** Price now less the window's open, in dollars. */
        val lead: Double,
        /** The last few one-minute closes, in dollars: where momentum points. */
        val momentum: Double,
        /** Last completed minute's volume over the average of the ten before. */
        val volume: Double,
        /** Share of the book's size sitting on the bid, 0..1. */
        val lean: Double,
        val upAsk: Double?,
        val downAsk: Double?,
        /** The desk's own early ceiling, which this bot is bound by too. */
        val ceiling: Double,
        val cashUsd: Double,
    )

    /** Which side the window is currently winning, if either. */
    fun leader(lead: Double, minEdge: Double): String? = when {
        lead >= minEdge -> "Up"
        lead <= -minEdge -> "Down"
        else -> null
    }

    /** The price this side is offered at. */
    fun askFor(side: String?, read: Read): Double? =
        when (side) {
            "Up" -> read.upAsk
            "Down" -> read.downAsk
            else -> null
        }

    /**
     * Why the rule is not buying, or null when it is.
     *
     * The order is the order a person would check them in, so the note on the
     * screen is the first thing that is actually wrong rather than the last
     * thing tested.
     */
    fun blockedBecause(read: Read, settings: Settings, holding: Boolean): String? {
        if (!settings.enabled) return "выключен"
        if (holding) return "в позиции"
        if (read.elapsedSec < settings.fromSec) return "рано"
        // A limit at or past the window's own length is no limit at all.
        if (settings.untilSec in 1 until WINDOW_SEC && read.elapsedSec > settings.untilSec) {
            return "поздно"
        }

        val side = leader(read.lead, settings.minEdge)
            ?: return "нет перевеса " + money(abs(read.lead)) + " из " + money(settings.minEdge)

        val up = side == "Up"
        if (up && read.momentum < 0.0 || !up && read.momentum > 0.0) return "импульс против"
        if (read.volume < settings.minVolume) {
            return "нет объёма ×" + String.format("%.2f", read.volume)
        }

        val lean = if (up) read.lean else 1.0 - read.lean
        if (lean < settings.minLean) return "стакан против " + pct(lean)

        val ask = askFor(side, read) ?: return "нет цены"
        val top = minOf(settings.maxPrice, read.ceiling)
        if (ask > top + 1e-9) return "дорого " + cents(ask) + " из " + cents(top)
        if (ask < settings.minPrice - 1e-9) return "рынок против " + cents(ask)
        if (read.cashUsd < ask * settings.shares) return "нет денег"

        return null
    }

    enum class Exit {
        /** Leave the offer where it is. */
        HOLD,

        /** The lead has gone: take what the book is bidding, now. */
        CUT,

        /** Ahead and nearly done: settlement pays a dollar and charges nothing. */
        RIDE,
    }

    /**
     * What to do with an open lot.
     *
     * Taking profit is not in here: that offer is resting on the book from the
     * moment the lot is opened, and the book fills it or does not. This is only
     * for the two things that need a decision — the lead turning against the
     * position, and the end of the window arriving with it still ahead.
     */
    fun exitFor(side: String, read: Read, settings: Settings): Exit {
        val ahead = if (side == "Up") read.lead else -read.lead
        if (read.elapsedSec >= settings.rideSec && ahead >= settings.minEdge) return Exit.RIDE
        if (ahead <= -settings.cutUsd) return Exit.CUT
        return Exit.HOLD
    }

    /**
     * Where the profit is offered, snapped up to the venue's step.
     *
     * Never under [MIN_TAKE_PCT] over what the shares cost: that is the whole
     * of "sell dearer", and it holds against a lower setting rather than
     * trusting one.
     */
    fun takeOf(settings: Settings): Double =
        maxOf(MIN_TAKE_PCT, settings.takePct)

    fun takePrice(paid: Double, settings: Settings, tick: Double): Double {
        val wanted = paid * (1.0 + takeOf(settings))
        val step = if (tick > 0) tick else 0.01
        val snapped = kotlin.math.ceil(wanted / step - 1e-9) * step
        return (Math.round(snapped * 10_000.0) / 10_000.0).coerceIn(step, 1.0 - step)
    }

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }

    private fun money(usd: Double) = "$" + String.format("%.0f", usd)
    private fun cents(price: Double) = "${Math.round(price * 100)}¢"
    private fun pct(share: Double) = "${Math.round(share * 100)}%"
}
