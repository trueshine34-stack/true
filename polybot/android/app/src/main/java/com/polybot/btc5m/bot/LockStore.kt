package com.polybot.btc5m.bot

import android.content.Context

/**
 * Money the app is not allowed to touch, kept across restarts.
 *
 * It is a property of the wallet rather than of a rule, so it lives on its
 * own and outlives every switch: a reserve that survived only while the desk
 * was open would be no reserve at all.
 */
class LockStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_lock", Context.MODE_PRIVATE)

    /** The fixed sum, and the share of the wallet, in that order. */
    fun load(): Pair<Double, Double> = Pair(
        prefs.getFloat("usd", 0f).toDouble(),
        prefs.getFloat("pct", 0f).toDouble(),
    )

    fun save(usd: Double, pct: Double) {
        prefs.edit()
            .putFloat("usd", maxOf(0.0, usd).toFloat())
            .putFloat("pct", pct.coerceIn(0.0, 1.0).toFloat())
            .apply()
    }
}
