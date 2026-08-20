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

        // Calibration lives outside the daily bucket: it is what the model has
        // learned about itself, and that should not reset at midnight.
        private const val KEY_CAL_XY = "calSumXY"
        private const val KEY_CAL_XX = "calSumXX"
        private const val KEY_CAL_N = "calSamples"
        private const val KEY_CAL_BRIER = "calBrierSum"
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
        // Only the day's counters; the calibration it learned is kept.
        prefs.edit()
            .remove(KEY_DAY)
            .remove(KEY_TRADES)
            .remove(KEY_WINS)
            .remove(KEY_LOSSES)
            .remove(KEY_STREAK)
            .remove(KEY_PNL)
            .remove(KEY_STAKED)
            .apply()
    }

    fun loadCalibration(): Calibration = Calibration(
        sumXY = prefs.getFloat(KEY_CAL_XY, 0f).toDouble(),
        sumXX = prefs.getFloat(KEY_CAL_XX, 0f).toDouble(),
        samples = prefs.getInt(KEY_CAL_N, 0),
        brierSum = prefs.getFloat(KEY_CAL_BRIER, 0f).toDouble(),
    )

    fun saveCalibration(calibration: Calibration) {
        prefs.edit()
            .putFloat(KEY_CAL_XY, calibration.sumXY.toFloat())
            .putFloat(KEY_CAL_XX, calibration.sumXX.toFloat())
            .putInt(KEY_CAL_N, calibration.samples)
            .putFloat(KEY_CAL_BRIER, calibration.brierSum.toFloat())
            .apply()
    }

    fun clearCalibration() {
        prefs.edit()
            .remove(KEY_CAL_XY)
            .remove(KEY_CAL_XX)
            .remove(KEY_CAL_N)
            .remove(KEY_CAL_BRIER)
            .apply()
    }
}
