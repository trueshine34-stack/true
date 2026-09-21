package com.plovault.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Парсер текстовых hand history GGPoker (выгрузка PokerCraft).
 *
 * Формат толерантный: неизвестные строки игнорируются, а тип игры определяется
 * и по заголовку, и по количеству карманных карт героя — поэтому PLO4 отличается
 * от PLO5/PLO6 даже если GG поменяет подпись игры.
 */
object GgHandParser {

    private const val HERO_DEFAULT = "Hero"

    private val HEADER = Regex(
        """Poker Hand #(\S+?):\s+(.*?)\s*\(([^)]*?)([\d.,]+)\s*/\s*[^\d]*([\d.,]+)\)\s*-\s*(\d{4}/\d{2}/\d{2}\s+\d{1,2}:\d{2}:\d{2})"""
    )
    private val TABLE = Regex("""Table\s+'([^']+)'\s+(\d+)-max\s+Seat #(\d+) is the button""")
    private val SEAT = Regex("""Seat (\d+): (.+?) \(([^\d]*)([\d.,]+) in chips\)""")
    private val DEALT = Regex("""Dealt to (.+?) \[([^\]]+)\]""")
    private val POST_SB = Regex("""^(.+?): posts small blind [^\d]*([\d.,]+)""")
    private val POST_BB = Regex("""^(.+?): posts big blind [^\d]*([\d.,]+)""")
    private val POST_ANTE = Regex("""^(.+?): posts (?:the )?ante [^\d]*([\d.,]+)""")
    private val POST_STRADDLE = Regex("""^(.+?): (?:posts straddle|straddles)[^\d]*([\d.,]+)""")
    private val BET = Regex("""^(.+?): bets [^\d]*([\d.,]+)""")
    private val CALL = Regex("""^(.+?): calls [^\d]*([\d.,]+)""")
    private val RAISE = Regex("""^(.+?): raises [^\d]*([\d.,]+) to [^\d]*([\d.,]+)""")
    private val FOLD = Regex("""^(.+?): folds""")
    private val CHECK = Regex("""^(.+?): checks""")
    private val UNCALLED = Regex("""Uncalled bet \([^\d]*([\d.,]+)\) returned to (.+)$""")
    private val COLLECTED = Regex("""^(.+?) collected [^\d]*([\d.,]+) from(?: the)? pot""")
    private val CASHOUT = Regex(
        """^(.+?):?\s*(?:cashed out|Cashed Out) the hand for [^\d]*([\d.,]+)(?:\s*\|\s*Cash Out Fee [^\d]*([\d.,]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val BOARD = Regex("""Board \[([^\]]+)\]""")
    private val TOTAL_POT = Regex("""Total pot [^\d]*([\d.,]+)""")
    private val RAKE = Regex("""Rake [^\d]*([\d.,]+)""")
    private val EXTRA_FEE = Regex("""(Jackpot|Bingo|Fortune|Tax)\s*[^\d]*([\d.,]+)""")
    private val SUMMARY_WON = Regex("""Seat \d+: (.+?)\s(?:\(.*?\)\s)?(?:showed|mucked|collected|won).*?won \(?[^\d]*([\d.,]+)""")
    private val SHOWED = Regex("""^Seat \d+: (.+?)\s(?:\(.+?\)\s)?showed \[""")
    private val ALLIN = Regex("""^(.+?): .*and is all-in""")

    private val STREET_FLOP = Regex("""^\*\*\* (?:FIRST |SECOND |THIRD )?FLOP \*\*\*""")
    private val STREET_TURN = Regex("""^\*\*\* (?:FIRST |SECOND |THIRD )?TURN \*\*\*""")
    private val STREET_RIVER = Regex("""^\*\*\* (?:FIRST |SECOND |THIRD )?RIVER \*\*\*""")

    /** Разбирает файл выгрузки (может содержать много раздач). */
    fun parseFile(text: String, heroNameHint: String? = null): ParseResult {
        val blocks = splitHands(text)
        val hands = ArrayList<ParsedHand>(blocks.size)
        val errors = ArrayList<String>()
        var failed = 0
        for (b in blocks) {
            try {
                val h = parseHand(b, heroNameHint)
                if (h != null) hands.add(h) else failed++
            } catch (e: Exception) {
                failed++
                if (errors.size < 20) errors.add(e.message ?: e.toString())
            }
        }
        return ParseResult(hands, failed, errors)
    }

    /** Режет текст на блоки раздач по заголовкам "Poker Hand #". */
    fun splitHands(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in normalized.split('\n')) {
            if (line.startsWith("Poker Hand #") || line.startsWith("GG Poker Hand #")) {
                if (sb.isNotBlank()) out.add(sb.toString().trim())
                sb.setLength(0)
            }
            sb.append(line).append('\n')
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.startsWith("Poker Hand #") || it.startsWith("GG Poker Hand #") }
    }

    fun parseHand(block: String, heroNameHint: String? = null): ParsedHand? {
        val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val header = HEADER.find(lines[0]) ?: return null

        val handId = header.groupValues[1]
        val gameLabel = header.groupValues[2].trim()
        val currencyRaw = header.groupValues[3].trim()
        val currency = when {
            currencyRaw.contains('$') -> "$"
            currencyRaw.contains('€') -> "€"
            currencyRaw.contains('£') -> "£"
            currencyRaw.contains('¥') -> "¥"
            currencyRaw.isNotEmpty() -> currencyRaw
            else -> "$"
        }
        val sb0 = num(header.groupValues[4])
        val bb0 = num(header.groupValues[5])
        val dateText = header.groupValues[6]
        val ts = parseDate(dateText)

        var tableName = ""
        var maxSeats = 0
        var buttonSeat = 0
        TABLE.find(block)?.let {
            tableName = it.groupValues[1]
            maxSeats = it.groupValues[2].toIntOrNull() ?: 0
            buttonSeat = it.groupValues[3].toIntOrNull() ?: 0
        }

        val isRc = handId.startsWith("RC", ignoreCase = true) ||
            tableName.contains("RushAndCash", ignoreCase = true) ||
            tableName.contains("Rush", ignoreCase = true)

        // --- места за столом ---
        data class RawSeat(val seat: Int, val name: String, val stack: Double)
        val rawSeats = ArrayList<RawSeat>()
        for (l in lines) {
            val m = SEAT.find(l) ?: continue
            rawSeats.add(RawSeat(m.groupValues[1].toInt(), m.groupValues[2].trim(), num(m.groupValues[4])))
        }
        if (rawSeats.isEmpty()) return null

        // --- герой и его карты ---
        var heroName = heroNameHint ?: HERO_DEFAULT
        var heroCards: List<String> = emptyList()
        for (l in lines) {
            val m = DEALT.find(l) ?: continue
            val who = m.groupValues[1].trim()
            val cards = m.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            // строка с реальными картами (не "[...]" скрытых) — это герой
            if (cards.size >= 2 && cards.none { it.contains('*') }) {
                heroName = who
                heroCards = cards
                break
            }
        }
        val heroSeatRow = rawSeats.firstOrNull { it.name == heroName }
        val heroSeat = heroSeatRow?.seat ?: -1

        val gameCode = detectGame(gameLabel, heroCards.size)

        // --- позиции ---
        val positions = assignPositions(rawSeats.map { it.seat }, buttonSeat)
        val players = rawSeats.map {
            PlayerSeat(it.seat, it.name, it.stack, it.name == heroName, positions[it.seat] ?: "?")
        }
        val heroPosition = positions[heroSeat] ?: "?"

        // --- проход по улицам: вложения героя и статистика ---
        var street = 0 // 0 preflop, 1 flop, 2 turn, 3 river
        var streetsSeen = 0
        var heroStreetCommit = 0.0
        var heroInvested = 0.0
        var heroVpip = false
        var heroPfr = false
        var heroThreeBet = false
        var heroFacedThreeBet = false
        var heroAllIn = false
        var heroSawFlop = false
        var preflopRaiseCount = 0
        var heroPreflopActed = false
        var heroCollected = 0.0
        var cashedOut = false
        var cashOutNet = 0.0
        var ante = 0.0
        var inSummary = false
        var heroFolded = false
        var heroShowed = false
        var heroWon = false

        fun closeStreet() {
            heroInvested += heroStreetCommit
            heroStreetCommit = 0.0
        }

        for (l in lines) {
            when {
                l.startsWith("*** HOLE CARDS ***") -> { street = 0 }
                STREET_FLOP.containsMatchIn(l) -> { closeStreet(); street = 1; streetsSeen = maxOf(streetsSeen, 1) }
                STREET_TURN.containsMatchIn(l) -> { closeStreet(); street = 2; streetsSeen = maxOf(streetsSeen, 2) }
                STREET_RIVER.containsMatchIn(l) -> { closeStreet(); street = 3; streetsSeen = maxOf(streetsSeen, 3) }
                l.startsWith("*** SHOW DOWN ***") || l.startsWith("*** SHOWDOWN ***") -> closeStreet()
                l.startsWith("*** SUMMARY ***") -> { closeStreet(); inSummary = true }
            }
            if (inSummary) {
                SHOWED.find(l)?.let { if (it.groupValues[1].trim() == heroName) heroShowed = true }
                SUMMARY_WON.find(l)?.let { if (it.groupValues[1].trim() == heroName) heroWon = true }
                continue
            }

            POST_ANTE.find(l)?.let {
                val amt = num(it.groupValues[2])
                if (it.groupValues[1].trim() == heroName) heroInvested += amt
                ante = maxOf(ante, amt)
                return@let
            }
            POST_SB.find(l)?.let {
                if (it.groupValues[1].trim() == heroName) heroStreetCommit += num(it.groupValues[2])
            }
            POST_BB.find(l)?.let {
                if (it.groupValues[1].trim() == heroName) heroStreetCommit += num(it.groupValues[2])
            }
            POST_STRADDLE.find(l)?.let {
                if (it.groupValues[1].trim() == heroName) heroStreetCommit += num(it.groupValues[2])
            }

            val isHeroLine = l.startsWith("$heroName:")

            RAISE.find(l)?.let { m ->
                val who = m.groupValues[1].trim()
                val to = num(m.groupValues[3])
                if (street == 0) {
                    if (who == heroName) {
                        if (preflopRaiseCount >= 2) heroFacedThreeBet = true
                        heroVpip = true
                        heroPfr = true
                        if (preflopRaiseCount == 1) heroThreeBet = true
                        heroPreflopActed = true
                    }
                    preflopRaiseCount++
                }
                if (who == heroName) heroStreetCommit = to
            }
            CALL.find(l)?.let { m ->
                val who = m.groupValues[1].trim()
                val amt = num(m.groupValues[2])
                if (who == heroName) {
                    if (street == 0) {
                        heroVpip = true
                        heroPreflopActed = true
                        if (preflopRaiseCount >= 2) heroFacedThreeBet = true
                    }
                    heroStreetCommit += amt
                }
            }
            BET.find(l)?.let { m ->
                if (m.groupValues[1].trim() == heroName) heroStreetCommit += num(m.groupValues[2])
            }
            FOLD.find(l)?.let { m ->
                if (m.groupValues[1].trim() == heroName) {
                    heroFolded = true
                    if (street == 0) {
                        heroPreflopActed = true
                        if (preflopRaiseCount >= 2) heroFacedThreeBet = true
                    }
                }
            }
            CHECK.find(l)?.let { m ->
                if (m.groupValues[1].trim() == heroName && street == 0) heroPreflopActed = true
            }
            if (isHeroLine) ALLIN.find(l)?.let { if (it.groupValues[1].trim() == heroName) heroAllIn = true }

            UNCALLED.find(l)?.let {
                if (it.groupValues[2].trim() == heroName) heroStreetCommit -= num(it.groupValues[1])
            }
            COLLECTED.find(l)?.let {
                if (it.groupValues[1].trim() == heroName) heroCollected += num(it.groupValues[2])
            }
            CASHOUT.find(l)?.let {
                if (it.groupValues[1].trim() == heroName) {
                    cashedOut = true
                    val amt = num(it.groupValues[2])
                    val fee = it.groupValues.getOrNull(3)?.takeIf { s -> s.isNotBlank() }?.let { s -> num(s) } ?: 0.0
                    cashOutNet += amt - fee
                }
            }
        }
        closeStreet()

        // Cash Out заменяет выигрыш банка: учитываем его, только если банк не собран.
        val collectedTotal = if (cashedOut && heroCollected == 0.0) cashOutNet else heroCollected
        if (collectedTotal > 0.0) heroWon = true
        heroSawFlop = streetsSeen >= 1 && !foldedBeforeFlop(lines, heroName)
        val heroWentToShowdown = (block.contains("*** SHOW DOWN ***") || block.contains("*** SHOWDOWN ***")) &&
            (heroShowed || (!heroFolded && heroCards.isNotEmpty() && streetsSeen >= 3))

        // --- итоги банка ---
        val summaryPart = block.substringAfter("*** SUMMARY ***", "")
        val totalPot = TOTAL_POT.find(summaryPart)?.let { num(it.groupValues[1]) } ?: 0.0
        val rake = RAKE.find(summaryPart)?.let { num(it.groupValues[1]) } ?: 0.0
        var extras = 0.0
        EXTRA_FEE.findAll(summaryPart).forEach { extras += num(it.groupValues[2]) }
        val board = BOARD.find(summaryPart)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

        val net = round2(collectedTotal - heroInvested)

        return ParsedHand(
            handId = handId,
            site = "GGPoker",
            gameLabel = gameLabel,
            gameCode = gameCode,
            isRushAndCash = isRc,
            currency = currency,
            smallBlind = sb0,
            bigBlind = bb0,
            ante = ante,
            timestampMillis = ts,
            dateText = dateText,
            tableName = tableName,
            maxSeats = if (maxSeats > 0) maxSeats else rawSeats.size,
            buttonSeat = buttonSeat,
            playerCount = rawSeats.size,
            players = players,
            heroName = heroName,
            heroSeat = heroSeat,
            heroCards = heroCards,
            heroPosition = heroPosition,
            board = board,
            streetsSeen = streetsSeen,
            heroInvested = round2(heroInvested),
            heroCollected = round2(collectedTotal),
            heroNet = net,
            totalPot = totalPot,
            rake = rake,
            jackpotFees = round2(extras),
            heroVpip = heroVpip,
            heroPfr = heroPfr,
            heroThreeBet = heroThreeBet,
            heroFacedThreeBet = heroFacedThreeBet,
            heroSawFlop = heroSawFlop,
            heroWentToShowdown = heroWentToShowdown,
            heroWonHand = heroWon,
            heroAllIn = heroAllIn,
            cashedOut = cashedOut,
            cashOutNet = round2(cashOutNet),
            raw = block
        )
    }

    private fun foldedBeforeFlop(lines: List<String>, hero: String): Boolean {
        for (l in lines) {
            if (STREET_FLOP.containsMatchIn(l)) return false
            if (l.startsWith("$hero:") && l.contains(": folds")) return true
        }
        return true
    }

    private fun detectGame(label: String, heroCardCount: Int): GameCode {
        val low = label.lowercase(Locale.ROOT)
        val omaha = low.contains("omaha") || low.contains("plo")
        return when {
            omaha && heroCardCount == 5 -> GameCode.PLO5
            omaha && heroCardCount == 6 -> GameCode.PLO6
            omaha && heroCardCount == 4 -> GameCode.PLO4
            omaha && low.contains("5 card") -> GameCode.PLO5
            omaha && low.contains("6 card") -> GameCode.PLO6
            omaha -> GameCode.PLO4
            low.contains("hold'em") || low.contains("holdem") -> GameCode.NLH
            else -> GameCode.OTHER
        }
    }

    /** Раздаёт позиции: от кнопки против часовой (SB, BB, UTG...). */
    fun assignPositions(seats: List<Int>, buttonSeat: Int): Map<Int, String> {
        val sorted = seats.sorted()
        if (sorted.isEmpty()) return emptyMap()
        val btnIndex = sorted.indexOf(buttonSeat).let { if (it >= 0) it else 0 }
        val n = sorted.size
        // порядок от SB по часовой стрелке
        val order = (1..n).map { sorted[(btnIndex + it) % n] }
        val labels: List<String> = when (n) {
            2 -> listOf("BB", "BTN")
            3 -> listOf("SB", "BB", "BTN")
            4 -> listOf("SB", "BB", "CO", "BTN")
            5 -> listOf("SB", "BB", "UTG", "CO", "BTN")
            6 -> listOf("SB", "BB", "UTG", "MP", "CO", "BTN")
            7 -> listOf("SB", "BB", "UTG", "UTG1", "MP", "CO", "BTN")
            8 -> listOf("SB", "BB", "UTG", "UTG1", "MP", "MP1", "CO", "BTN")
            else -> listOf("SB", "BB", "UTG", "UTG1", "UTG2", "MP", "MP1", "CO", "BTN")
        }
        val res = HashMap<Int, String>()
        for (i in order.indices) {
            val label = if (i < labels.size) labels[i] else "MP"
            res[order[i]] = label
        }
        if (n == 2) { res[sorted[btnIndex]] = "BTN/SB" }
        return res
    }

    private fun num(s: String): Double =
        s.replace(",", "").replace(" ", "").toDoubleOrNull() ?: 0.0

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun parseDate(text: String): Long = try {
        val f = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(text.trim())?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}
