package com.polybot.btc5m.bot

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic behind the pair bot, kept free of I/O so it can be tested.
 *
 * Everything rests on one identity: on a binary market Up and Down settle to
 * $1 and $0 in some order, so **one Up plus one Down is worth exactly $1**, no
 * matter which way the price goes. A matched pair bought for less than $1 is
 * therefore locked profit — the only real question is how cheaply the second
 * leg can be picked up, and how much unmatched inventory is carried meanwhile.
 *
 * Fees decide the shape of the whole strategy. Polymarket charges the taker
 * only, so a resting limit that someone else crosses into pays nothing. That is
 * why 42¢ + 53¢ = 95¢ works as a target while the same two legs bought by
 * crossing the spread would cost about 98.5¢ and give back most of the edge.
 */
object PairMath {

    /**
     * Highest total a pair may cost and still clear the required margin.
     *
     * Two caps apply and the tighter wins: a floor on profit (a pair returns $1,
     * so paying C returns (1-C)/C) and a hard ceiling on the combined average.
     */
    fun maxPairCost(minProfitPct: Double, maxPairAvg: Double): Double =
        min(maxPairAvg, 1.0 / (1.0 + max(0.0, minProfitPct)))

    /**
     * Limit price for the leg that completes a pair.
     *
     * `heavyAvg` is the average already paid for the side we are long, fees
     * included. When the completing order will cross the spread its own taker
     * fee has to come out of the same budget; the fee depends on the price we
     * are solving for, so this settles it by iteration — it converges in two
     * rounds because the fee is at most 1.75¢ on a dollar.
     */
    fun completionLimit(
        heavyAvg: Double,
        budget: Double,
        taker: Boolean,
        feeRate: Double,
        feeExponent: Double,
    ): Double {
        val room = budget - heavyAvg
        if (room <= 0.0) return 0.0
        if (!taker) return room

        var price = room
        repeat(3) {
            val fee = Strategy.takerFeePerShare(price, feeRate, feeExponent)
            price = (room - fee).coerceAtLeast(0.0)
        }
        return price
    }

    /**
     * Where to bid on one side before any position exists.
     *
     * Up and Down are quoted at a combined dollar, so joining both best bids
     * buys a pair for roughly a dollar minus the two half-spreads — a couple of
     * cents, not five. Reaching 95¢ means bidding *below* both mids and waiting
     * to be hit, which is the "wait for the other side to come down" the whole
     * strategy is built on. Splitting the ceiling in the proportion the market
     * itself uses keeps both bids the same distance from fair.
     */
    fun allocatedBid(myMid: Double, theirMid: Double, budget: Double): Double? {
        val total = myMid + theirMid
        if (total <= 0.0 || myMid <= 0.0) return null
        return budget * (myMid / total)
    }

    /**
     * Would this buy push the combined average past its ceiling?
     *
     * A side with no shares yet has no average of its own, so the projection
     * uses the price being proposed. That is the honest reading: buying the
     * first Down at 53¢ against Up at 42¢ makes the pair average 95¢ whether or
     * not any Down was held before.
     */
    fun breachesPairCap(
        buyingLeg: PairLeg,
        otherLeg: PairLeg,
        price: Double,
        shares: Double,
        maxPairAvg: Double,
    ): Boolean {
        if (shares <= 0.0) return false
        val projectedShares = buyingLeg.shares + shares
        if (projectedShares <= 0.0) return false
        val projectedAvg = (buyingLeg.costUsd + price * shares) / projectedShares
        // An empty other side cannot be averaged; the pair is not formed yet, so
        // judge this leg against the room a pair would leave it.
        val otherAvg = if (otherLeg.shares > 1e-9) otherLeg.avg else 0.0
        return projectedAvg + otherAvg > maxPairAvg + 1e-9
    }

    /**
     * Lot size for one side. The cheaper leg is bought in larger size.
     */
    fun lotFor(lotShares: Double, minOrder: Double, cheap: Boolean, bonusPct: Double): Double {
        val bonus = if (cheap) 1.0 + max(0.0, bonusPct) else 1.0
        return max(lotShares * bonus, minOrder)
    }

    /**
     * How many more shares a side may take on before it is too far ahead.
     *
     * This is the cap that makes the strategy safe. "Always buy the cheaper
     * side" on its own is a trap: the cheaper side is cheaper because it is
     * losing, and it keeps getting cheaper, so an uncapped bot pours the whole
     * balance into the leg heading for zero and never assembles one pair. A
     * side may lead by a single lot and no more.
     *
     * Counted on filled shares only — measuring resting orders here would let a
     * large unfilled order on one side unlock unlimited buying on the other.
     */
    fun allowance(myShares: Double, theirShares: Double, lot: Double): Double =
        theirShares + lot - myShares

    /**
     * Profit threshold for rotating out of a leg.
     *
     * Cheaply-bought shares are let go sooner: they are the ones that ran, and
     * recycling them into the other side is what pulls the pair average down.
     */
    fun rotateTarget(leg: PairLeg, settings: PairSettings): Double? {
        if (leg.shares <= 1e-9 || leg.avg <= 0.0) return null
        val pct = if (leg.avg < settings.cheapLegUnder) {
            settings.cheapRotateProfitPct
        } else {
            settings.rotateProfitPct
        }
        return leg.avg * (1.0 + pct)
    }

    /** Rounds a price down to the venue's grid; buys must never round up. */
    fun snapDown(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = floor(price / tick + 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(0.0, 1.0)
    }

    /** Rounds a price up to the grid; sells must never round down. */
    fun snapUp(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        val snapped = kotlin.math.ceil(price / tick - 1e-9) * tick
        return (Math.round(snapped * 10000.0) / 10000.0).coerceIn(0.0, 1.0)
    }

    /**
     * What the book pays out at settlement.
     *
     * Matched pairs return $1 each whichever way the market resolves; only the
     * unmatched excess is exposed to the outcome.
     */
    fun settlementProceeds(up: PairLeg, down: PairLeg, winner: String): Double {
        val winningShares = if (winner == "Up") up.shares else down.shares
        return winningShares * 1.0
    }

    /**
     * Locked profit carried in the book right now: the part of the position
     * that no longer depends on the outcome.
     */
    fun lockedProfit(up: PairLeg, down: PairLeg): Double {
        val pairs = min(up.shares, down.shares)
        if (pairs <= 0.0) return 0.0
        return pairs * (1.0 - (up.avg + down.avg))
    }
}
