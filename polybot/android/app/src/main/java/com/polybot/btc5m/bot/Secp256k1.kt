package com.polybot.btc5m.bot

import java.math.BigInteger
import java.util.Arrays
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve

/**
 * Ethereum-flavoured secp256k1, on BouncyCastle alone.
 *
 * web3j would cover this, but its Android artifacts ship without transitive
 * dependencies and its JVM ones drag in Jackson, Tuweni and a native KZG
 * library for features this app never touches. The signature format is pinned
 * by unit tests against Polymarket's own client, so the narrower dependency
 * costs no confidence.
 */
object Secp256k1 {

    private val PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    private val CURVE = ECDomainParameters(PARAMS.curve, PARAMS.g, PARAMS.n, PARAMS.h)
    private val HALF_CURVE_ORDER: BigInteger = PARAMS.n.shiftRight(1)

    fun keccak256(input: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(input, 0, input.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    class KeyPair(val privateKey: BigInteger, val publicKey: BigInteger) {
        /** Lowercase 0x-prefixed address, as the CLOB expects to see it. */
        val address: String by lazy {
            val hash = keccak256(toFixed(publicKey, 64))
            "0x" + toHex(Arrays.copyOfRange(hash, 12, 32))
        }
    }

    fun keyPairFromPrivateKey(privateKeyHex: String): KeyPair {
        val clean = privateKeyHex.removePrefix("0x")
        require(clean.length == 64) { "private key must be 32 bytes" }
        val priv = BigInteger(clean, 16)
        val point = PARAMS.g.multiply(priv).normalize()
        val encoded = point.getEncoded(false)
        // Drop the 0x04 uncompressed prefix; Ethereum hashes the bare X||Y.
        val pub = BigInteger(1, Arrays.copyOfRange(encoded, 1, encoded.size))
        return KeyPair(priv, pub)
    }

    class Signature(val r: ByteArray, val s: ByteArray, val v: Byte)

    /**
     * Deterministic (RFC 6979) signature over an already-hashed 32-byte digest,
     * with the low-s normalisation and recovery id Ethereum verifiers expect.
     */
    fun sign(digest: ByteArray, keyPair: KeyPair): Signature {
        require(digest.size == 32) { "digest must be 32 bytes" }

        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(keyPair.privateKey, CURVE))
        val components = signer.generateSignature(digest)

        val r = components[0]
        // A signature with high s is malleable; every Ethereum tool canonicalises.
        val s = if (components[1] > HALF_CURVE_ORDER) CURVE.n.subtract(components[1]) else components[1]

        var recId = -1
        for (candidate in 0..3) {
            val recovered = recoverPublicKey(candidate, r, s, digest)
            if (recovered != null && recovered == keyPair.publicKey) {
                recId = candidate
                break
            }
        }
        check(recId >= 0) { "could not construct a recoverable signature" }

        return Signature(toFixed(r, 32), toFixed(s, 32), (recId + 27).toByte())
    }

    private fun recoverPublicKey(
        recId: Int,
        r: BigInteger,
        s: BigInteger,
        message: ByteArray,
    ): BigInteger? {
        val n = CURVE.n
        val i = BigInteger.valueOf(recId.toLong() / 2)
        val x = r.add(i.multiply(n))

        val prime = SecP256K1Curve.q
        if (x >= prime) return null

        val R = decompressKey(x, (recId and 1) == 1)
        if (!R.multiply(n).isInfinity) return null

        val e = BigInteger(1, message)
        val eInv = BigInteger.ZERO.subtract(e).mod(n)
        val rInv = r.modInverse(n)
        val srInv = rInv.multiply(s).mod(n)
        val eInvrInv = rInv.multiply(eInv).mod(n)

        val q = ECAlgorithms.sumOfTwoMultiplies(CURVE.g, eInvrInv, R, srInv).normalize()
        if (q.isInfinity) return null

        val encoded = q.getEncoded(false)
        return BigInteger(1, Arrays.copyOfRange(encoded, 1, encoded.size))
    }

    private fun decompressKey(xBN: BigInteger, yBit: Boolean): ECPoint {
        val converter = X9IntegerConverter()
        val compEnc = converter.integerToBytes(xBN, 1 + converter.getByteLength(CURVE.curve))
        compEnc[0] = (if (yBit) 0x03 else 0x02).toByte()
        return CURVE.curve.decodePoint(compEnc)
    }

    fun toFixed(value: BigInteger, length: Int): ByteArray {
        val out = ByteArray(length)
        val bytes = value.toByteArray()
        // BigInteger.toByteArray can prepend a sign byte; strip it when padding.
        val start = if (bytes.size > length) bytes.size - length else 0
        val len = minOf(bytes.size - start, length)
        System.arraycopy(bytes, start, out, length - len, len)
        return out
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
