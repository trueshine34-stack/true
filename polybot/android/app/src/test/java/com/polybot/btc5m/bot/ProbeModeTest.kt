package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three entries keep separate records, and separating them is arithmetic
 * over a stamped list rather than three copies of the bot.
 */
class ProbeModeTest {

    private fun round(mode: String, demo: Boolean, pnl: Double) = ProbeBot.Round(
        windowStart = 0L,
        asset = "t",
        demo = demo,
        mode = mode,
        side = "Up",
        perHour = 0.0,
        shares = 5.0,
        price = 0.50,
        proceeds = 5.0 * 0.50 + pnl,
        winner = "Up",
    )

    private val all = listOf(
        round("line", demo = true, pnl = 2.0),
        round("fade", demo = true, pnl = -1.0),
        round("inside", demo = true, pnl = 0.5),
        round("line", demo = false, pnl = 4.0),
    )

    private fun won(demo: Boolean, mode: String) =
        all.filter { it.demo == demo && it.mode == mode && it.shares > 0.0 }.sumOf { it.pnl }

    @Test
    fun `each entry counts only its own rounds`() {
        assertEquals(2.0, won(demo = true, mode = "line"), 1e-9)
        assertEquals(-1.0, won(demo = true, mode = "fade"), 1e-9)
        assertEquals(0.5, won(demo = true, mode = "inside"), 1e-9)
    }

    /** And the accounts stay apart inside one entry, as they always did. */
    @Test
    fun `the wallet's line is not the paper line`() {
        assertEquals(4.0, won(demo = false, mode = "line"), 1e-9)
    }

    /**
     * A record filed before the entries were told apart was taken by the
     * line — that is what the desk shipped with and what the others were
     * split from — so it must read as the line's rather than as nobody's.
     */
    @Test
    fun `an unstamped round belongs to the line`() {
        val old = ProbeBot.Round(
            windowStart = 0L,
            asset = "t",
            demo = true,
            side = "Up",
            perHour = 0.0,
            shares = 5.0,
            price = 0.50,
        )
        assertEquals("line", old.mode)
    }
}
