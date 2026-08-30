package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePlanTest {

    private val on = ProbePlan.Settings(enabled = true)

    private val W = 1_788_060_600L

    @Test
    fun `aims at the next window through the lead before it opens`() {
        // Ten seconds left, and nine.
        assertEquals(W + 300, ProbePlan.targetWindow(W, 290, on))
        assertEquals(W + 300, ProbePlan.targetWindow(W, 299, on))
    }

    @Test
    fun `aims at the running window for the same lead after it opens`() {
        // The venue does not always publish the next market in time, and a
        // window entered two seconds late is still that window's bet.
        assertEquals(W, ProbePlan.targetWindow(W, 0, on))
        assertEquals(W, ProbePlan.targetWindow(W, 10, on))
    }

    @Test
    fun `aims at nothing through the middle of a window`() {
        assertNull(ProbePlan.targetWindow(W, 11, on))
        assertNull(ProbePlan.targetWindow(W, 150, on))
        assertNull(ProbePlan.targetWindow(W, 289, on))
    }

    @Test
    fun `a longer lead widens both chances`() {
        val early = ProbePlan.Settings(enabled = true, leadSec = 30)
        assertEquals(W + 300, ProbePlan.targetWindow(W, 270, early))
        assertEquals(W, ProbePlan.targetWindow(W, 30, early))
        assertNull(ProbePlan.targetWindow(W, 31, early))
    }

    @Test
    fun `says why it is standing aside`() {
        assertEquals("выключен", ProbePlan.blockedBecause("Up", 0.5, 100.0, ProbePlan.Settings()))
        assertEquals("нет свечей", ProbePlan.blockedBecause("", 0.5, 100.0, on))
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

    @Test
    fun `stands aside when the reversal is one window away`() {
        // A typical window travels sixty dollars, so the rule wants twenty-one
        // of room. Ten is not enough: this window arrives at the level with
        // most of itself left, and the direction is nearly spent.
        assertTrue(ProbePlan.tooClose(price = 100_000.0, level = 100_010.0, typical = 60.0))
        // Thirty away, and the window has somewhere to go first.
        assertTrue(!ProbePlan.tooClose(price = 100_000.0, level = 100_030.0, typical = 60.0))
    }

    @Test
    fun `measures the room the same either side of the price`() {
        assertTrue(ProbePlan.tooClose(price = 100_000.0, level = 99_990.0, typical = 60.0))
    }

    @Test
    fun `has no opinion without a level or a scale`() {
        assertTrue(!ProbePlan.tooClose(100_000.0, null, 60.0))
        assertTrue(!ProbePlan.tooClose(100_000.0, 100_010.0, 0.0))
        assertTrue(!ProbePlan.tooClose(0.0, 100_010.0, 60.0))
    }

    @Test
    fun `a zero share switches the check off`() {
        assertTrue(!ProbePlan.tooClose(100_000.0, 100_001.0, 60.0, share = 0.0))
    }

    @Test
    fun `says which price it is standing aside from`() {
        val why = ProbePlan.blockedBecause(
            way = "Up",
            ask = 0.5,
            cashUsd = 100.0,
            settings = on,
            price = 100_000.0,
            level = 100_010.0,
            typical = 60.0,
        )
        assertEquals("у разворота 100010", why)
    }

    @Test
    fun `room in front of the line is not in the way`() {
        assertNull(
            ProbePlan.blockedBecause(
                way = "Up",
                ask = 0.5,
                cashUsd = 100.0,
                settings = on,
                price = 100_000.0,
                level = 100_200.0,
                typical = 60.0,
            ),
        )
    }
}
