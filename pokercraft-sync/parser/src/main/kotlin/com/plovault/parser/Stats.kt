package com.plovault.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class HandFilter(
    val games: Set<GameCode>? = setOf(GameCode.PLO4),
    val rushAndCashOnly: Boolean? = true,
    val stakes: Set<String>? = null,
    val positions: Set<String>? = null,
    val fromMillis: Long? = null,
    val toMillis: Long? = null
) {
    fun matches(h: ParsedHand): Boolean {
        if (games != null && h.gameCode !in games) return false
        if (rushAndCashOnly == true && !h.isRushAndCash) return false
        if (rushAndCashOnly == false && h.isRushAndCash) return false
        if (stakes != null && h.stakeLabel !in stakes) return false
        if (positions != null && h.heroPosition !in positions) return false
        if (fromMillis != null && h.timestampMillis < fromMillis) return false
        if (toMillis != null && h.timestampMillis > toMillis) return false
        return true
    }
}

data class Stats(
    val label: String,
    val hands: Int,
    val netCash: Double,
    val netBb: Double,
    val bbPer100: Double,
    val vpip: Double,
    val pfr: Double,
    val threeBet: Double,
    val wwsf: Double,
    val wtsd: Double,
    val wsd: Double,
    val sawFlop: Double,
    val rake: Double,
    val rakeBbPer100: Double,
    val jackpotFees: Double,
    val allInHands: Int,
    val biggestWin: Double,
    val biggestLoss: Double,
    val firstHandMillis: Long,
    val lastHandMillis: Long
)

object StatsEngine {

    fun compute(hands: List<ParsedHand>, label: String = "Всего"): Stats {
        if (hands.isEmpty()) {
            return Stats(label, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0, 0)
        }
        val n = hands.size
        var net = 0.0
        var netBb = 0.0
        var vpip = 0
        var pfr = 0
        var threeBetOpp = 0
        var threeBet = 0
        var sawFlop = 0
        var wonAfterFlop = 0
        var wtsd = 0
        var wsd = 0
        var rake = 0.0
        var rakeBb = 0.0
        var jackpot = 0.0
        var allIn = 0
        var best = Double.NEGATIVE_INFINITY
        var worst = Double.POSITIVE_INFINITY
        var first = Long.MAX_VALUE
        var last = 0L

        for (h in hands) {
            net += h.heroNet
            netBb += h.heroNetBb
            if (h.heroVpip) vpip++
            if (h.heroPfr) pfr++
            if (h.heroFacedThreeBet || h.heroThreeBet) threeBetOpp++
            if (h.heroThreeBet) threeBet++
            if (h.heroSawFlop) {
                sawFlop++
                if (h.heroWonHand) wonAfterFlop++
            }
            if (h.heroWentToShowdown) {
                wtsd++
                if (h.heroWonHand) wsd++
            }
            rake += h.rake
            if (h.bigBlind > 0) rakeBb += h.rake / h.bigBlind
            jackpot += h.jackpotFees
            if (h.heroAllIn) allIn++
            if (h.heroNet > best) best = h.heroNet
            if (h.heroNet < worst) worst = h.heroNet
            if (h.timestampMillis in 1 until first) first = h.timestampMillis
            if (h.timestampMillis > last) last = h.timestampMillis
        }

        return Stats(
            label = label,
            hands = n,
            netCash = round2(net),
            netBb = round2(netBb),
            bbPer100 = round2(netBb / n * 100.0),
            vpip = pct(vpip, n),
            pfr = pct(pfr, n),
            threeBet = if (threeBetOpp > 0) pct(threeBet, threeBetOpp) else 0.0,
            wwsf = if (sawFlop > 0) pct(wonAfterFlop, sawFlop) else 0.0,
            wtsd = if (sawFlop > 0) pct(wtsd, sawFlop) else 0.0,
            wsd = if (wtsd > 0) pct(wsd, wtsd) else 0.0,
            sawFlop = pct(sawFlop, n),
            rake = round2(rake),
            rakeBbPer100 = round2(rakeBb / n * 100.0),
            jackpotFees = round2(jackpot),
            allInHands = allIn,
            biggestWin = if (best == Double.NEGATIVE_INFINITY) 0.0 else round2(best),
            biggestLoss = if (worst == Double.POSITIVE_INFINITY) 0.0 else round2(worst),
            firstHandMillis = if (first == Long.MAX_VALUE) 0 else first,
            lastHandMillis = last
        )
    }

    fun byStake(hands: List<ParsedHand>): List<Stats> =
        hands.groupBy { it.stakeLabel }
            .map { (k, v) -> compute(v, k) }
            .sortedByDescending { it.hands }

    fun byPosition(hands: List<ParsedHand>): List<Stats> {
        val order = listOf("UTG", "UTG1", "UTG2", "MP", "MP1", "CO", "BTN", "BTN/SB", "SB", "BB")
        return hands.groupBy { it.heroPosition }
            .map { (k, v) -> compute(v, k) }
            .sortedBy { order.indexOf(it.label).let { i -> if (i < 0) 99 else i } }
    }

    fun byDay(hands: List<ParsedHand>): List<Stats> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return hands.groupBy { f.format(java.util.Date(it.timestampMillis)) }
            .map { (k, v) -> compute(v, k) }
            .sortedBy { it.label }
    }

    /** Накопительный график результата в bb (для графика в приложении). */
    fun equityCurveBb(hands: List<ParsedHand>): List<Double> {
        var acc = 0.0
        return hands.sortedBy { it.timestampMillis }.map { acc += it.heroNetBb; round2(acc) }
    }

    private fun pct(part: Int, total: Int): Double =
        if (total == 0) 0.0 else round2(part * 100.0 / total)

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}

object CsvExport {
    private const val HEADER =
        "hand_id,date_utc,game,stake,rush_and_cash,currency,sb,bb,table,seats,players,hero_position," +
            "hero_cards,board,streets_seen,invested,collected,net,net_bb,total_pot,rake,fees," +
            "vpip,pfr,three_bet,saw_flop,showdown,won,all_in,cashed_out"

    fun toCsv(hands: List<ParsedHand>): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder(HEADER).append('\n')
        for (h in hands.sortedBy { it.timestampMillis }) {
            sb.append(q(h.handId)).append(',')
                .append(q(f.format(java.util.Date(h.timestampMillis)))).append(',')
                .append(q(h.gameCode.name)).append(',')
                .append(q(h.stakeLabel)).append(',')
                .append(h.isRushAndCash).append(',')
                .append(q(h.currency)).append(',')
                .append(h.smallBlind).append(',')
                .append(h.bigBlind).append(',')
                .append(q(h.tableName)).append(',')
                .append(h.maxSeats).append(',')
                .append(h.playerCount).append(',')
                .append(q(h.heroPosition)).append(',')
                .append(q(h.heroCards.joinToString(" "))).append(',')
                .append(q(h.board.joinToString(" "))).append(',')
                .append(h.streetsSeen).append(',')
                .append(h.heroInvested).append(',')
                .append(h.heroCollected).append(',')
                .append(h.heroNet).append(',')
                .append(round2(h.heroNetBb)).append(',')
                .append(h.totalPot).append(',')
                .append(h.rake).append(',')
                .append(h.jackpotFees).append(',')
                .append(h.heroVpip).append(',')
                .append(h.heroPfr).append(',')
                .append(h.heroThreeBet).append(',')
                .append(h.heroSawFlop).append(',')
                .append(h.heroWentToShowdown).append(',')
                .append(h.heroWonHand).append(',')
                .append(h.heroAllIn).append(',')
                .append(h.cashedOut).append('\n')
        }
        return sb.toString()
    }

    private fun q(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
