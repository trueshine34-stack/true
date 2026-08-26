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
        // The rule used to start at forty-five seconds, inside the first
        // minute. A setting saved back then would keep it there forever, so
        // that exact value is read as "never chosen" and takes the new default.
        firstAtSec = prefs.getLong("firstAtSec", LadderPlan.DEFAULT_FIRST_AT_SEC)
            .let { if (it == 45L) LadderPlan.DEFAULT_FIRST_AT_SEC else it },
        pauseSec = prefs.getLong("pauseSec", LadderPlan.DEFAULT_PAUSE_SEC),
        earlyMaxPrice = prefs.getFloat(
            "earlyMaxPrice",
            LadderPlan.DEFAULT_EARLY_MAX_PRICE.toFloat(),
        ).toDouble(),
        earlySec = prefs.getLong("earlySec", LadderPlan.DEFAULT_EARLY_SEC),
        untilSec = prefs.getLong("untilSec", LadderPlan.DEFAULT_UNTIL_SEC),
    )

    fun saveSettings(s: LadderPlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .putFloat("shares", s.shares.toFloat())
            .putLong("everySec", s.everySec)
            .putLong("firstAtSec", s.firstAtSec)
            .putLong("pauseSec", s.pauseSec)
            .putFloat("earlyMaxPrice", s.earlyMaxPrice.toFloat())
            .putLong("earlySec", s.earlySec)
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
        winStreak = prefs.getInt("winStreak", 0),
    )

    fun saveTotals(t: LadderBot.Totals) {
        prefs.edit()
            .putInt("rounds", t.rounds)
            .putInt("buys", t.buys)
            .putInt("sells", t.sells)
            .putFloat("spent", t.spent.toFloat())
            .putFloat("got", t.got.toFloat())
            .putFloat("settled", t.settled.toFloat())
            .putInt("winStreak", t.winStreak)
            .apply()
    }

    fun clear() = prefs.edit().remove("rounds").remove("buys").remove("sells")
        .remove("spent").remove("got").remove("settled")
        .remove("winStreak").apply()
}
