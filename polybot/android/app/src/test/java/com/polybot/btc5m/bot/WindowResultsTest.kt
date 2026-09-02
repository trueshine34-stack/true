package com.polybot.btc5m.bot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The answers survive a restart, and they survive it per coin.
 */
class WindowResultsTest {

    private val disk = HashMap<String, String>()

    private val store = object : WindowResults.Store {
        override fun read(key: String): String? = disk[key]
        override fun write(key: String, value: String) {
            disk[key] = value
        }
    }

    @After
    fun clean() {
        WindowResults.store = null
        Coins.select("btc")
        WindowResults.forget()
    }

    @Test
    fun `what was written comes back`() {
        WindowResults.store = store
        disk["results.btc"] = "1000:Up,1300:Down"
        WindowResults.reload(nowSec = 2_000)

        assertEquals("Up", WindowResults.of(1_000))
        assertEquals("Down", WindowResults.of(1_300))
        assertEquals("", WindowResults.of(1_600))
    }

    @Test
    fun `anything older than a day is dropped on the way in`() {
        WindowResults.store = store
        val now = 10L * 24 * 3600
        val old = now - (WindowResults.KEEP_HOURS + 1) * 3600
        val fresh = now - 3600
        disk["results.btc"] = "$old:Up,$fresh:Down"
        WindowResults.reload(nowSec = now)

        assertEquals("", WindowResults.of(old))
        assertEquals("Down", WindowResults.of(fresh))
    }

    @Test
    fun `each coin reads its own`() {
        WindowResults.store = store
        // A window from ten minutes ago, because a switch reads the disk
        // against the clock and a day is what it keeps.
        val window = Clock.nowSec() - 600
        disk["results.btc"] = "$window:Up"
        disk["results.sol"] = "$window:Down"

        Coins.select("btc")
        WindowResults.forget()
        assertEquals("Up", WindowResults.of(window))

        // Switching is what calls this, and the same window on another coin
        // is a different question with a different answer.
        Coins.select("sol")
        WindowResults.forget()
        assertEquals("Down", WindowResults.of(window))
    }

    @Test
    fun `nothing stored is nothing known, not a crash`() {
        WindowResults.store = null
        WindowResults.reload(nowSec = 2_000)
        assertEquals("", WindowResults.of(1_000))
    }
}
