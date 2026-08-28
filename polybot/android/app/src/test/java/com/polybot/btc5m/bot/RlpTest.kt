package com.polybot.btc5m.bot

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/** The encoding a transaction is made of, against the spec's own examples. */
class RlpTest {

    private fun hex(bytes: ByteArray) = Rlp.toHex(bytes)

    @Test
    fun aSingleSmallByteIsItself() {
        assertEquals("0x0f", hex(Rlp.bytes(byteArrayOf(0x0f))))
    }

    @Test
    fun shortStringsCarryALengthPrefix() {
        assertEquals("0x83646f67", hex(Rlp.bytes("dog".toByteArray())))
        assertEquals("0x80", hex(Rlp.bytes(ByteArray(0))))
    }

    @Test
    fun longStringsCarryTheLengthOfTheirLength() {
        val long = ByteArray(1024) { 'a'.code.toByte() }
        val encoded = hex(Rlp.bytes(long))
        assertEquals("0xb90400", encoded.substring(0, 8))
    }

    @Test
    fun listsWrapTheirContents() {
        assertEquals("0xc0", hex(Rlp.list(emptyList())))
        assertEquals(
            "0xc88363617483646f67",
            hex(Rlp.list(listOf(Rlp.bytes("cat".toByteArray()), Rlp.bytes("dog".toByteArray())))),
        )
    }

    @Test
    fun zeroIsNothingAtAll() {
        // The one that breaks a transaction rather than failing to encode: a
        // zero written as 0x00 is a different value to a verifier.
        assertEquals("0x80", hex(Rlp.number(0L)))
        assertEquals("0x", hex(Rlp.minimal(BigInteger.ZERO)))
    }

    @Test
    fun numbersAreShortestBigEndian() {
        assertEquals("0x01", hex(Rlp.number(1L)))
        assertEquals("0x7f", hex(Rlp.number(127L)))
        assertEquals("0x8180", hex(Rlp.number(128L)))
        assertEquals("0x820400", hex(Rlp.number(1024L)))
    }

    @Test
    fun hexRoundTrips() {
        assertEquals("0xdeadbeef", hex(Rlp.hexToBytes("0xdeadbeef")))
        assertEquals("0x0f", hex(Rlp.hexToBytes("f")))
        assertEquals("0x", hex(Rlp.hexToBytes("0x")))
    }
}
