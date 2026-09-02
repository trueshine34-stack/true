package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * A hint about which way the window is going. Nothing acts on it.
 *
 * This is what the pulse rule used to trade on, kept for the one part of it
 * that was worth keeping: the reading. Four independent sources are asked
 * whether the same side is winning, and the answer goes on the screen for a
 * person to weigh — the rule that turned the same four numbers into an order
 * is gone, along with its money, its record and its opinions about exits.
 *
 *  - **The lead.** How far price has moved from the window's own open, on the
 *    series the market settles against. Under a few dollars the window is a
 *    coin flip wearing a direction.
 *  - **Momentum.** Where the last few one-minute closes have gone. A lead
 *    being handed back is not a lead.
 *  - **Volume.** Whether the last completed minute traded more than the recent
 *    average. A move nobody is behind is the kind that comes back.
 *  - **The book.** Which way resting size leans within a hair of the mid,
 *    which is what the next few dollars have to get through.
 */
object Signal {

    /** The lead a window needs before it is saying anything, in dollars. */
    const val MIN_EDGE = 6.0

    /** Share of the book's size, within the span, the leading side needs. */
    const val MIN_LEAN = 0.55

    /** Last completed minute's volume against the ten before it. */
    const val MIN_VOLUME = 0.7

    /**
     * Before this the window has not said anything yet.
     *
     * A minute and a half: under it the momentum is one candle, the volume is
     * that candle against an average, and the book has not been tested by
     * anything. Four readings agreeing there is four ways of describing the
     * same noise.
     */
    const val FROM_SEC = 90L

    /**
     * What one dollar of the edge means: bitcoin at a hundred thousand.
     *
     * Six dollars of lead is a real move on bitcoin and an impossible one on a
     * hundred-dollar coin, so the figure is read against the price of the coin
     * in front of it — the same *move*, whichever is being traded.
     */
    const val EDGE_REFERENCE = 100_000.0

    fun edgeFor(price: Double): Double =
        if (price > 0.0) MIN_EDGE * (price / EDGE_REFERENCE) else MIN_EDGE

    /** The four readings, as they stand right now. */
    data class Read(
        val elapsedSec: Long,
        /** Price now less the window's open, in that coin's dollars. */
        val lead: Double,
        /** The last few one-minute closes, in dollars. */
        val momentum: Double,
        /** Last completed minute's volume over the average of the ten before. */
        val volume: Double,
        /** Share of the book's size sitting on the bid, 0..1. */
        val lean: Double,
        /** What the coin costs, which is what a dollar of lead is worth. */
        val price: Double,
    )

    /**
     * The hint: a side, how many of the four agree with it, and why not.
     *
     * A side is named as soon as the lead names one — that is the question the
     * market settles — and the count is how much of the rest of the desk is
     * behind it. Four out of four is the reading the old rule traded; three is
     * a lead with something arguing against it, and the [against] line says
     * which one.
     */
    data class Hint(
        val side: String?,
        val agree: Int,
        val against: String?,
    )

    /** Which side the window is currently winning, if either. */
    fun leader(lead: Double, edge: Double): String? = when {
        lead >= edge -> "Up"
        lead <= -edge -> "Down"
        else -> null
    }

    fun of(read: Read): Hint {
        val edge = edgeFor(read.price)
        val side = leader(read.lead, edge)
            ?: return Hint(null, 0, "ход " + money(abs(read.lead)) + " из " + money(edge))

        val up = side == "Up"
        val withMomentum = if (up) read.momentum >= 0.0 else read.momentum <= 0.0
        val withVolume = read.volume >= MIN_VOLUME
        val lean = if (up) read.lean else 1.0 - read.lean
        val withBook = lean >= MIN_LEAN

        val agree = 1 + listOf(withMomentum, withVolume, withBook).count { it }
        val against = when {
            read.elapsedSec < FROM_SEC -> "рано, " + (FROM_SEC - read.elapsedSec) + " с"
            !withMomentum -> "импульс против"
            !withVolume -> "нет объёма ×" + String.format("%.2f", read.volume)
            !withBook -> "стакан против " + (lean * 100).toInt() + "%"
            else -> null
        }
        // Early in the window the four readings are noise agreeing with
        // itself, so the count is honest about it: the side stands, the
        // confluence does not.
        return Hint(side, if (read.elapsedSec < FROM_SEC) 1 else agree, against)
    }

    private fun money(value: Double): String =
        "$" + if (value >= 10.0) {
            String.format("%.0f", value)
        } else if (value >= 0.1) {
            String.format("%.2f", value)
        } else {
            String.format("%.3f", value)
        }
}
