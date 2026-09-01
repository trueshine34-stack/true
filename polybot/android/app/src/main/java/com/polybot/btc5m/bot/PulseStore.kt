package com.polybot.btc5m.bot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The pulse bot's settings and running totals, kept across restarts. */
class PulseStore(
    context: Context,
    /**
     * Which pulse this is. The soft rule keeps its settings, its money and
     * its record in a file of its own — two rules sharing a store would be
     * one rule with a confusing history.
     */
    private val name: String = "polybot_pulse",
    /** And what it starts from, before anything has been stored. */
    private val fallback: PulsePlan.Settings = PulsePlan.Settings(),
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    fun loadSettings(): PulsePlan.Settings = PulsePlan.Settings(
        // Always running, and not a stored answer. A rule switched off keeps
        // no record, and the record is the whole reason for paper money: the
        // question it answers — is this worth real money — cannot be answered
        // by a rule that was not running while nobody was watching. What is
        // still a choice is whose money it trades, which is [demo].
        enabled = true,
        bankUsd = prefs.getFloat("bankUsd", fallback.bankUsd.toFloat()).toDouble(),
        stakeUsd = prefs.getFloat("stakeUsd", fallback.stakeUsd.toFloat()).toDouble(),
        stakePct = prefs.getFloat("stakePct", fallback.stakePct.toFloat()).toDouble(),
        // Everything that makes one rule softer than the other comes from
        // its own fallback, so the soft one is soft on a fresh install and
        // stays wherever it has been set afterwards.
        fromSec = fallback.fromSec,
        untilSec = fallback.untilSec,
        rideSec = fallback.rideSec,
        minEdge = prefs.getFloat("minEdge", fallback.minEdge.toFloat()).toDouble(),
        minLean = prefs.getFloat("minLean", fallback.minLean.toFloat()).toDouble(),
        minVolume = prefs.getFloat("minVolume", fallback.minVolume.toFloat()).toDouble(),
        minPrice = fallback.minPrice,
        maxPrice = fallback.maxPrice,
        ladder = fallback.ladder,
        takePct = prefs.getFloat("takePct", fallback.takePct.toFloat()).toDouble(),
        // A desk that was running this rule on real money keeps doing so:
        // the old "demo" flag being off is exactly the old way of saying it.
        live = prefs.getBoolean("live", !prefs.getBoolean("demo", true)),
    )

    fun saveSettings(s: PulsePlan.Settings) {
        prefs.edit()
            .putFloat("bankUsd", s.bankUsd.toFloat())
            .putFloat("stakeUsd", s.stakeUsd.toFloat())
            .putFloat("stakePct", s.stakePct.toFloat())
            .putFloat("minEdge", s.minEdge.toFloat())
            .putFloat("minLean", s.minLean.toFloat())
            .putFloat("minVolume", s.minVolume.toFloat())
            .putFloat("takePct", s.takePct.toFloat())
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

    /**
     * Every window an account has closed, kept across restarts.
     *
     * Totals alone cannot tell a steady run from a wild one that happens to
     * net the same, and the second is the one worth stopping — so the rounds
     * themselves are kept, per account, capped where the bot caps them.
     */
    fun loadRounds(demo: Boolean): List<PulseBot.Round> {
        val raw = prefs.getString(key("rounds.json", demo), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                PulseBot.Round(
                    windowStart = o.optLong("windowStart"),
                    boughtAt = o.optLong("boughtAt"),
                    demo = demo,
                    outcome = o.optString("outcome"),
                    shares = o.optDouble("shares", 0.0),
                    price = o.optDouble("price", 0.0),
                    spent = o.optDouble("spent", 0.0),
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

    fun saveRounds(rounds: List<PulseBot.Round>, demo: Boolean) {
        val array = JSONArray()
        rounds.forEach {
            array.put(
                JSONObject()
                    .put("windowStart", it.windowStart)
                    .put("boughtAt", it.boughtAt)
                    .put("outcome", it.outcome)
                    .put("shares", it.shares)
                    .put("price", it.price)
                    .put("spent", it.spent)
                    .put("proceeds", it.proceeds)
                    .put("settled", it.settled)
                    .put("winner", it.winner)
                    .put("note", it.note),
            )
        }
        prefs.edit().putString(key("rounds.json", demo), array.toString()).apply()
    }

    fun clear() {
        val edit = prefs.edit()
        for (demo in listOf(true, false)) {
            edit.remove(key("rounds", demo)).remove(key("wins", demo))
                .remove(key("losses", demo)).remove(key("spent", demo))
                .remove(key("got", demo)).remove(key("settled", demo))
                .remove(key("rounds.json", demo))
        }
        edit.apply()
    }
}
