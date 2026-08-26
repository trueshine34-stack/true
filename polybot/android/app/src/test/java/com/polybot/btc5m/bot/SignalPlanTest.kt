package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules the indicator bot trades TradingView's read by. */
class SignalPlanTest {

    private val on = SignalPlan.Settings(enabled = true)
    private val tick = 0.01

    private fun gauges(summary: Double, ma: Double, osc: Double) =
        TradingView.Gauges(summary, ma, osc, close = 100_000.0, at = 0L)

    // ------------------------------------------------------- the three words

    @Test
    fun theGaugesReadAsTradingViewPrintsThem() {
        assertEquals("Strong Buy", SignalPlan.verdict(0.7))
        assertEquals("Buy", SignalPlan.verdict(0.2))
        assertEquals("Neutral", SignalPlan.verdict(0.0))
        assertEquals("Sell", SignalPlan.verdict(-0.2))
        assertEquals("Strong Sell", SignalPlan.verdict(-0.7))
    }

    @Test
    fun allThreeBuyingIsAnUpCall() {
        assertEquals("Up", SignalPlan.direction(gauges(0.6, 0.8, 0.15)))
        assertEquals("Down", SignalPlan.direction(gauges(-0.6, -0.8, -0.15)))
    }

    /** Unanimity is the whole filter; anything short of it is no signal. */
    @Test
    fun oneNeutralIsEnoughToSitOut() {
        assertNull(SignalPlan.direction(gauges(0.6, 0.8, 0.05)))
        assertNull(SignalPlan.direction(gauges(0.6, -0.8, 0.4)))
        assertNull(SignalPlan.direction(gauges(0.0, 0.0, 0.0)))
        assertNull(SignalPlan.direction(null))
    }

    @Test
    fun aMissingGaugeIsNotASignal() {
        assertNull(SignalPlan.direction(gauges(0.6, Double.NaN, 0.4)))
    }

    // --------------------------------------------------------- the entries

    private fun blocked(
        side: String? = "Up",
        ask: Double? = 0.40,
        elapsed: Long = 30,
        buys: Int = 0,
        lastEntry: Double? = null,
        cash: Double = 6.0,
        settings: SignalPlan.Settings = on,
    ) = SignalPlan.blockedBecause(side, ask, elapsed, buys, lastEntry, tick, cash, settings)

    @Test
    fun anAgreedSideAtAFairPriceIsBought() {
        assertNull(blocked())
    }

    @Test
    fun nothingHappensWithoutAgreement() {
        assertEquals("индикаторы не согласны", blocked(side = null))
    }

    @Test
    fun theFirstTenSecondsAreLeftAlone() {
        assertEquals("ждёт 10 с", blocked(elapsed = 0))
        assertEquals("ждёт 10 с", blocked(elapsed = 9))
        assertNull(blocked(elapsed = 10))
    }

    /** A clip taken in the last minute has no ladder left to climb. */
    @Test
    fun itStopsEnteringBeforeTheClose() {
        assertNull(blocked(elapsed = 239))
        assertEquals("поздно входить", blocked(elapsed = 240))
    }

    @Test
    fun sixtyCentsIsTheCeiling() {
        assertNull(blocked(ask = 0.60))
        assertEquals("дороже 60¢", blocked(ask = 0.61))
    }

    @Test
    fun threeClipsAndEachOneCheaper() {
        assertEquals("взял свои 3", blocked(buys = 3, lastEntry = 0.30))
        assertEquals("ждёт тик ниже", blocked(ask = 0.40, buys = 1, lastEntry = 0.40))
        assertNull(blocked(ask = 0.39, buys = 1, lastEntry = 0.40))
    }

    @Test
    fun itWillNotSpendMoneyItDoesNotHave() {
        assertEquals("нет денег в контейнере", blocked(cash = 0.2))
    }

    /** A dollar at forty cents is two and a half shares; the venue takes five. */
    @Test
    fun theClipIsRaisedToTheVenuesFloor() {
        assertEquals(5.0, SignalPlan.clipShares(0.40, 5.0, on), 1e-9)
        assertEquals(0.42, SignalPlan.crossPrice(0.40, tick), 1e-9)
    }
}
