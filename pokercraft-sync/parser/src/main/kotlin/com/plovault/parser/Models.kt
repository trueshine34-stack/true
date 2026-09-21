package com.plovault.parser

/** Тип игры, распознанный по заголовку раздачи и количеству карт героя. */
enum class GameCode { PLO4, PLO5, PLO6, NLH, OTHER }

data class PlayerSeat(
    val seat: Int,
    val name: String,
    val stack: Double,
    val isHero: Boolean,
    val position: String
)

/**
 * Одна разобранная раздача. Все денежные значения — в валюте стола.
 * heroNet = собрано из банка (+ cash out) - вложено.
 */
data class ParsedHand(
    val handId: String,
    val site: String,
    val gameLabel: String,
    val gameCode: GameCode,
    val isRushAndCash: Boolean,
    val currency: String,
    val smallBlind: Double,
    val bigBlind: Double,
    val ante: Double,
    val timestampMillis: Long,
    val dateText: String,
    val tableName: String,
    val maxSeats: Int,
    val buttonSeat: Int,
    val playerCount: Int,
    val players: List<PlayerSeat>,
    val heroName: String,
    val heroSeat: Int,
    val heroCards: List<String>,
    val heroPosition: String,
    val board: List<String>,
    val streetsSeen: Int,
    val heroInvested: Double,
    val heroCollected: Double,
    val heroNet: Double,
    val totalPot: Double,
    val rake: Double,
    val jackpotFees: Double,
    val heroVpip: Boolean,
    val heroPfr: Boolean,
    val heroThreeBet: Boolean,
    val heroFacedThreeBet: Boolean,
    val heroSawFlop: Boolean,
    val heroWentToShowdown: Boolean,
    val heroWonHand: Boolean,
    val heroAllIn: Boolean,
    val cashedOut: Boolean,
    val cashOutNet: Double,
    val raw: String
) {
    /** Результат в больших блайндах. */
    val heroNetBb: Double get() = if (bigBlind > 0) heroNet / bigBlind else 0.0

    /** Метка лимита, напр. "PLO4 RC $0.05/$0.10". */
    val stakeLabel: String
        get() = buildString {
            append(gameCode.name)
            if (isRushAndCash) append(" RC")
            append(' ')
            append(currency)
            append(fmt(smallBlind))
            append('/')
            append(currency)
            append(fmt(bigBlind))
        }

    private fun fmt(v: Double): String =
        if (v >= 1.0) String.format("%.2f", v).trimEnd('0').trimEnd('.') else String.format("%.2f", v)
}

data class ParseResult(
    val hands: List<ParsedHand>,
    val failedBlocks: Int,
    val errors: List<String>
)
