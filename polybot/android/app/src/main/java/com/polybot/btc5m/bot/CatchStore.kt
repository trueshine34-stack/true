package com.polybot.btc5m.bot

import android.content.Context

/** The catcher's settings and running totals, kept across restarts. */
class CatchStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_catch", Context.MODE_PRIVATE)

    fun loadSettings(): CatchPlan.Settings = CatchPlan.Settings(
        // Armed by hand, every time. A rule that starts trading a side on its
        // own because it was left on yesterday is not what the button meant.
        enabled = false,
        bankUsd = prefs.getFloat("bankUsd", CatchPlan.DEFAULT_BANK_USD.toFloat()).toDouble(),
        drop = prefs.getFloat("drop", CatchPlan.DROP.toFloat()).toDouble(),
        step = prefs.getFloat("step", CatchPlan.STEP.toFloat()).toDouble(),
        gain = prefs.getFloat("gain", CatchPlan.GAIN.toFloat()).toDouble(),
        spread = prefs.getFloat("spread", CatchPlan.SPREAD.toFloat()).toDouble(),
        share = prefs.getFloat("share", CatchPlan.SHARE.toFloat()).toDouble(),
        minShares = prefs.getFloat("minShares", CatchPlan.MIN_SHARES.toFloat()).toDouble(),
    )

    fun saveSettings(s: CatchPlan.Settings) {
        prefs.edit()
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .putFloat("drop", s.drop.toFloat())
            .putFloat("step", s.step.toFloat())
            .putFloat("gain", s.gain.toFloat())
            .putFloat("spread", s.spread.toFloat())
            .putFloat("share", s.share.toFloat())
            .putFloat("minShares", s.minShares.toFloat())
            .apply()
    }

    fun loadTotals(): CatchBot.Totals = CatchBot.Totals(
        buys = prefs.getInt("buys", 0),
        sells = prefs.getInt("sells", 0),
        spent = prefs.getFloat("spent", 0f).toDouble(),
        got = prefs.getFloat("got", 0f).toDouble(),
        settled = prefs.getFloat("settled", 0f).toDouble(),
    )

    fun saveTotals(t: CatchBot.Totals) {
        prefs.edit()
            .putInt("buys", t.buys)
            .putInt("sells", t.sells)
            .putFloat("spent", t.spent.toFloat())
            .putFloat("got", t.got.toFloat())
            .putFloat("settled", t.settled.toFloat())
            .apply()
    }

    fun clear() = prefs.edit()
        .remove("buys").remove("sells")
        .remove("spent").remove("got").remove("settled")
        .apply()
}
