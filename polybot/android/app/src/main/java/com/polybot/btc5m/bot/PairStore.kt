package com.polybot.btc5m.bot

import android.content.Context

/**
 * The pair bot's books, kept across restarts.
 *
 * Two sets of figures, never mixed: paper and live. A test run that quietly
 * inflated the real numbers would make the whole journal worthless, and the
 * point of the test mode is to be able to compare the two.
 *
 * Unlike the main bot's daily bucket these totals never roll over — the user
 * wants a running balance over every session, so that is what this keeps.
 * Money is stored as raw double bits rather than as a float: a float carries
 * about seven digits, which is fine for one window's P&L and not fine for a
 * balance that accumulates over weeks.
 */
class PairStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_pair", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PAPER_CASH = "paperCash"
        private const val KEY_PAPER_SEEDED = "paperSeeded"
    }

    private fun putMoney(editor: android.content.SharedPreferences.Editor, key: String, v: Double) {
        editor.putLong(key, java.lang.Double.doubleToRawLongBits(v))
    }

    private fun money(key: String, fallback: Double = 0.0): Double {
        if (!prefs.contains(key)) return fallback
        return java.lang.Double.longBitsToDouble(prefs.getLong(key, 0L))
    }

    private fun prefix(dryRun: Boolean) = if (dryRun) "test." else "live."

    fun loadStats(dryRun: Boolean): PairStats {
        val p = prefix(dryRun)
        return PairStats(
            windows = prefs.getInt("${p}windows", 0),
            buys = prefs.getInt("${p}buys", 0),
            sells = prefs.getInt("${p}sells", 0),
            pairsLocked = money("${p}pairsLocked"),
            feesUsd = money("${p}fees"),
            realisedPnlUsd = money("${p}pnl"),
        )
    }

    fun saveStats(dryRun: Boolean, stats: PairStats) {
        val p = prefix(dryRun)
        val editor = prefs.edit()
            .putInt("${p}windows", stats.windows)
            .putInt("${p}buys", stats.buys)
            .putInt("${p}sells", stats.sells)
        putMoney(editor, "${p}pairsLocked", stats.pairsLocked)
        putMoney(editor, "${p}fees", stats.feesUsd)
        putMoney(editor, "${p}pnl", stats.realisedPnlUsd)
        editor.apply()
    }

    /**
     * Paper cash. Seeded once from the configured starting balance, then it is
     * the run's own history — re-seeding it on every start would hide exactly
     * the thing the test mode exists to show.
     */
    fun loadPaperCash(startUsd: Double): Double {
        if (!prefs.getBoolean(KEY_PAPER_SEEDED, false)) {
            savePaperCash(startUsd)
            return startUsd
        }
        return money(KEY_PAPER_CASH, startUsd)
    }

    fun savePaperCash(value: Double) {
        val editor = prefs.edit().putBoolean(KEY_PAPER_SEEDED, true)
        putMoney(editor, KEY_PAPER_CASH, value)
        editor.apply()
    }

    fun clear(startUsd: Double) {
        prefs.edit().clear().apply()
        savePaperCash(startUsd)
    }
}
