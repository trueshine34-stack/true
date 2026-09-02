package com.polybot.btc5m.bot

import android.content.Context

/**
 * Whether the clock speaks, kept across restarts.
 *
 * On its own rather than with the desk's settings because the service reads it
 * without the screen: the cue exists for a phone in a pocket, and a phone in a
 * pocket is exactly the case where the WebView has never been opened since the
 * process came up.
 */
class CueStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_cue", Context.MODE_PRIVATE)

    fun load(): Boolean = prefs.getBoolean("countdown", false)

    fun save(on: Boolean) {
        prefs.edit().putBoolean("countdown", on).apply()
    }
}
