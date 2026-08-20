package com.polybot.btc5m.bot

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the native signing path to Polymarket's own client.
 *
 * A mismatch here means the exchange would reject every order the service
 * sends, so these run on the JVM as part of the normal build rather than being
 * discovered on a live market.
 */
class SigningTest {

    private val keyPair = Secp256k1.keyPairFromPrivateKey(ReferenceVectors.PRIVATE_KEY)

    @Test
    fun `derives the reference address`() {
        assertEquals(ReferenceVectors.ADDRESS.lowercase(), keyPair.address)
    }

    @Test
    fun `order digests match the reference client`() {
        for (v in ReferenceVectors.ORDERS) {
            val struct = Signing.OrderStruct(
                salt = BigInteger(v.salt),
                maker = v.maker,
                signer = ReferenceVectors.ADDRESS,
                tokenId = BigInteger(v.tokenId),
                makerAmount = BigInteger(v.makerAmount),
                takerAmount = BigInteger(v.takerAmount),
                side = if (v.side == "BUY") 0 else 1,
                signatureType = v.signatureType,
                timestamp = BigInteger.valueOf(v.timestampMs),
            )
            val digest = Signing.orderDigest(struct, v.verifyingContract, Contracts.CHAIN_ID)
            assertEquals(
                "digest for salt ${v.salt}",
                v.digest.lowercase(),
                "0x" + Secp256k1.toHex(digest),
            )
        }
    }

    @Test
    fun `order signatures match the reference client`() {
        for (v in ReferenceVectors.ORDERS) {
            val struct = Signing.OrderStruct(
                salt = BigInteger(v.salt),
                maker = v.maker,
                signer = ReferenceVectors.ADDRESS,
                tokenId = BigInteger(v.tokenId),
                makerAmount = BigInteger(v.makerAmount),
                takerAmount = BigInteger(v.takerAmount),
                side = if (v.side == "BUY") 0 else 1,
                signatureType = v.signatureType,
                timestamp = BigInteger.valueOf(v.timestampMs),
            )
            assertEquals(
                "signature for salt ${v.salt}",
                v.signature.lowercase(),
                Signing.signOrder(struct, v.verifyingContract, Contracts.CHAIN_ID, keyPair)
                    .lowercase(),
            )
        }
    }

    @Test
    fun `l1 auth signatures match the reference client`() {
        for (v in ReferenceVectors.AUTHS) {
            assertEquals(
                "auth for ts ${v.timestamp} nonce ${v.nonce}",
                v.signature.lowercase(),
                Signing.clobAuthSignature(keyPair, v.timestamp, v.nonce.toLong())
                    .lowercase(),
            )
        }
    }

    @Test
    fun `l2 hmac matches the reference client`() {
        for (v in ReferenceVectors.HMACS) {
            val message = v.timestamp + v.method + v.path + v.body
            assertEquals(
                "hmac for ${v.method} ${v.path}",
                v.expected,
                Signing.hmacSignature(ReferenceVectors.SECRET, message),
            )
        }
    }

    @Test
    fun `amount rounding matches the reference client`() {
        val cfg = Orders.roundConfigFor(0.01)
        for (v in ReferenceVectors.AMOUNTS) {
            val amounts = when (v.kind) {
                "market_buy" -> Orders.marketOrderAmounts("BUY", v.amount, v.price, cfg)
                "limit_buy" -> Orders.limitOrderAmounts("BUY", v.amount, v.price, cfg)
                "limit_sell" -> Orders.limitOrderAmounts("SELL", v.amount, v.price, cfg)
                else -> error("unknown vector kind ${v.kind}")
            }
            val label = "${v.kind} ${v.amount} @ ${v.price}"
            assertEquals("$label maker", v.maker, amounts.makerAmount.toString())
            assertEquals("$label taker", v.taker, amounts.takerAmount.toString())
        }
    }

    @Test
    fun `exit ladder picks the rung the clock calls for`() {
        val ladder = DEFAULT_EXIT_LADDER
        val cases = listOf(
            0L to 0.89,
            20L to 0.89,
            179L to 0.89,
            180L to 0.93,
            239L to 0.93,
            240L to 0.96,
            269L to 0.96,
            270L to 0.99,
            299L to 0.99,
            600L to 0.99,
        )
        for ((elapsed, expected) in cases) {
            assertEquals(
                "price at ${elapsed}s",
                expected,
                Strategy.exitPriceFor(ladder, elapsed),
                1e-9,
            )
        }
    }

    @Test
    fun `exit ladder tolerates an unsorted or empty list`() {
        val shuffled = DEFAULT_EXIT_LADDER.reversed()
        assertEquals(0.93, Strategy.exitPriceFor(shuffled, 200), 1e-9)
        // A rung that starts later than the first tick still covers the start,
        // so a position opened early always has a sell to rest.
        assertEquals(0.5, Strategy.exitPriceFor(listOf(ExitStep(100, 0.5)), 0), 1e-9)
        assertEquals(0.99, Strategy.exitPriceFor(emptyList(), 0), 1e-9)
    }

    @Test
    fun `tick sizes map to the right rounding config`() {
        assertEquals(Orders.RoundConfig(2, 2, 4), Orders.roundConfigFor(0.01))
        assertEquals(Orders.RoundConfig(1, 2, 3), Orders.roundConfigFor(0.1))
        assertEquals(Orders.RoundConfig(4, 2, 6), Orders.roundConfigFor(0.0001))
    }
}
