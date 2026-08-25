package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a fill off the venue's response.
 *
 * The two amounts are the maker and taker assets of the order that was posted,
 * and which one is shares swaps with the side. Getting it wrong is silent: the
 * order goes through, the money moves, and only the app's own books are off —
 * by a factor of the price.
 */
class FilledTest {

    @Test
    fun aBuyTakesSharesAndGivesDollars() {
        // 5 shares at 56¢: 2.80 out, 5 in.
        val fill = Orders.filled("BUY", makingAmount = 2.80, takingAmount = 5.0)

        assertEquals(5.0, fill.shares, 1e-9)
        assertEquals(2.80, fill.usd, 1e-9)
    }

    @Test
    fun aSellGivesSharesAndTakesDollars() {
        // 5 shares at 99¢: 5 out, 4.95 in. Read the other way round this was
        // booked as a 4.95-share sale, leaving a phantom 0.05 for the sell rule
        // to chase and part of the round missing from the profit.
        val fill = Orders.filled("SELL", makingAmount = 5.0, takingAmount = 4.95)

        assertEquals(5.0, fill.shares, 1e-9)
        assertEquals(4.95, fill.usd, 1e-9)
    }

    @Test
    fun theErrorWouldHaveBeenWorstInTheMiddleOfTheBook() {
        // 5 shares at 50¢ read the wrong way is half a position.
        val fill = Orders.filled("SELL", makingAmount = 5.0, takingAmount = 2.50)

        assertEquals(5.0, fill.shares, 1e-9)
    }

    @Test
    fun anOrderThatRestedMatchedNothing() {
        val fill = Orders.filled("SELL", makingAmount = null, takingAmount = null)

        assertEquals(0.0, fill.shares, 1e-9)
        assertEquals(0.0, fill.usd, 1e-9)
    }
}
