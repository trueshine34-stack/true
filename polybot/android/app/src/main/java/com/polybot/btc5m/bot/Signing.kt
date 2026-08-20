package com.polybot.btc5m.bot

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing for the Polymarket CLOB.
 *
 * Ported from py-clob-client-v2 and pinned by unit tests against vectors that
 * the reference client produced, so a refactor here cannot silently start
 * emitting signatures the exchange rejects.
 */
object Signing {

    const val ORDER_TYPE_STRING =
        "Order(uint256 salt,address maker,address signer,uint256 tokenId," +
            "uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType," +
            "uint256 timestamp,bytes32 metadata,bytes32 builder)"

    private const val DOMAIN_TYPE_STRING =
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"

    private val ORDER_TYPE_HASH = Secp256k1.keccak256(ORDER_TYPE_STRING.toByteArray(StandardCharsets.UTF_8))
    private val DOMAIN_TYPE_HASH = Secp256k1.keccak256(DOMAIN_TYPE_STRING.toByteArray(StandardCharsets.UTF_8))

    /** Left-pads to a 32-byte ABI word. */
    private fun word(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val bytes = value.toByteArray()
        // BigInteger.toByteArray may prepend a sign byte; drop it for unsigned words.
        val start = if (bytes.size > 32) bytes.size - 32 else 0
        val len = minOf(bytes.size - start, 32)
        System.arraycopy(bytes, start, out, 32 - len, len)
        return out
    }

    private fun word(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        require(clean.length <= 64) { "value wider than one word: $hex" }
        return word(BigInteger(clean.ifEmpty { "0" }, 16))
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    fun domainSeparator(
        name: String,
        version: String,
        chainId: Long,
        verifyingContract: String,
    ): ByteArray = Secp256k1.keccak256(
        concat(
            DOMAIN_TYPE_HASH,
            Secp256k1.keccak256(name.toByteArray(StandardCharsets.UTF_8)),
            Secp256k1.keccak256(version.toByteArray(StandardCharsets.UTF_8)),
            word(BigInteger.valueOf(chainId)),
            word(verifyingContract),
        ),
    )

    data class OrderStruct(
        val salt: BigInteger,
        val maker: String,
        val signer: String,
        val tokenId: BigInteger,
        val makerAmount: BigInteger,
        val takerAmount: BigInteger,
        /** 0 = BUY, 1 = SELL. */
        val side: Int,
        val signatureType: Int,
        val timestamp: BigInteger,
        val metadata: String = Contracts.BYTES32_ZERO,
        val builder: String = Contracts.BYTES32_ZERO,
    )

    fun orderStructHash(order: OrderStruct): ByteArray = Secp256k1.keccak256(
        concat(
            ORDER_TYPE_HASH,
            word(order.salt),
            word(order.maker),
            word(order.signer),
            word(order.tokenId),
            word(order.makerAmount),
            word(order.takerAmount),
            word(BigInteger.valueOf(order.side.toLong())),
            word(BigInteger.valueOf(order.signatureType.toLong())),
            word(order.timestamp),
            word(order.metadata),
            word(order.builder),
        ),
    )

    /** The EIP-712 digest the exchange recovers the signer from. */
    fun orderDigest(order: OrderStruct, verifyingContract: String, chainId: Long): ByteArray =
        Secp256k1.keccak256(
            concat(
                byteArrayOf(0x19, 0x01),
                domainSeparator("Polymarket CTF Exchange", "2", chainId, verifyingContract),
                orderStructHash(order),
            ),
        )

    // L1 auth signs a domain with no verifyingContract, so it needs its own
    // type hash rather than the four-field one used for orders.
    private const val AUTH_DOMAIN_TYPE_STRING =
        "EIP712Domain(string name,string version,uint256 chainId)"

    private const val AUTH_TYPE_STRING =
        "ClobAuth(address address,string timestamp,uint256 nonce,string message)"

    const val AUTH_MESSAGE = "This message attests that I control the given wallet"

    private val AUTH_DOMAIN_TYPE_HASH =
        Secp256k1.keccak256(AUTH_DOMAIN_TYPE_STRING.toByteArray(StandardCharsets.UTF_8))
    private val AUTH_TYPE_HASH =
        Secp256k1.keccak256(AUTH_TYPE_STRING.toByteArray(StandardCharsets.UTF_8))

    /**
     * L1 signature, which is what mints the API credentials in the first place.
     */
    fun clobAuthSignature(
        keyPair: Secp256k1.KeyPair,
        timestampSec: Long,
        nonce: Long,
        chainId: Long = Contracts.CHAIN_ID,
    ): String {
        val domain = Secp256k1.keccak256(
            concat(
                AUTH_DOMAIN_TYPE_HASH,
                Secp256k1.keccak256("ClobAuthDomain".toByteArray(StandardCharsets.UTF_8)),
                Secp256k1.keccak256("1".toByteArray(StandardCharsets.UTF_8)),
                word(BigInteger.valueOf(chainId)),
            ),
        )
        val structHash = Secp256k1.keccak256(
            concat(
                AUTH_TYPE_HASH,
                word(keyPair.address),
                Secp256k1.keccak256(
                    timestampSec.toString().toByteArray(StandardCharsets.UTF_8),
                ),
                word(BigInteger.valueOf(nonce)),
                Secp256k1.keccak256(AUTH_MESSAGE.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        val digest = Secp256k1.keccak256(
            concat(byteArrayOf(0x19, 0x01), domain, structHash),
        )
        return signDigest(digest, keyPair)
    }

    /** 65-byte `r || s || v` signature, hex encoded. */
    fun signDigest(digest: ByteArray, keyPair: Secp256k1.KeyPair): String {
        val sig = Secp256k1.sign(digest, keyPair)
        return "0x" + Secp256k1.toHex(sig.r) + Secp256k1.toHex(sig.s) +
            String.format("%02x", sig.v)
    }

    fun signOrder(
        order: OrderStruct,
        verifyingContract: String,
        chainId: Long,
        keyPair: Secp256k1.KeyPair,
    ): String = signDigest(orderDigest(order, verifyingContract, chainId), keyPair)

    /**
     * L2 signature: HMAC-SHA256 over `timestamp + METHOD + path [+ body]`, keyed
     * by the base64url-decoded API secret, re-encoded base64url with padding.
     */
    fun hmacSignature(secret: String, message: String): String {
        val key = Base64.getUrlDecoder().decode(padBase64(secret))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().encodeToString(digest)
    }

    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }
}
