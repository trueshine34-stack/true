package com.polybot.btc5m.bot

/**
 * The books a walled-off bot keeps.
 *
 * Both small bots trade their own money out of the same wallet the desk uses,
 * which means "what has this one made" is a question the balance cannot answer.
 * They each keep their own ledger, and it is the same ledger, so it lives here
 * rather than twice.
 */
object BotBook {

    /** Everything a bot has ever done, in one line. */
    data class Totals(
        val rounds: Int = 0,
        val buys: Int = 0,
        val sells: Int = 0,
        /** Money paid for shares. */
        val spent: Double = 0.0,
        /** Money taken for shares. */
        val got: Double = 0.0,
        /** What shares held to the close were worth. */
        val settled: Double = 0.0,
        val wins: Int = 0,
        val losses: Int = 0,
    ) {
        val pnl: Double get() = got + settled - spent
    }

    /** A finished window, kept so a stats card can show more than a number. */
    data class Past(
        val windowStart: Long,
        val side: String,
        val shares: Double,
        val spent: Double,
        val got: Double,
        val pnl: Double,
        val note: String,
    )
}
