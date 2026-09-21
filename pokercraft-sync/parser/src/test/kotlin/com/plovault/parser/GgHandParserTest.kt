package com.plovault.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgHandParserTest {

    private val plo4Rc = """
Poker Hand #RC2814873042: Omaha Pot Limit ($0.05/$0.10) - 2024/03/12 15:04:05
Table 'RushAndCash1234567' 6-max Seat #1 is the button
Seat 1: 1a2b3c4d ($10.45 in chips)
Seat 2: Hero ($10.00 in chips)
Seat 3: 5e6f7g8h ($12.30 in chips)
Seat 4: 9i0j1k2l ($8.20 in chips)
Seat 5: 3m4n5o6p ($10.00 in chips)
Seat 6: 7q8r9s0t ($15.00 in chips)
Hero: posts small blind $0.05
5e6f7g8h: posts big blind $0.10
*** HOLE CARDS ***
Dealt to Hero [Ac Kd 7h 2s]
9i0j1k2l: folds
3m4n5o6p: raises $0.25 to $0.35
7q8r9s0t: folds
1a2b3c4d: folds
Hero: raises $0.85 to $1.20
5e6f7g8h: folds
3m4n5o6p: calls ${'$'}0.85
*** FLOP *** [Ah 7d 2c]
Hero: bets $2.00
3m4n5o6p: calls ${'$'}2.00
*** TURN *** [Ah 7d 2c] [9s]
Hero: checks
3m4n5o6p: checks
*** RIVER *** [Ah 7d 2c 9s] [Kh]
Hero: bets $3.00
3m4n5o6p: folds
Uncalled bet ($3.00) returned to Hero
Hero collected $6.25 from pot
*** SUMMARY ***
Total pot $6.40 | Rake $0.15 | Jackpot $0 | Bingo $0 | Fortune $0 | Tax $0
Board [Ah 7d 2c 9s Kh]
Seat 1: 1a2b3c4d (button) folded before Flop (didn't bet)
Seat 2: Hero (small blind) collected ($6.25)
Seat 5: 3m4n5o6p folded on the River
""".trimIndent()

    private val plo4Loss = """
Poker Hand #RC2814873099: Omaha Pot Limit ($0.25/$0.50) - 2024/03/12 16:10:00
Table 'RushAndCash7654321' 6-max Seat #3 is the button
Seat 1: aaa11111 ($50.00 in chips)
Seat 2: bbb22222 ($60.00 in chips)
Seat 3: ccc33333 ($55.00 in chips)
Seat 4: Hero ($50.00 in chips)
Seat 5: ddd44444 ($48.00 in chips)
Seat 6: eee55555 ($70.00 in chips)
Hero: posts small blind $0.25
ddd44444: posts big blind $0.50
*** HOLE CARDS ***
Dealt to Hero [As Ks Qd Jd]
eee55555: folds
aaa11111: folds
bbb22222: folds
ccc33333: raises $1.25 to $1.75
Hero: calls ${'$'}1.50
ddd44444: folds
*** FLOP *** [2h 7c 9d]
Hero: checks
ccc33333: bets $2.00
Hero: folds
Uncalled bet ($2.00) returned to ccc33333
ccc33333 collected $3.75 from pot
*** SUMMARY ***
Total pot $4.00 | Rake $0.25 | Jackpot $0 | Bingo $0 | Fortune $0 | Tax $0
Board [2h 7c 9d]
Seat 4: Hero (small blind) folded on the Flop
""".trimIndent()

    private val plo5 = """
Poker Hand #RC2814880000: Omaha Pot Limit (5 Cards) ($0.10/$0.25) - 2024/03/13 10:00:00
Table 'RushAndCash999' 6-max Seat #2 is the button
Seat 1: zzz11111 ($25.00 in chips)
Seat 2: Hero ($25.00 in chips)
Seat 3: yyy22222 ($25.00 in chips)
Hero: posts small blind $0.10
yyy22222: posts big blind $0.25
*** HOLE CARDS ***
Dealt to Hero [Ac Kd 7h 2s 9c]
zzz11111: folds
Hero: folds
yyy22222 collected $0.20 from pot
*** SUMMARY ***
Total pot $0.20 | Rake $0 | Jackpot $0
Seat 2: Hero (button) folded before Flop
""".trimIndent()

    private val nlhCash = """
Poker Hand #HD9988776655: Hold'em No Limit ($0.05/$0.10) - 2024/03/14 11:00:00
Table 'NLHGold42' 6-max Seat #4 is the button
Seat 1: qqq11111 ($10.00 in chips)
Seat 4: Hero ($10.00 in chips)
Seat 5: www22222 ($10.00 in chips)
qqq11111: posts small blind $0.05
www22222: posts big blind $0.10
*** HOLE CARDS ***
Dealt to Hero [Ac Kd]
Hero: raises $0.20 to $0.30
qqq11111: folds
www22222: folds
Uncalled bet ($0.20) returned to Hero
Hero collected $0.25 from pot
*** SUMMARY ***
Total pot $0.20 | Rake $0 | Jackpot $0
Seat 4: Hero (button) collected ($0.25)
""".trimIndent()

    @Test
    fun parsesRushAndCashPlo4Win() {
        val h = GgHandParser.parseHand(plo4Rc)!!
        assertEquals("RC2814873042", h.handId)
        assertEquals(GameCode.PLO4, h.gameCode)
        assertTrue(h.isRushAndCash)
        assertEquals(0.05, h.smallBlind, 0.0001)
        assertEquals(0.10, h.bigBlind, 0.0001)
        assertEquals(6, h.playerCount)
        assertEquals("SB", h.heroPosition)
        assertEquals(listOf("Ac", "Kd", "7h", "2s"), h.heroCards)
        assertEquals(3, h.streetsSeen)
        assertEquals(3.20, h.heroInvested, 0.001)
        assertEquals(6.25, h.heroCollected, 0.001)
        assertEquals(3.05, h.heroNet, 0.001)
        assertEquals(30.5, h.heroNetBb, 0.01)
        assertEquals(0.15, h.rake, 0.001)
        assertTrue(h.heroVpip)
        assertTrue(h.heroPfr)
        assertTrue(h.heroThreeBet)
        assertTrue(h.heroSawFlop)
        assertTrue(h.heroWonHand)
        assertFalse(h.heroWentToShowdown)
        assertEquals(5, h.board.size)
        assertEquals("PLO4 RC $0.05/$0.10", h.stakeLabel)
    }

    @Test
    fun parsesLossWithFold() {
        val h = GgHandParser.parseHand(plo4Loss)!!
        assertEquals(GameCode.PLO4, h.gameCode)
        assertEquals("SB", h.heroPosition)
        assertEquals(1.75, h.heroInvested, 0.001)
        assertEquals(0.0, h.heroCollected, 0.001)
        assertEquals(-1.75, h.heroNet, 0.001)
        assertEquals(-3.5, h.heroNetBb, 0.01)
        assertTrue(h.heroVpip)
        assertFalse(h.heroPfr)
        assertTrue(h.heroSawFlop)
        assertFalse(h.heroWonHand)
    }

    @Test
    fun detectsFiveCardOmahaByHoleCards() {
        val h = GgHandParser.parseHand(plo5)!!
        assertEquals(GameCode.PLO5, h.gameCode)
        assertEquals(-0.10, h.heroNet, 0.001)
    }

    @Test
    fun detectsHoldem() {
        val h = GgHandParser.parseHand(nlhCash)!!
        assertEquals(GameCode.NLH, h.gameCode)
        assertFalse(h.isRushAndCash)
        assertEquals(0.15, h.heroNet, 0.001)
    }

    @Test
    fun splitsMultiHandFileAndFiltersPlo4Rush() {
        val file = listOf(plo4Rc, plo4Loss, plo5, nlhCash).joinToString("\n\n")
        val res = GgHandParser.parseFile(file)
        assertEquals(4, res.hands.size)
        assertEquals(0, res.failedBlocks)

        val filter = HandFilter(games = setOf(GameCode.PLO4), rushAndCashOnly = true)
        val plo4 = res.hands.filter { filter.matches(it) }
        assertEquals(2, plo4.size)

        val stats = StatsEngine.compute(plo4)
        assertEquals(2, stats.hands)
        assertEquals(1.30, stats.netCash, 0.001)
        assertEquals(27.0, stats.netBb, 0.01)
        assertEquals(1350.0, stats.bbPer100, 0.01)
        assertEquals(100.0, stats.vpip, 0.01)
        assertEquals(50.0, stats.pfr, 0.01)

        val byStake = StatsEngine.byStake(plo4)
        assertEquals(2, byStake.size)
        val curve = StatsEngine.equityCurveBb(plo4)
        assertEquals(2, curve.size)
        assertEquals(27.0, curve.last(), 0.01)
    }

    @Test
    fun positionsAreAssignedFromButton() {
        val pos = GgHandParser.assignPositions(listOf(1, 2, 3, 4, 5, 6), 1)
        assertEquals("SB", pos[2])
        assertEquals("BB", pos[3])
        assertEquals("UTG", pos[4])
        assertEquals("MP", pos[5])
        assertEquals("CO", pos[6])
        assertEquals("BTN", pos[1])
    }

    @Test
    fun csvContainsAllHands() {
        val res = GgHandParser.parseFile(listOf(plo4Rc, plo4Loss).joinToString("\n\n"))
        val csv = CsvExport.toCsv(res.hands)
        val lines = csv.trim().split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("hand_id,"))
        assertTrue(csv.contains("RC2814873042"))
    }

    @Test
    fun handlesCashOut() {
        val cashOut = """
Poker Hand #RC2814881111: Omaha Pot Limit ($0.05/$0.10) - 2024/03/15 12:00:00
Table 'RushAndCash555' 6-max Seat #1 is the button
Seat 1: aaa ($10.00 in chips)
Seat 2: Hero ($10.00 in chips)
Hero: posts small blind $0.05
aaa: posts big blind $0.10
*** HOLE CARDS ***
Dealt to Hero [Ac Ad Kc Kd]
Hero: raises $0.25 to $0.30
aaa: calls ${'$'}0.20
*** FLOP *** [Ah 7d 2c]
Hero: bets $0.60 and is all-in
aaa: calls ${'$'}0.60
Hero: Cashed Out the hand for $1.70 | Cash Out Fee $0.08
*** SUMMARY ***
Total pot $1.80 | Rake $0.09 | Jackpot $0
Board [Ah 7d 2c 9s Kh]
""".trimIndent()
        val h = GgHandParser.parseHand(cashOut)!!
        assertTrue(h.cashedOut)
        assertTrue(h.heroAllIn)
        assertEquals(0.90, h.heroInvested, 0.001)
        assertEquals(1.62, h.heroCollected, 0.001)
        assertEquals(0.72, h.heroNet, 0.001)
    }
}
