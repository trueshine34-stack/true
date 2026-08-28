package com.polybot.btc5m.bot

import android.content.Context

/** The take rule's switch and threshold, kept across restarts. */
class TakeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_take", Context.MODE_PRIVATE)

    fun load(): TakePlan.Settings = TakePlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        gain = prefs.getFloat("gain", TakePlan.DEFAULT_GAIN.toFloat()).toDouble(),
    )

    fun save(s: TakePlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putFloat("gain", s.gain.toFloat())
            .apply()
    }
}
