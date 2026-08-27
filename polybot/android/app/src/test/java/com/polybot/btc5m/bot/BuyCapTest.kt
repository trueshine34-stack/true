package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuyCapTest {

    @Test
    fun firstMinuteStopsAtFiftyFour() {
        assertEquals(0.54, BuyCap.ceiling(0), 1e-9)
        assertEquals(0.54, BuyCap.ceiling(59), 1e-9)
    }

    @Test
    fun secondAndThirdMinutesStopAtSeventySeven() {
        assertEquals(0.77, BuyCap.ceiling(60), 1e-9)
        assertEquals(0.77, BuyCap.ceiling(179), 1e-9)
    }

    @Test
    fun theFourthMinuteIsOpen() {
        assertEquals(1.0, BuyCap.ceiling(180), 1e-9)
        assertEquals(1.0, BuyCap.ceiling(239), 1e-9)
    }

    @Test
    fun theLastMinuteClosesAtNinetyOne() {
        assertEquals(0.91, BuyCap.ceiling(240), 1e-9)
        assertEquals(0.91, BuyCap.ceiling(299), 1e-9)
        assertTrue(BuyCap.blocked(0.92, 260))
        assertFalse(BuyCap.blocked(0.91, 260))
    }

    @Test
    fun theCeilingItselfIsAllowed() {
        assertFalse(BuyCap.blocked(0.54, 30))
        assertTrue(BuyCap.blocked(0.55, 30))
        assertFalse(BuyCap.blocked(0.77, 120))
        assertTrue(BuyCap.blocked(0.78, 120))
        assertFalse(BuyCap.blocked(0.95, 200))
    }

    @Test
    fun aMarketWithNoWindowIsTreatedAsItsFirstSecond() {
        // Not knowing when the window opened is not a reason to lift a limit.
        assertEquals(0L, BuyCap.elapsedFor(0L, now = 1_787_817_600))
        assertTrue(BuyCap.blocked(0.6, BuyCap.elapsedFor(0L, now = 1_787_817_600)))
    }

    @Test
    fun elapsedIsMeasuredFromTheMarketsOwnWindow() {
        val start = 1_787_817_600L
        assertEquals(90L, BuyCap.elapsedFor(start, now = start + 90))
        // An order into a window that has not opened yet is at its start.
        assertEquals(-30L, BuyCap.elapsedFor(start, now = start - 30))
        assertEquals(0.54, BuyCap.ceiling(BuyCap.elapsedFor(start, now = start - 30)), 1e-9)
    }

    @Test
    fun theRefusalSaysWhichRuleAndWhatItAllows() {
        assertEquals("В первую минуту не покупаем дороже 54¢", BuyCap.reason(10))
        assertEquals("В первые 3 минуты не покупаем дороже 77¢", BuyCap.reason(100))
        assertEquals("В последнюю минуту не покупаем дороже 91¢", BuyCap.reason(250))
    }
}
