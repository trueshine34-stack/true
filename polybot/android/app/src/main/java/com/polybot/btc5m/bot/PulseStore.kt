package com.polybot.btc5m.bot

import android.content.Context

/** The pulse bot's settings and running totals, kept across restarts. */
class PulseStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_pulse", Context.MODE_PRIVATE)

    fun loadSettings(): PulsePlan.Settings = PulsePlan.Settings(
        // Always running, and not a stored answer. A rule switched off keeps
        // no record, and the record is the whole reason for paper money: the
        // question it answers — is this worth real money — cannot be answered
        // by a rule that was not running while nobody was watching. What is
        // still a choice is whose money it trades, which is [demo].
        enabled = true,
        bankUsd = prefs.getFloat("bankUsd", PulsePlan.DEFAULT_BANK_USD.toFloat()).toDouble(),
        shares = prefs.getFloat("shares", PulsePlan.DEFAULT_SHARES.toFloat()).toDouble(),
        minEdge = prefs.getFloat("minEdge", PulsePlan.DEFAULT_MIN_EDGE.toFloat()).toDouble(),
        minLean = prefs.getFloat("minLean", PulsePlan.DEFAULT_MIN_LEAN.toFloat()).toDouble(),
        minVolume = prefs.getFloat("minVolume", PulsePlan.DEFAULT_MIN_VOLUME.toFloat()).toDouble(),
        takePct = prefs.getFloat("takePct", PulsePlan.DEFAULT_TAKE_PCT.toFloat()).toDouble(),
        cutUsd = prefs.getFloat("cutUsd", PulsePlan.DEFAULT_CUT_USD.toFloat()).toDouble(),
        // A desk that was running this rule on real money keeps doing so:
        // the old "demo" flag being off is exactly the old way of saying it.
        live = prefs.getBoolean("live", !prefs.getBoolean("demo", true)),
    )

    fun saveSettings(s: PulsePlan.Settings) {
        prefs.edit()
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .putFloat("shares", s.shares.toFloat())
            .putFloat("minEdge", s.minEdge.toFloat())
            .putFloat("minLean", s.minLean.toFloat())
            .putFloat("minVolume", s.minVolume.toFloat())
            .putFloat("takePct", s.takePct.toFloat())
            .putFloat("cutUsd", s.cutUsd.toFloat())
            .putBoolean("live", s.live)
            .apply()
    }

    /**
     * The record, per account.
     *
     * The paper account keeps the original keys, so a desk that has been
     * running keeps the history it has built; the real one is stored beside
     * it under its own prefix and starts empty, which is the truth — until
     * now there was no such thing as this rule's real record separate from
     * its paper one.
     */
    private fun key(name: String, demo: Boolean) = if (demo) name else "real.$name"

    fun loadTotals(demo: Boolean = true): PulseBot.Totals = PulseBot.Totals(
        rounds = prefs.getInt(key("rounds", demo), 0),
        wins = prefs.getInt(key("wins", demo), 0),
        losses = prefs.getInt(key("losses", demo), 0),
        spent = prefs.getFloat(key("spent", demo), 0f).toDouble(),
        got = prefs.getFloat(key("got", demo), 0f).toDouble(),
        settled = prefs.getFloat(key("settled", demo), 0f).toDouble(),
    )

    fun saveTotals(t: PulseBot.Totals, demo: Boolean = true) {
        prefs.edit()
            .putInt(key("rounds", demo), t.rounds)
            .putInt(key("wins", demo), t.wins)
            .putInt(key("losses", demo), t.losses)
            .putFloat(key("spent", demo), t.spent.toFloat())
            .putFloat(key("got", demo), t.got.toFloat())
            .putFloat(key("settled", demo), t.settled.toFloat())
            .apply()
    }

    fun clear() {
        val edit = prefs.edit()
        for (demo in listOf(true, false)) {
            edit.remove(key("rounds", demo)).remove(key("wins", demo))
                .remove(key("losses", demo)).remove(key("spent", demo))
                .remove(key("got", demo)).remove(key("settled", demo))
        }
        edit.apply()
    }
}
