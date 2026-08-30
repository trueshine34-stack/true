package com.polybot.btc5m.bot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The probe's settings and its record of every window it traded.
 *
 * The record is the point of the whole thing — a test that cannot be read
 * afterwards has tested nothing — so rounds are kept as they close, capped at
 * a couple of hundred, which is more than a day of five-minute windows.
 */
class ProbeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_probe", Context.MODE_PRIVATE)

    private companion object {
        const val MAX_ROUNDS = 200
    }

    fun loadSettings(): ProbePlan.Settings = ProbePlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        stakeUsd = prefs.getFloat("stakeUsd", ProbePlan.DEFAULT_STAKE.toFloat()).toDouble(),
        leadSec = prefs.getLong("leadSec", ProbePlan.DEFAULT_LEAD_SEC),
        roomShare = prefs.getFloat("roomShare", ProbePlan.DEFAULT_ROOM.toFloat()).toDouble(),
        roundBand = prefs.getFloat("roundBand", ProbePlan.DEFAULT_ROUND_BAND.toFloat()).toDouble(),
        demo = prefs.getBoolean("demo", true),
        bankUsd = prefs.getFloat("bankUsd", ProbePlan.DEFAULT_BANK.toFloat()).toDouble(),
    )

    fun saveSettings(s: ProbePlan.Settings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putFloat("stakeUsd", s.stakeUsd.toFloat())
            .putLong("leadSec", s.leadSec)
            .putFloat("roomShare", s.roomShare.toFloat())
            .putFloat("roundBand", s.roundBand.toFloat())
            .putBoolean("demo", s.demo)
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .apply()
    }

    fun loadRounds(): List<ProbeBot.Round> {
        val raw = prefs.getString("rounds", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                ProbeBot.Round(
                    windowStart = o.optLong("windowStart"),
                    asset = o.optString("asset"),
                    demo = o.optBoolean("demo", false),
                    target = o.optDouble("target", 0.0),
                    side = o.optString("side"),
                    perHour = o.optDouble("perHour", 0.0),
                    shares = o.optDouble("shares", 0.0),
                    price = o.optDouble("price", 0.0),
                    sold = o.optDouble("sold", 0.0),
                    proceeds = o.optDouble("proceeds", 0.0),
                    settled = o.optDouble("settled", 0.0),
                    winner = o.optString("winner"),
                    note = o.optString("note").takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRounds(rounds: List<ProbeBot.Round>) {
        val array = JSONArray()
        rounds.takeLast(MAX_ROUNDS).forEach {
            array.put(
                JSONObject()
                    .put("windowStart", it.windowStart)
                    .put("asset", it.asset)
                    .put("demo", it.demo)
                    .put("target", it.target)
                    .put("side", it.side)
                    .put("perHour", it.perHour)
                    .put("shares", it.shares)
                    .put("price", it.price)
                    .put("sold", it.sold)
                    .put("proceeds", it.proceeds)
                    .put("settled", it.settled)
                    .put("winner", it.winner)
                    .put("note", it.note ?: ""),
            )
        }
        prefs.edit().putString("rounds", array.toString()).apply()
    }

    fun clearRounds() = prefs.edit().remove("rounds").apply()
}
