package com.polybot.btc5m.bot

/**
 * What a position asks on the way out, and what it cost on the way in.
 *
 * These three sums belonged to the trend rule's book while that rule existed;
 * they were never about the trend. What a taken offer costs is a fact about
 * the venue's fee, and where the ladder is standing right now is a fact about
 * the desk's own sell settings — both are asked by every rule that holds
 * shares, so they outlive the one that happened to define them first.
 */
object Exits {

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
     * Either the ladder's rung for this point in the window, or — where the
     * desk is set to price off cost rather than off the clock — the margin
     * that rule wants, never below what the shares cost.
     */
    fun price(
        cost: Double,
        elapsedSec: Long,
        secondsLeft: Long,
        highWater: Double,
        /** The worst it has been bid, which is what arms the rescue. */
        lowWater: Double = 0.0,
        rung: Int,
        bestBid: Double?,
        exit: AutoSell.Settings,
        tick: Double = 0.01,
    ): Double {
        if (exit.percentMode) {
            return SellLadder.capped(
                SellPercent.priceFor(
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
                ),
                cost,
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
        // A side the book once wrote off does not climb with the clock: it
        // asks the first rung all window and ninety-three at the end, because
        // the price its recovery reaches is not the price a steady winner
        // would have walked up to by now.
        if (exit.dipRescue && SellLadder.dipped(lowWater)) {
            return SellLadder.afterDip(rungs, secondsLeft)
        }
        return rungs[step.coerceIn(0, rungs.size - 1)]
    }

    /** Which rung the clock and the high-water mark have reached. */
    fun step(
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
}
