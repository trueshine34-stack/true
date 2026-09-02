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
     * How long after a window closes its shares still count as money in flight.
     *
     * A share-based reserve is taken of everything the run is worth: the cash
     * plus what is in the market at what it cost. At the boundary the market's
     * half settles — the winning side pays a dollar, the losing side pays
     * nothing — and the payout lands in the wallet a few seconds later. Inside
     * that gap the money is in neither place, so the position keeps counting.
     *
     * After it, it must stop. Counting a settled window for a further five
     * minutes was adding its cost to a wallet that had already been paid it:
     * on a twelve-dollar wallet with three dollars of settled shares, a
     * seventy-five percent reserve was taken of fifteen and left eighty-seven
     * cents to trade with instead of three dollars.
     */
    const val SETTLE_GRACE_SEC = 30L

    /**
     * The earliest window whose positions still count as held.
     *
     * The window running now, always — and the one before it while its
     * settlement is still on its way.
     */
    fun heldSince(nowSec: Long, windowSec: Long): Long {
        val window = nowSec - (nowSec % windowSec)
        return if (nowSec - window <= SETTLE_GRACE_SEC) window - windowSec else window
    }

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
