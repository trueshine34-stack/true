package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule the ladder bot buys the favourite by. */
class LadderPlanTest {

    private val on = LadderPlan.Settings(enabled = true)

    // ------------------------------------------------------ when it looks

    /**
     * Fifteen seconds before each minute ends — which is exactly when the sell
     * ladder steps, so the rung being compared against is the one the position
     * will actually be offered at.
     */
    @Test
    fun theCheckComesFifteenSecondsBeforeEachMinute() {
        assertEquals(0, LadderPlan.slotFor(105, on))
        assertEquals(1, LadderPlan.slotFor(165, on))
        assertEquals(2, LadderPlan.slotFor(225, on))
        assertEquals(3, LadderPlan.slotFor(285, on))
    }

    /**
     * The first minute is sat out. A window that has just opened has not
     * picked a side, and the first rung is the cheapest exit the ladder ever
     * offers — the worst one to buy into.
     */
    @Test
    fun theFirstMinuteIsSkipped() {
        assertEquals(-1, LadderPlan.slotFor(0, on))
        assertEquals(-1, LadderPlan.slotFor(45, on))
        assertEquals(-1, LadderPlan.slotFor(104, on))
    }

    /** Past the last check there is no ladder left to climb. */
    @Test
    fun itStopsLookingBeforeTheClose() {
        assertEquals(-1, LadderPlan.slotFor(286, on))
        assertEquals(-1, LadderPlan.slotFor(299, on))
    }

    /**
     * A clip that has gone through frees its money and its attention. Waiting
     * out the rest of the minute for a condition that is true now is waiting
     * for nothing; the pause is only long enough to keep one moment from being
     * bought twice.
     */
    @Test
    fun afterAClipItMayBuyAgainWithoutWaitingOutTheMinute() {
        assertTrue(LadderPlan.readyAfter(120, sinceLastBuyMs = 4_000, settings = on))
        assertFalse(LadderPlan.readyAfter(120, sinceLastBuyMs = 3_999, settings = on))
    }

    @Test
    fun andNeverOutsideTheEntryBand() {
        assertFalse(LadderPlan.readyAfter(104, sinceLastBuyMs = 60_000, settings = on))
        assertFalse(LadderPlan.readyAfter(286, sinceLastBuyMs = 60_000, settings = on))
        assertTrue(LadderPlan.readyAfter(285, sinceLastBuyMs = 60_000, settings = on))
    }

    // ------------------------------------------------------ which side

    @Test
    fun theDearerSideIsTheOneTheMarketPicked() {
        assertEquals("Up", LadderPlan.leadingSide(0.62, 0.39))
        assertEquals("Down", LadderPlan.leadingSide(0.39, 0.62))
    }

    /** Dead level is the market picking nothing, and so does this. */
    @Test
    fun aLevelBookIsNoSignal() {
        assertNull(LadderPlan.leadingSide(0.5, 0.5))
        assertNull(LadderPlan.leadingSide(null, null))
    }

    @Test
    fun oneSideMissingFromTheBookLeavesTheOther() {
        assertEquals("Down", LadderPlan.leadingSide(null, 0.4))
        assertEquals("Up", LadderPlan.leadingSide(0.4, null))
    }

    // ------------------------------------------------------ whether it buys

    private fun blocked(
        side: String? = "Up",
        ask: Double? = 0.78,
        rung: Double = 0.84,
        elapsed: Long = 105,
        cash: Double = 5.0,
        settings: LadderPlan.Settings = on,
    ) = LadderPlan.blockedBecause(side, ask, rung, elapsed, cash, settings)

    @Test
    fun aFavouriteUnderItsOwnExitIsBought() {
        assertNull(blocked())
    }

    @Test
    fun itWillNotPayMoreThanTheRungItWouldSellAt() {
        assertEquals("дороже ступени 84¢", blocked(ask = 0.84))
        assertEquals("дороже ступени 84¢", blocked(ask = 0.9))
    }

    /**
     * A cent under the rung is not a profit: the fee comes out of the sale, and
     * a trade that only looks positive is the worst kind to take.
     */
    @Test
    fun aGapTooSmallToPayTheFeeIsRefused() {
        // net(0.84) = 0.84 - 0.07 * 0.84 * 0.16 = 0.8306
        assertEquals("не покрывает комиссию", blocked(ask = 0.8306))
        assertEquals("не покрывает комиссию", blocked(ask = 0.835))
        assertNull(blocked(ask = 0.82))
    }

    @Test
    fun itWillNotSpendMoneyItDoesNotHave() {
        // Five shares at 78c is $3.90 — exactly enough, and a cent short.
        assertNull(blocked(cash = 3.90))
        assertEquals("нет денег в контейнере", blocked(cash = 3.89))
    }

    @Test
    fun nothingHappensWhileItIsOffOrTheBookIsLevel() {
        assertEquals("выключен", blocked(settings = LadderPlan.Settings()))
        assertEquals("стороны вровень", blocked(side = null))
        assertEquals("нет цены", blocked(ask = null))
    }

    @Test
    fun theClipCrossesTheOffer() {
        assertEquals(0.80, LadderPlan.crossPrice(0.78, 0.01), 1e-9)
        assertEquals(0.99, LadderPlan.crossPrice(0.99, 0.01), 1e-9)
    }

    /**
     * The rung compared against is the one the ladder is stepping to, because
     * the check happens inside the ladder's own fifteen-second lead.
     */
    @Test
    fun theRungAtEachCheckIsTheNextOne() {
        val rungs = SellLadder.DEFAULT
        assertEquals(0.84, SellLadder.priceFor(45, null, rungs), 1e-9)
        assertEquals(0.89, SellLadder.priceFor(105, null, rungs), 1e-9)
        assertEquals(0.93, SellLadder.priceFor(165, null, rungs), 1e-9)
        assertEquals(0.97, SellLadder.priceFor(225, null, rungs), 1e-9)
    }
}
