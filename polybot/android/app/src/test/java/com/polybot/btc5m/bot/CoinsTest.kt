package com.polybot.btc5m.bot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinsTest {

    @After
    fun back() {
        Coins.select("btc")
    }

    @Test
    fun theSlugIsTheCoinAndTheWindow() {
        assertEquals("btc-updown-5m-", Coins.BTC.slugPrefix)
        assertEquals("eth-updown-5m-", Coins.ETH.slugPrefix)
        assertEquals("sol-updown-5m-", Coins.SOL.slugPrefix)
    }

    @Test
    fun binanceIsNamedTwoWays() {
        assertEquals("ethusdt", Coins.ETH.stream)
        assertEquals("ETHUSDT", Coins.ETH.pair)
    }

    @Test
    fun anUnknownCoinIsBitcoin() {
        assertEquals(Coins.BTC, Coins.of(null))
        assertEquals(Coins.BTC, Coins.of("doge"))
        assertEquals(Coins.SOL, Coins.of("SOL"))
    }

    @Test
    fun selectingSaysWhetherAnythingMoved() {
        assertTrue(Coins.select("sol"))
        assertEquals(Coins.SOL, Coins.current)
        // The same coin again is not a switch: the desk must not tear its
        // sockets down to arrive where it already is.
        assertFalse(Coins.select("sol"))
        assertTrue(Coins.select("btc"))
    }

    @Test
    fun aPriceIsPrintedAsFinelyAsItMoves() {
        assertEquals(0, Coins.BTC.digits)
        assertEquals(2, Coins.SOL.digits)
    }
}

/**
 * A dollar of edge is a different thing on a coin that costs a hundred
 * dollars, and the rule has one dollar figure for all three.
 */
class EdgeScaleTest {

    private fun read(lead: Double, price: Double) = PulsePlan.Read(
        elapsedSec = 120,
        lead = lead,
        momentum = 1.0,
        volume = 1.0,
        lean = 0.8,
        upAsk = 0.60,
        downAsk = 0.40,
        ceiling = 0.90,
        cashUsd = 100.0,
        price = price,
    )

    @Test
    fun theSettingIsBitcoinAtAHundredThousand() {
        assertEquals(6.0, PulsePlan.edgeFor(6.0, 100_000.0), 1e-9)
        // And the same move, in that coin's own dollars.
        assertEquals(0.18, PulsePlan.edgeFor(6.0, 3_000.0), 1e-9)
        assertEquals(0.006, PulsePlan.edgeFor(6.0, 100.0), 1e-9)
    }

    @Test
    fun anUnknownPriceLeavesTheSettingAlone() {
        assertEquals(6.0, PulsePlan.edgeFor(6.0, 0.0), 1e-9)
    }

    @Test
    fun aCheapCoinCanStillWinItsWindow() {
        val settings = PulsePlan.Settings(enabled = true)
        // A cent of lead on a hundred-dollar coin is the same move as six
        // dollars on bitcoin — which used to read as no edge at all, so the
        // rule never entered a single window there.
        assertEquals(null, PulsePlan.blockedBecause(read(0.01, 100.0), settings, holding = false))
        assertEquals(
            "Up",
            PulsePlan.leader(0.01, PulsePlan.edgeFor(settings.minEdge, 100.0)),
        )
        // And a tenth of that is still nothing.
        assertTrue(
            PulsePlan.blockedBecause(read(0.0005, 100.0), settings, holding = false)
                ?.startsWith("нет перевеса") == true,
        )
    }
}
