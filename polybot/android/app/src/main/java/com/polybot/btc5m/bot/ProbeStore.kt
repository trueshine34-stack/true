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

    /**
     * The dials, per account.
     *
     * The paper account keeps the original keys, so a desk that has been
     * running keeps its settings; the real one is stored beside it under its
     * own prefix and starts as a copy, so splitting the two changes nothing
     * about how the wallet was already trading.
     */
    private fun key(name: String, real: Boolean) = if (real) "real.$name" else name

    fun loadSettings(real: Boolean = false): ProbePlan.Settings = ProbePlan.Settings(
        enabled = prefs.getBoolean("enabled", false),
        stakeUsd = dial("stakeUsd", real, ProbePlan.DEFAULT_STAKE),
        // The lead has been ten seconds, then twenty, and is now forty-five.
        // A setting still sitting on either of the old defaults is an old
        // default rather than a choice, so it moves with them; a lead the
        // user has actually typed is theirs and is left alone.
        leadSec = prefs.getLong(key("leadSec", real), prefs.getLong("leadSec", ProbePlan.DEFAULT_LEAD_SEC))
            .let { if (it in ProbePlan.OLD_LEADS) ProbePlan.DEFAULT_LEAD_SEC else it },
        roomShare = dial("roomShare", real, ProbePlan.DEFAULT_ROOM),
        roundBand = dial("roundBand", real, ProbePlan.DEFAULT_ROUND_BAND),
        demo = prefs.getBoolean("demo", true),
        // The two were one switch, so "not demo" meant "real". A setting
        // saved before they were separated says which of the two was on, and
        // that is what it keeps until the person says otherwise.
        live = prefs.getBoolean("live", !prefs.getBoolean("demo", true)),
        bankUsd = prefs.getFloat("bankUsd", ProbePlan.DEFAULT_BANK.toFloat()).toDouble(),
        inside = prefs.getBoolean(key("inside", real), prefs.getBoolean("inside", false)),
        edgeUsd = dial("edgeUsd", real, ProbePlan.DEFAULT_EDGE),
    )

    /** One dial: the account's own value, or what the desk had before the split. */
    private fun dial(name: String, real: Boolean, fallback: Double): Double =
        prefs.getFloat(key(name, real), prefs.getFloat(name, fallback.toFloat())).toDouble()

    fun saveSettings(s: ProbePlan.Settings, real: Boolean = false) {
        val edit = prefs.edit()
            .putFloat(key("stakeUsd", real), s.stakeUsd.toFloat())
            .putLong(key("leadSec", real), s.leadSec)
            .putFloat(key("roomShare", real), s.roomShare.toFloat())
            .putFloat(key("roundBand", real), s.roundBand.toFloat())
            .putBoolean(key("inside", real), s.inside)
            .putFloat(key("edgeUsd", real), s.edgeUsd.toFloat())
        // Everything that is the desk's answer rather than an account's is
        // written once, by whichever account was being edited.
        if (!real) {
            edit
                .putBoolean("enabled", s.enabled)
                .putBoolean("demo", s.demo)
                .putBoolean("live", s.live)
                .putFloat("bankUsd", s.bankUsd.toFloat())
        }
        edit.apply()
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
                    // Older records carried a single flag; one add is one add.
                    adds = o.optInt("adds", if (o.optBoolean("added", false)) 1 else 0),
                    leg = o.optInt("leg", 0),
                    soldAt = o.optDouble("soldAt", 0.0),
                    back = o.optBoolean("back", false),
                    lowWater = o.optDouble("lowWater", 0.0),
                    why = o.optString("why"),
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
                    .put("adds", it.adds)
                    .put("leg", it.leg)
                    .put("soldAt", it.soldAt)
                    .put("back", it.back)
                    .put("lowWater", it.lowWater)
                    .put("why", it.why)
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

    fun clearRounds() =
        prefs.edit().remove("rounds").remove("streak").remove("streakLive").apply()

    /**
     * What the winning run has added to the stake, kept across restarts.
     *
     * One for each account. They run the same rule over the same windows and
     * still diverge — a bid the venue never filled is a window the wallet sat
     * out and the paper account traded — so a run is a property of an account
     * and not of the rule.
     */
    fun loadStreak(demo: Boolean): Double =
        prefs.getFloat(streakKey(demo), 0f).toDouble()

    fun saveStreak(demo: Boolean, streak: Double) =
        prefs.edit().putFloat(streakKey(demo), streak.toFloat()).apply()

    // The paper run keeps the old key, so a run in progress survives the
    // update rather than being quietly reset by a rename.
    private fun streakKey(demo: Boolean) = if (demo) "streak" else "streakLive"
}
