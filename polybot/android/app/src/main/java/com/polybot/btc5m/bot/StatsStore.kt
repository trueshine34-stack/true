package com.polybot.btc5m.bot

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily trading statistics, kept across restarts.
 *
 * Stopping the bot is a normal thing to do mid-session, so the numbers cannot
 * live and die with the service. They are bucketed by local calendar day and
 * roll over on their own at midnight.
 */
class StatsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_TRADES = "trades"
        private const val KEY_WINS = "wins"
        private const val KEY_LOSSES = "losses"
        private const val KEY_STREAK = "consecutiveLosses"
        private const val KEY_PNL = "realisedPnlUsd"
        private const val KEY_STAKED = "stakedUsd"
    }

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Today's figures, or a clean slate if the stored bucket is from before. */
    fun load(): Stats {
        if (prefs.getString(KEY_DAY, null) != today()) return Stats()
        return Stats(
            trades = prefs.getInt(KEY_TRADES, 0),
            wins = prefs.getInt(KEY_WINS, 0),
            losses = prefs.getInt(KEY_LOSSES, 0),
            consecutiveLosses = prefs.getInt(KEY_STREAK, 0),
            realisedPnlUsd = prefs.getFloat(KEY_PNL, 0f).toDouble(),
            stakedUsd = prefs.getFloat(KEY_STAKED, 0f).toDouble(),
        )
    }

    fun save(stats: Stats) {
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_TRADES, stats.trades)
            .putInt(KEY_WINS, stats.wins)
            .putInt(KEY_LOSSES, stats.losses)
            .putInt(KEY_STREAK, stats.consecutiveLosses)
            .putFloat(KEY_PNL, stats.realisedPnlUsd.toFloat())
            .putFloat(KEY_STAKED, stats.stakedUsd.toFloat())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
