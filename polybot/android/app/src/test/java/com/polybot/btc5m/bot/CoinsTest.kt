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

