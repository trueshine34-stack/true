package com.polybot.btc5m.bot

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The withdrawal builds a real transaction, so its encoding is checked against
 * vectors worked out independently rather than against itself.
 *
 * The dangerous mistakes here are silent ones: a leading zero byte on an
 * integer, a mis-scaled amount, a recipient in the wrong half of the word.
 * None of those fail loudly — they produce a transaction that is either
 * rejected after paying gas, or valid and wrong.
 */
class WalletTest {

    private val usdc = "0x2791bca1f2de4661ed88a30c99a7a9449aa84174"
    private val to = "0x89C1DFaBfD22c5fF16158eD7d0A23d2cEa0177C3"

    @Test
    fun rlpEncodesTheCanonicalVectors() {
        assertEquals("83646f67", hex(Wallet.Rlp.bytes("dog".toByteArray())))
        assertEquals("c0", hex(Wallet.Rlp.list(emptyList())))
        assertEquals("80", hex(Wallet.Rlp.int(BigInteger.ZERO)))
        assertEquals("0f", hex(Wallet.Rlp.int(BigInteger.valueOf(15))))
        assertEquals("820400", hex(Wallet.Rlp.int(BigInteger.valueOf(1024))))
    }

    @Test
    fun anIntegerNeverCarriesALeadingZero() {
        // BigInteger.toByteArray sign-pads anything with the high bit set, and
        // that pad makes an encoding no node accepts.
        assertEquals("81ff", hex(Wallet.Rlp.int(BigInteger.valueOf(255))))
        assertEquals("8506fc23ac00", hex(Wallet.Rlp.int(BigInteger.valueOf(30_000_000_000L))))
    }

    @Test
    fun theTransferCallIsEncodedForTheRightRecipient() {
        val data = "a9059cbb" + Wallet.word(to) + Wallet.word(BigInteger.valueOf(12_340_000L))
        assertEquals(
            "a9059cbb" +
                "00000000000000000000000089c1dfabfd22c5ff16158ed7d0a23d2cea0177c3" +
                "0000000000000000000000000000000000000000000000000000000000bc4b20",
            data,
        )
    }

    @Test
    fun dollarsBecomeSixDecimalUnits() {
        assertEquals(BigInteger.valueOf(12_340_000L), Wallet.toUnits(12.34, 6))
        // Never round a withdrawal up: the extra units are not there to send.
        assertEquals(BigInteger.valueOf(1_234_567L), Wallet.toUnits(1.2345678, 6))
        assertEquals(12.34, Wallet.fromUnits(BigInteger.valueOf(12_340_000L), 6), 1e-9)
    }

    @Test
    fun theUnsignedTransactionMatchesAnIndependentEncoding() {
        val data = "a9059cbb" + Wallet.word(to) + Wallet.word(BigInteger.valueOf(12_340_000L))
        val tx = Wallet.Tx(
            chainId = 137L,
            nonce = BigInteger.valueOf(7),
            maxPriorityFeePerGas = BigInteger.valueOf(30_000_000_000L),
            maxFeePerGas = BigInteger.valueOf(100_000_000_000L),
            gasLimit = BigInteger.valueOf(120_000L),
            to = usdc,
            value = BigInteger.ZERO,
            data = Wallet.hexToBytes(data),
        )

        assertEquals(
            "02f8708189078506fc23ac0085174876e8008301d4c0942791bca1f2de4661ed88a3" +
                "0c99a7a9449aa8417480b844a9059cbb0000000000000000000000008" +
                "9c1dfabfd22c5ff16158ed7d0a23d2cea0177c3000000000000000000" +
                "0000000000000000000000000000000000000000bc4b20c0",
            hex(tx.unsigned()),
        )
    }

    @Test
    fun theSignedTransactionKeepsTheTypeAndGrowsBySignature() {
        val keys = Secp256k1.keyPairFromPrivateKey(
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318",
        )
        val tx = Wallet.Tx(
            chainId = 137L,
            nonce = BigInteger.ONE,
            maxPriorityFeePerGas = BigInteger.valueOf(30_000_000_000L),
            maxFeePerGas = BigInteger.valueOf(100_000_000_000L),
            gasLimit = BigInteger.valueOf(120_000L),
            to = usdc,
            value = BigInteger.ZERO,
            data = Wallet.hexToBytes("a9059cbb" + Wallet.word(to) + Wallet.word(BigInteger.TEN)),
        )
        val signed = tx.sign(keys)

        assertEquals(0x02.toByte(), signed[0])
        // Parity, r and s, and nothing else.
        val added = signed.size - tx.unsigned().size
        assert(added in 66..68) { "signature added $added bytes" }
        // Deterministic: the same transaction signs to the same bytes.
        assertEquals(hex(signed), hex(tx.sign(keys)))
    }

    private fun hex(bytes: ByteArray) = Secp256k1.toHex(bytes)
}
