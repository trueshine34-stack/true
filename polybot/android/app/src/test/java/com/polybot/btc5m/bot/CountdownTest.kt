package com.polybot.btc5m.bot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clock speaks once at twenty-five seconds and twice at ten — and each of
 * them once per window, whatever the tick rate.
 */
class CountdownTest {

    private val heard = ArrayList<String>()

    @After
    fun quiet() {
        Countdown.set(false)
    }

    private fun run(seconds: List<Long>): List<String> {
        heard.clear()
        Countdown.listener = { kind -> heard.add(kind) }
        Countdown.set(true)
        seconds.forEach { Countdown.tick(it) }
        Countdown.listener = null
        return heard.toList()
    }

    @Test
    fun `one cue at twenty-five, two at ten`() {
        assertEquals(25L, Countdown.FIRST_SEC)
        assertEquals(10L, Countdown.SECOND_SEC)
        // A window that opens at 3000 closes at 3300.
        assertEquals(
            listOf("tick", "tick2"),
            run(listOf(3_200L, 3_275L, 3_280L, 3_291L, 3_299L)),
        )
    }

    @Test
    fun `each mark sounds once however often it is asked`() {
        // Half-second ticks land on the same second twice.
        assertEquals(
            listOf("tick", "tick2"),
            run(listOf(3_275L, 3_275L, 3_276L, 3_290L, 3_290L, 3_295L)),
        )
    }

    @Test
    fun `the next window is a new pair of cues`() {
        assertEquals(
            listOf("tick", "tick2", "tick", "tick2"),
            run(listOf(3_280L, 3_295L, 3_580L, 3_595L)),
        )
    }

    @Test
    fun `switched off it says nothing`() {
        heard.clear()
        Countdown.listener = { kind -> heard.add(kind) }
        Countdown.set(false)
        listOf(3_280L, 3_295L).forEach { Countdown.tick(it) }
        Countdown.listener = null
        assertEquals(emptyList<String>(), heard)
    }
}
