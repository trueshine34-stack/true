package com.polybot.btc5m.bot

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * What a side is actually worth, part way through its own window.
 *
 * The rule this app started with tries to guess, forty-five seconds before a
 * window opens, which way five minutes of bitcoin will go. Measured over a
 * month of tape that guess is right 49% of the time — it has no edge at all,
 * and no arrangement of chart rules found one.
 *
 * This asks a different question. Once a window is running, how it ends is
 * largely arithmetic: a side already well ahead with a minute left is not a
 * forecast, it is a near certainty, and the only question is what the book
 * charges for it. So the chance is computed, the ask is read, and the side is
 * bought only when the book is asking less than it is worth. Every such buy
 * is worth more than it costs whichever way that particular window goes,
 * which is the whole difference between an edge and a hunch.
 *
 * The shape is the one a random walk gives — the chance of ending above where
 * you started, from where you are now, with the time that is left. The walk's
 * own steepness is wrong, though: the tape is more persistent than a walk, and
 * a side already ahead wins rather more often than the walk allows. So the
 * steepness is fitted rather than assumed, by maximum likelihood over 31 756
 * observations across 28 days, and checked on a half of them it never saw.
 */
object FairValue {

    /**
     * How sharply the chance moves with the distance already travelled.
     *
     * Fitted, not chosen. On the half of the month held back from the fit the
     * model comes out slightly conservative — it promises 70% where 76%
     * happens, and 80% where 85% does — which is the right direction for a
     * number used to decide whether something is cheap.
     */
    const val STEEP = 2.05

    /** The window's own length, which is what the remaining time is against. */
    const val WINDOW_SEC = 300L

    /**
     * The chance this window closes above where it opened.
     *
     * [moved] is how far price has come from the window's opening price and
     * [typical] what a five-minute candle usually covers, so the distance is
     * in units of an ordinary move rather than in dollars — a hundred dollars
     * means something different every week.
     */
    fun chanceUp(
        moved: Double,
        typical: Double,
        leftSec: Long,
        windowSec: Long = WINDOW_SEC,
        steep: Double = STEEP,
    ): Double {
        if (typical <= 0.0 || windowSec <= 0L) return 0.5
        // Past the close there is nothing left to happen.
        if (leftSec <= 0L) return if (moved > 0.0) 1.0 else if (moved < 0.0) 0.0 else 0.5
        val left = minOf(leftSec, windowSec).toDouble() / windowSec
        val z = (moved / typical) / sqrt(left)
        val x = (steep * z).coerceIn(-40.0, 40.0)
        return 1.0 / (1.0 + exp(-x))
    }

    /** And the same for whichever side is being asked about. */
    fun chance(
        side: String,
        moved: Double,
        typical: Double,
        leftSec: Long,
        windowSec: Long = WINDOW_SEC,
        steep: Double = STEEP,
    ): Double {
        val up = chanceUp(moved, typical, leftSec, windowSec, steep)
        return if (side == "Down") 1.0 - up else up
    }
}
