package com.polybot.btc5m.bot

/**
 * Money in the wallet that the app may not spend.
 *
 * A little arithmetic, kept on its own because of where it is applied:
 * between the venue's answer and every reading of the balance in the app. A
 * reserve enforced at each order instead would be a rule each new order had
 * to remember, and one that forgot would spend it.
 */
object Reserve {

    /**
     * How long after a window closes its settlement counts as money in flight.
     *
     * A share reserve is taken of everything the run is worth: the cash, what
     * is in the market at what it cost, and what a closed window is about to
     * pay. The payout lands in the wallet a few seconds after the boundary,
     * and inside that gap the money is in neither place — so it is counted
     * here, and stops being counted once the balance is showing it.
     *
     * Counting a settled window's *cost* for a further five minutes was the
     * bug this replaced: it added the cost to a wallet that had already been
     * paid, so on a twelve-dollar wallet with three dollars of settled shares
     * a seventy-five percent reserve was taken of fifteen and left eighty-
     * seven cents to trade with instead of three dollars nineteen.
     */
    const val SETTLE_GRACE_SEC = 30L

    /** Polymarket's taker fee, charged on top of what an order is worth. */
    private const val FEE_RATE = 0.07

    /**
     * What a buy actually takes out of the wallet.
     *
     * The fee is charged on top of the order rather than out of it, so an
     * order for exactly the free balance cannot be paid for and is refused by
     * the venue — and a reserve sized to the cent has to know that too.
     */
    fun buyCost(shares: Double, price: Double): Double {
        if (!shares.isFinite() || !price.isFinite() || shares <= 0.0 || price <= 0.0) {
            return 0.0
        }
        val fee = if (price >= 1.0) 0.0 else FEE_RATE * price * (1 - price)
        return shares * (price + fee)
    }

    /**
     * The side the last seconds of a window have already decided against.
     *
     * At five seconds from the close the price has said what the window is:
     * whichever side is behind is not coming back, and the money in it is
     * spent whatever happens next. A share reserve counts open positions at
     * what they cost so that opening one does not free a fresh slice — but a
     * position that has already lost is not a position any more, it is a
     * receipt, and holding money against it locks away money twice over.
     *
     * Null while the window is still a question: no lead, no reading, or the
     * two sides level.
     */
    fun losingSide(lead: Double?, secondsLeft: Long, lastSec: Long = DOOMED_SEC): String? {
        if (lead == null || !lead.isFinite()) return null
        if (secondsLeft !in 0..lastSec) return null
        return when {
            lead > 0.0 -> "Down"
            lead < 0.0 -> "Up"
            else -> null
        }
    }

    /** How near the close a losing side stops being counted as money held. */
    const val DOOMED_SEC = 5L

    /**
     * How much of [wallet] is locked away, given the two ways of saying it.
     *
     * A share is not the same setting as a sum, and which one is wanted
     * depends on what the reserve is for. A sum keeps a fixed amount out of
     * the market — rent, the float for something else — and stays that amount
     * whether the run doubles or halves. A share keeps a proportion, so a
     * winning run locks more away by itself and a losing one frees it back:
     * the amount at risk stays the same fraction of the account without
     * anyone having to move the number.
     *
     * A share, where one is set, is the answer: nothing sensible can be made
     * of both at once, and the desk sets one and clears the other.
     */
    fun lockedOf(wallet: Double, usd: Double, pct: Double): Double {
        if (!wallet.isFinite() || wallet <= 0.0) return 0.0
        if (pct.isFinite() && pct > 0.0) {
            // Above the whole wallet a share means the whole wallet, which is
            // "trade nothing" — a coherent, if drastic, setting.
            return wallet * minOf(1.0, pct)
        }
        if (!usd.isFinite() || usd <= 0.0) return 0.0
        return usd
    }

    /**
     * What is left of [wallet] once [locked] is set aside.
     *
     * Never negative: a reserve larger than the wallet means there is nothing
     * to trade with, which is a balance of zero and not a debt. And a locked
     * amount that is not a number at all is no reserve — a broken setting must
     * not quietly stop the desk.
     */
    fun free(wallet: Double, locked: Double): Double {
        if (!wallet.isFinite() || wallet <= 0.0) return 0.0
        if (!locked.isFinite() || locked <= 0.0) return wallet
        return maxOf(0.0, wallet - locked)
    }
}
