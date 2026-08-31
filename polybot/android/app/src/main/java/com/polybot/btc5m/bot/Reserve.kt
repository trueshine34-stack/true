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
