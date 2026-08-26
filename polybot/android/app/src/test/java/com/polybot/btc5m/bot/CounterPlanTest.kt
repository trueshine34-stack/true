package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the counter bot fades the desk by. */
class CounterPlanTest {

    private val on = CounterPlan.Settings(enabled = true)
    private val tick = 0.01

    private fun blocked(
        ask: Double? = 0.25,
        elapsed: Long = 30,
        buys: Int = 0,
        lastEntry: Double? = null,
        cash: Double = 5.0,
        settings: CounterPlan.Settings = on,
    ) = CounterPlan.blockedBecause(ask, elapsed, buys, lastEntry, tick, cash, settings)

    @Test
    fun aCheapOppositeSideEarlyInTheWindowIsTaken() {
        assertNull(blocked())
    }

    @Test
    fun nothingHappensWhileItIsSwitchedOff() {
        assertEquals("выключен", blocked(settings = CounterPlan.Settings()))
    }

    @Test
    fun thirtyCentsIsTooDear() {
        // Under thirty, not at it.
        assertNull(blocked(ask = 0.29))
        assertNotNull(blocked(ask = 0.30))
        assertNotNull(blocked(ask = 0.31))
    }

    @Test
    fun afterTwoMinutesItStopsBuying() {
        assertNull(blocked(elapsed = 119))
        assertEquals("первые 2 минуты прошли", blocked(elapsed = 120))
        assertEquals("первые 2 минуты прошли", blocked(elapsed = 240))
    }

    @Test
    fun threeClipsIsAll() {
        assertNull(blocked(buys = 2, lastEntry = 0.26))
        assertEquals("взял свои 3", blocked(buys = 3, lastEntry = 0.26))
    }

    /**
     * The whole point of the second and third clips is that they are cheaper.
     * Without this it buys the same price three times in six seconds.
     */
    @Test
    fun eachFurtherClipWaitsForAnotherTickDown() {
        assertEquals("ждёт тик ниже", blocked(ask = 0.25, buys = 1, lastEntry = 0.25))
        assertEquals("ждёт тик ниже", blocked(ask = 0.26, buys = 1, lastEntry = 0.25))
        assertNull(blocked(ask = 0.24, buys = 1, lastEntry = 0.25))
        assertNull(blocked(ask = 0.20, buys = 1, lastEntry = 0.25))
    }

    @Test
    fun itWillNotSpendMoneyItDoesNotHave() {
        assertEquals("нет денег в контейнере", blocked(cash = 0.5))
    }

    @Test
    fun withoutAPriceThereIsNothingToDecide() {
        assertEquals("нет цены", blocked(ask = null))
        assertEquals("нет цены", blocked(ask = 0.0))
    }

    /**
     * A dollar at a quarter is four shares, and the venue takes nothing under
     * five — so the clip costs more than a dollar, and the size has to say so
     * rather than be rounded back under the floor.
     */
    @Test
    fun theClipIsRaisedToTheVenuesFloor() {
        assertEquals(5.0, CounterPlan.clipShares(0.25, 5.0, on), 1e-9)
        assertEquals(1.25, CounterPlan.clipCost(0.25, 5.0, on), 1e-9)

        // At ten cents the dollar floor bites before the share floor does.
        assertEquals(10.0, CounterPlan.clipShares(0.10, 5.0, on), 1e-9)
    }

    /** Above the floor the clip is simply the dollar it was asked for. */
    @Test
    fun aDearerClipIsJustTheMoney() {
        val big = on.copy(clipUsd = 4.0)
        assertEquals(16.0, CounterPlan.clipShares(0.25, 5.0, big), 1e-9)
        assertEquals(4.0, CounterPlan.clipCost(0.25, 5.0, big), 1e-9)
    }

    /** It is buying a dip that is still moving; resting at the ask misses it. */
    @Test
    fun theEntryCrossesTheOffer() {
        assertEquals(0.27, CounterPlan.crossPrice(0.25, tick), 1e-9)
        assertEquals(0.99, CounterPlan.crossPrice(0.99, tick), 1e-9)
    }

    /** A quarter up on what it paid, and the fee comes out of the sale. */
    @Test
    fun theExitAsksAQuarterNetOfTheFee() {
        val exit = CounterPlan.exitPrice(0.25, tick, on)
        assertTrue("$exit", exit > 0.3125)
        assertEquals(0.25 * 1.25, SellPercent.netSell(exit), 0.01)
    }

    @Test
    fun itBuysTheSideTheDeskIsNotOn() {
        assertEquals("Down", CounterPlan.opposite("Up"))
        assertEquals("Up", CounterPlan.opposite("Down"))
    }
}
