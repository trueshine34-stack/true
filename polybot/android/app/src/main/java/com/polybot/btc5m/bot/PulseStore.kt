package com.polybot.btc5m.bot

import android.content.Context

/** The pulse bot's settings and running totals, kept across restarts. */
class PulseStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_pulse", Context.MODE_PRIVATE)

    fun loadSettings(): PulsePlan.Settings = PulsePlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        bankUsd = prefs.getFloat("bankUsd", PulsePlan.DEFAULT_BANK_USD.toFloat()).toDouble(),
        shares = prefs.getFloat("shares", PulsePlan.DEFAULT_SHARES.toFloat()).toDouble(),
        minEdge = prefs.getFloat("minEdge", PulsePlan.DEFAULT_MIN_EDGE.toFloat()).toDouble(),
        minLean = prefs.getFloat("minLean", PulsePlan.DEFAULT_MIN_LEAN.toFloat()).toDouble(),
        minVolume = prefs.getFloat("minVolume", PulsePlan.DEFAULT_MIN_VOLUME.toFloat()).toDouble(),
        takePct = prefs.getFloat("takePct", PulsePlan.DEFAULT_TAKE_PCT.toFloat()).toDouble(),
        cutUsd = prefs.getFloat("cutUsd", PulsePlan.DEFAULT_CUT_USD.toFloat()).toDouble(),
    )

    fun saveSettings(s: PulsePlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .putFloat("shares", s.shares.toFloat())
            .putFloat("minEdge", s.minEdge.toFloat())
            .putFloat("minLean", s.minLean.toFloat())
            .putFloat("minVolume", s.minVolume.toFloat())
            .putFloat("takePct", s.takePct.toFloat())
            .putFloat("cutUsd", s.cutUsd.toFloat())
            .apply()
    }

    fun loadTotals(): PulseBot.Totals = PulseBot.Totals(
        rounds = prefs.getInt("rounds", 0),
        wins = prefs.getInt("wins", 0),
        losses = prefs.getInt("losses", 0),
        spent = prefs.getFloat("spent", 0f).toDouble(),
        got = prefs.getFloat("got", 0f).toDouble(),
        settled = prefs.getFloat("settled", 0f).toDouble(),
    )

    fun saveTotals(t: PulseBot.Totals) {
        prefs.edit()
            .putInt("rounds", t.rounds)
            .putInt("wins", t.wins)
            .putInt("losses", t.losses)
            .putFloat("spent", t.spent.toFloat())
            .putFloat("got", t.got.toFloat())
            .putFloat("settled", t.settled.toFloat())
            .apply()
    }

    fun clear() = prefs.edit()
        .remove("rounds").remove("wins").remove("losses")
        .remove("spent").remove("got").remove("settled")
        .apply()
}
