package com.polybot.btc5m.bot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The indicator bot's own books, kept across restarts and apart from the desk's. */
class SignalStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_signal", Context.MODE_PRIVATE)

    fun loadSettings(): SignalPlan.Settings = SignalPlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        bankUsd = prefs.getFloat("bankUsd", SignalPlan.DEFAULT_BANK_USD.toFloat()).toDouble(),
        clipUsd = prefs.getFloat("clipUsd", SignalPlan.DEFAULT_CLIP_USD.toFloat()).toDouble(),
        maxBuys = prefs.getInt("maxBuys", SignalPlan.DEFAULT_MAX_BUYS),
        maxPrice = prefs.getFloat("maxPrice", SignalPlan.DEFAULT_MAX_PRICE.toFloat()).toDouble(),
        fromSec = prefs.getLong("fromSec", SignalPlan.DEFAULT_FROM_SEC),
        untilSec = prefs.getLong("untilSec", SignalPlan.DEFAULT_UNTIL_SEC),
    )

    fun saveSettings(settings: SignalPlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putFloat("bankUsd", settings.bankUsd.toFloat())
            .putFloat("clipUsd", settings.clipUsd.toFloat())
            .putInt("maxBuys", settings.maxBuys)
            .putFloat("maxPrice", settings.maxPrice.toFloat())
            .putLong("fromSec", settings.fromSec)
            .putLong("untilSec", settings.untilSec)
            .apply()
    }

    fun loadTotals(): BotBook.Totals = BotBook.Totals(
        rounds = prefs.getInt("rounds", 0),
        buys = prefs.getInt("buys", 0),
        sells = prefs.getInt("sells", 0),
        spent = prefs.getFloat("spent", 0f).toDouble(),
        got = prefs.getFloat("got", 0f).toDouble(),
        settled = prefs.getFloat("settled", 0f).toDouble(),
        wins = prefs.getInt("wins", 0),
        losses = prefs.getInt("losses", 0),
    )

    fun saveTotals(totals: BotBook.Totals) {
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

    fun loadPast(): List<BotBook.Past> {
        val raw = prefs.getString("past", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                BotBook.Past(
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

    fun savePast(past: List<BotBook.Past>) {
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
