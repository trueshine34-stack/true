package com.polybot.btc5m.bot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The counter bot's own books, kept across restarts.
 *
 * It trades its own walled-off money, so "what has it made" is a question only
 * it can answer — the wallet's balance mixes it with the desk's. The running
 * totals and the last few rounds are therefore written down rather than
 * recomputed, and they survive the service being killed mid-window.
 */
class CounterStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_counter", Context.MODE_PRIVATE)

    fun loadSettings(): CounterPlan.Settings = CounterPlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        bankUsd = prefs.getFloat("bankUsd", CounterPlan.DEFAULT_BANK_USD.toFloat()).toDouble(),
        clipUsd = prefs.getFloat("clipUsd", CounterPlan.DEFAULT_CLIP_USD.toFloat()).toDouble(),
        maxBuys = prefs.getInt("maxBuys", CounterPlan.DEFAULT_MAX_BUYS),
        entryUnder = prefs.getFloat("entryUnder", CounterPlan.DEFAULT_ENTRY_UNDER.toFloat())
            .toDouble(),
        entryWindowSec = prefs.getLong("entryWindowSec", CounterPlan.DEFAULT_ENTRY_WINDOW_SEC),
        gainPct = prefs.getFloat("gainPct", CounterPlan.DEFAULT_GAIN.toFloat()).toDouble(),
    )

    fun saveSettings(settings: CounterPlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putFloat("bankUsd", settings.bankUsd.toFloat())
            .putFloat("clipUsd", settings.clipUsd.toFloat())
            .putInt("maxBuys", settings.maxBuys)
            .putFloat("entryUnder", settings.entryUnder.toFloat())
            .putLong("entryWindowSec", settings.entryWindowSec)
            .putFloat("gainPct", settings.gainPct.toFloat())
            .apply()
    }

    fun loadTotals(): CounterBot.Totals = CounterBot.Totals(
        rounds = prefs.getInt("rounds", 0),
        buys = prefs.getInt("buys", 0),
        sells = prefs.getInt("sells", 0),
        spent = prefs.getFloat("spent", 0f).toDouble(),
        got = prefs.getFloat("got", 0f).toDouble(),
        settled = prefs.getFloat("settled", 0f).toDouble(),
        wins = prefs.getInt("wins", 0),
        losses = prefs.getInt("losses", 0),
    )

    fun saveTotals(totals: CounterBot.Totals) {
        prefs.edit()
            .putInt("rounds", totals.rounds)
            .putInt("buys", totals.buys)
            .putInt("sells", totals.sells)
            .putFloat("spent", totals.spent.toFloat())
            .putFloat("got", totals.got.toFloat())
            .putFloat("settled", totals.settled.toFloat())
            .putInt("wins", totals.wins)
            .putInt("losses", totals.losses)
            .apply()
    }

    fun loadPast(): List<CounterBot.Past> {
        val raw = prefs.getString("past", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                CounterBot.Past(
                    windowStart = o.optLong("windowStart"),
                    side = o.optString("side"),
                    shares = o.optDouble("shares"),
                    spent = o.optDouble("spent"),
                    got = o.optDouble("got"),
                    pnl = o.optDouble("pnl"),
                    note = o.optString("note"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePast(past: List<CounterBot.Past>) {
        val array = JSONArray()
        past.take(24).forEach {
            array.put(
                JSONObject()
                    .put("windowStart", it.windowStart)
                    .put("side", it.side)
                    .put("shares", it.shares)
                    .put("spent", it.spent)
                    .put("got", it.got)
                    .put("pnl", it.pnl)
                    .put("note", it.note),
            )
        }
        prefs.edit().putString("past", array.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
