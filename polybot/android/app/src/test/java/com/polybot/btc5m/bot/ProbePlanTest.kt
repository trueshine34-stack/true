package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePlanTest {

    private val on = ProbePlan.Settings(enabled = true)

    @Test
    fun `enters inside the lead and not before it`() {
        assertTrue(ProbePlan.due(10, on))
        assertTrue(ProbePlan.due(1, on))
        assertTrue(!ProbePlan.due(11, on))
        assertTrue(!ProbePlan.due(60, on))
    }

    @Test
    fun `does not enter on the boundary itself`() {
        // Zero seconds left is the next window, already open — the entry it
        // would place belongs to a window that has started without it.
        assertTrue(!ProbePlan.due(0, on))
        assertTrue(!ProbePlan.due(-3, on))
    }

    @Test
    fun `a longer lead moves the whole window forward`() {
        val early = ProbePlan.Settings(enabled = true, leadSec = 30)
        assertTrue(ProbePlan.due(30, early))
        assertTrue(!ProbePlan.due(31, early))
    }

    @Test
    fun `says why it is standing aside`() {
        assertEquals("выключен", ProbePlan.blockedBecause("Up", 0.5, 100.0, ProbePlan.Settings()))
        assertEquals("тренд вбок", ProbePlan.blockedBecause("", 0.5, 100.0, on))
        assertEquals("нет цены", ProbePlan.blockedBecause("Up", null, 100.0, on))
        assertEquals("нет цены", ProbePlan.blockedBecause("Up", 0.0, 100.0, on))
        assertEquals("контейнер пуст", ProbePlan.blockedBecause("Up", 0.5, 1.0, on))
    }

    @Test
    fun `will not chase a side that is already priced`() {
        val why = ProbePlan.blockedBecause("Up", 0.86, 100.0, on)
        assertEquals("дорого 86¢", why)
        // The ceiling itself is still allowed.
        assertNull(ProbePlan.blockedBecause("Up", ProbePlan.MAX_PRICE, 100.0, on))
    }

    @Test
    fun `nothing is in the way when the line points and the price is fair`() {
        assertNull(ProbePlan.blockedBecause("Down", 0.48, 20.0, on))
    }

    @Test
    fun `sizes the stake in shares at the price being paid`() {
        // Five dollars at fifty cents is ten shares.
        assertEquals(10.0, ProbePlan.shares(5.0, 0.50, 5.0), 1e-9)
        // And at twenty-five cents, twenty.
        assertEquals(20.0, ProbePlan.shares(5.0, 0.25, 5.0), 1e-9)
    }

    @Test
    fun `never sizes under the venue's floor`() {
        // Five dollars at eighty cents is 6.25 shares, which rounds to 6.3 —
        // above the floor. At a floor of ten it would be the floor instead.
        assertEquals(6.3, ProbePlan.shares(5.0, 0.80, 5.0), 1e-9)
        assertEquals(10.0, ProbePlan.shares(5.0, 0.80, 10.0), 1e-9)
    }

    @Test
    fun `crosses the spread by a tick and stops short of a dollar`() {
        assertEquals(0.51, ProbePlan.crossPrice(0.50, 0.01), 1e-9)
        assertEquals(0.99, ProbePlan.crossPrice(0.99, 0.01), 1e-9)
        assertEquals(0.999, ProbePlan.crossPrice(0.999, 0.001), 1e-9)
    }
}
