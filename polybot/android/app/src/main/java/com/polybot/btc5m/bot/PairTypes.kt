package com.polybot.btc5m.bot

/**
 * Settings for the pair bot.
 *
 * Defaults follow the worked example: seed the side under 50¢ in 5-share lots
 * every 10–20 seconds, complete the pair only while the total stays under 95¢,
 * and recycle a leg that has run into the other side.
 */
data class PairSettings(
    val dryRun: Boolean = true,
    /** Shares per lot. The venue's own floor is 5. */
    val lotShares: Double = 5.0,
    /**
     * Extra size on the cheaper side, as a fraction of the lot.
     *
     * It also sets how far that side may lead the other. Being long the cheap
     * leg risks a few cents a share; being long the dear one risks most of a
     * dollar, so the lopsidedness is pointed deliberately at the cheap side.
     */
    val cheapSideBonusPct: Double = 0.30,
    val minIntervalSec: Int = 10,
    val maxIntervalSec: Int = 20,
    /** Only seed a side quoted below this — "the cheaper side". */
    val maxSeedPrice: Double = 0.50,
    /** Ceiling on avg(Up) + avg(Down). A pair returns $1, so this is the margin. */
    val maxPairAvg: Double = 0.95,
    /** Floor on the return of a completed pair, as a fraction of its cost. */
    val minPairProfitPct: Double = 0.03,
    /** Sell part of a leg once the bid clears its average by this much. */
    val rotateProfitPct: Double = 0.10,
    /** A leg averaging under `cheapLegUnder` rotates on this smaller gain. */
    val cheapLegUnder: Double = 0.50,
    val cheapRotateProfitPct: Double = 0.05,
    /** Fraction of a leg to sell when it rotates. */
    val rotateFraction: Double = 0.5,
    /**
     * Cross the spread instead of resting. Fills sooner and pays the taker fee
     * on every leg, which is most of the edge on a 95¢ pair.
     */
    val takerEntry: Boolean = false,
    /** Ceiling on money at risk in the window, cost basis of both legs. */
    val maxExposureUsd: Double = 20.0,
    /** Ceiling on |Up shares − Down shares|; this is the only directional risk. */
    val maxImbalanceShares: Double = 20.0,
    /** Stop seeding and try to square the book this long before the close. */
    val flattenSec: Int = 40,
    /** Starting balance for the paper run, seeded once and then left alone. */
    val paperStartUsd: Double = 100.0,
)

/** One side of the book: how many shares, and what they cost in total. */
data class PairLeg(
    var shares: Double = 0.0,
    var costUsd: Double = 0.0,
) {
    val avg: Double get() = if (shares > 1e-9) costUsd / shares else 0.0

    fun buy(shares: Double, costUsd: Double) {
        this.shares += shares
        this.costUsd += costUsd
    }

    /**
     * Average-cost accounting: selling part of a leg removes its share of the
     * basis and leaves the average where it was.
     */
    fun sell(shares: Double) {
        if (this.shares <= 1e-9) return
        val fraction = (shares / this.shares).coerceIn(0.0, 1.0)
        this.costUsd -= this.costUsd * fraction
        this.shares -= this.shares * fraction
        if (this.shares < 1e-9) {
            this.shares = 0.0
            this.costUsd = 0.0
        }
    }
}

/** A live order the pair bot owns. */
data class PairOrder(
    val localId: Long,
    var orderId: String?,
    val side: String,
    val action: String,
    val price: Double,
    val size: Double,
    val dryRun: Boolean,
    val placedAt: Long,
    val note: String,
    /**
     * Set on a rotation sell. When it fills, the proceeds go straight into the
     * other side — selling the leg that ran and buying the one that fell is a
     * single move, not two independent ones.
     */
    val rotation: Boolean = false,
    var matched: Double = 0.0,
    var cancelled: Boolean = false,
) {
    val remaining: Double get() = (size - matched).coerceAtLeast(0.0)
    val live: Boolean get() = !cancelled && remaining > 1e-9
}

/** One executed trade, kept for the on-screen ledger. */
data class PairFill(
    val at: Long,
    val side: String,
    val action: String,
    val shares: Double,
    val price: Double,
    val feeUsd: Double,
    val dryRun: Boolean,
    val note: String,
)

data class PairStats(
    var windows: Int = 0,
    var buys: Int = 0,
    var sells: Int = 0,
    var pairsLocked: Double = 0.0,
    var feesUsd: Double = 0.0,
    var realisedPnlUsd: Double = 0.0,
)

/** The pair book for one 5-minute window. */
data class PairBook(
    val windowStart: Long,
    val windowEnd: Long,
    /** Captured at creation: a book settles into the books it was opened in. */
    val dryRun: Boolean = true,
    var market: Market? = null,
    val up: PairLeg = PairLeg(),
    val down: PairLeg = PairLeg(),
    /** Everything paid out this window, fees included. */
    var spentUsd: Double = 0.0,
    /** Everything taken in this window, fees already deducted. */
    var proceedsUsd: Double = 0.0,
    var feesUsd: Double = 0.0,
    var nextSeedAtMs: Long = 0,
    var winner: String? = null,
    var pnlUsd: Double? = null,
    var settled: Boolean = false,
) {
    val pairs: Double get() = kotlin.math.min(up.shares, down.shares)
    val imbalance: Double get() = up.shares - down.shares
    val exposureUsd: Double get() = up.costUsd + down.costUsd
    val pairAvg: Double get() = up.avg + down.avg
}
