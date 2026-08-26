package com.polybot.btc5m.bot

import android.content.Context

/** The ladder bot's settings and running totals, kept across restarts. */
class LadderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_ladder", Context.MODE_PRIVATE)

    fun loadSettings(): LadderPlan.Settings = LadderPlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        bankUsd = prefs.getFloat("bankUsd", LadderPlan.DEFAULT_BANK_USD.toFloat()).toDouble(),
        shares = prefs.getFloat("shares", LadderPlan.DEFAULT_SHARES.toFloat()).toDouble(),
        everySec = prefs.getLong("everySec", LadderPlan.DEFAULT_EVERY_SEC),
        firstAtSec = prefs.getLong("firstAtSec", LadderPlan.DEFAULT_FIRST_AT_SEC),
        untilSec = prefs.getLong("untilSec", LadderPlan.DEFAULT_UNTIL_SEC),
    )

    fun saveSettings(s: LadderPlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .putFloat("shares", s.shares.toFloat())
            .putLong("everySec", s.everySec)
            .putLong("firstAtSec", s.firstAtSec)
            .putLong("untilSec", s.untilSec)
            .apply()
    }

    fun loadTotals(): LadderBot.Totals = LadderBot.Totals(
        rounds = prefs.getInt("rounds", 0),
        buys = prefs.getInt("buys", 0),
        sells = prefs.getInt("sells", 0),
        spent = prefs.getFloat("spent", 0f).toDouble(),
        got = prefs.getFloat("got", 0f).toDouble(),
        settled = prefs.getFloat("settled", 0f).toDouble(),
    )

    fun saveTotals(t: LadderBot.Totals) {
        prefs.edit()
            .putInt("rounds", t.rounds)
            .putInt("buys", t.buys)
            .putInt("sells", t.sells)
            .putFloat("spent", t.spent.toFloat())
            .putFloat("got", t.got.toFloat())
            .putFloat("settled", t.settled.toFloat())
            .apply()
    }

    fun clear() = prefs.edit().remove("rounds").remove("buys").remove("sells")
        .remove("spent").remove("got").remove("settled").apply()
}
