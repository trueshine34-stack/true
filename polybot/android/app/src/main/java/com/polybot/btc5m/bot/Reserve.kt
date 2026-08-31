package com.polybot.btc5m.bot

/**
 * Money in the wallet that the app may not spend.
 *
 * One line of arithmetic, kept on its own because of where it is applied:
 * between the venue's answer and every reading of the balance in the app. A
 * reserve enforced at each order instead would be a rule each new order had
 * to remember, and one that forgot would spend it.
 */
object Reserve {

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
