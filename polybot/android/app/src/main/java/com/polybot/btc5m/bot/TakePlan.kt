package com.polybot.btc5m.bot

/**
 * Taking a profit the book is showing but the standing offer will not reach.
 *
 * The sell ladder asks for a price and waits. That is right when the side runs
 * — and wrong when it goes up eight cents, stops, and comes back: the offer
 * sat above the whole move and the round ends flat or worse. This watches what
 * the bids are actually paying and closes the position the moment that is
 * enough, whatever the offer above it is asking.
 *
 * Enough means after the fee. The venue takes its cut out of a sale in money,
 * so a gain measured on the raw price is a gain that partly is not there —
 * fifteen percent on the screen has to be fifteen percent in the wallet.
 */
object TakePlan {

    /** The gain that is worth taking rather than waiting out. */
    const val DEFAULT_GAIN = 0.15

    data class Settings(
        val enabled: Boolean = false,
        val gain: Double = DEFAULT_GAIN,
    )

    /** What one share pays after the taker fee — the money, not the mark. */
    fun net(price: Double): Double = SellPercent.netSell(price)

    /**
     * Whether the bid is paying enough to close at.
     *
     * Measured against what the shares cost, after the fee: the point is the
     * profit that ends up in the balance.
     */
    fun ready(cost: Double, bid: Double?, settings: Settings): Boolean {
        if (!settings.enabled) return false
        if (cost <= 0.0) return false
        if (bid == null || bid <= 0.0) return false
        return net(bid) >= cost * (1.0 + settings.gain) - 1e-9
    }

    /** How much better than break-even the book is right now, as a share. */
    fun gainAt(cost: Double, bid: Double?): Double {
        if (cost <= 0.0 || bid == null || bid <= 0.0) return 0.0
        return net(bid) / cost - 1.0
    }

    /**
     * Where to sell.
     *
     * A tick under the bid, because this is meant to be taken now: an offer
     * placed exactly at the bid is a race with everyone else standing there,
     * and the tick is a fraction of the gain being collected.
     */
    fun takePrice(bid: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return maxOf(step, bid - step)
    }
}
