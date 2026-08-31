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

    fun load(): Double = prefs.getFloat("usd", 0f).toDouble()

    fun save(usd: Double) {
        prefs.edit().putFloat("usd", maxOf(0.0, usd).toFloat()).apply()
    }
}
