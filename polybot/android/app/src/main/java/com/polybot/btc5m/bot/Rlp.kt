package com.polybot.btc5m.bot

import java.math.BigInteger

/**
 * Recursive-length prefix, the encoding Ethereum transactions are made of.
 *
 * Two rules and no schema: a byte string is itself when it is a single byte
 * under 0x80, and otherwise carries a length prefix; a list carries the length
 * of its encoded contents. Numbers are big-endian with no leading zeros, and
 * zero is the empty string — which is the detail that silently breaks a
 * transaction rather than failing to encode one.
 */
object Rlp {

    fun bytes(value: ByteArray): ByteArray = when {
        value.size == 1 && (value[0].toInt() and 0xFF) < 0x80 -> value
        value.size <= 55 -> byteArrayOf((0x80 + value.size).toByte()) + value
        else -> {
            val length = minimal(BigInteger.valueOf(value.size.toLong()))
            byteArrayOf((0xB7 + length.size).toByte()) + length + value
        }
    }

    fun list(items: List<ByteArray>): ByteArray {
        val payload = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return when {
            payload.size <= 55 -> byteArrayOf((0xC0 + payload.size).toByte()) + payload
            else -> {
                val length = minimal(BigInteger.valueOf(payload.size.toLong()))
                byteArrayOf((0xF7 + length.size).toByte()) + length + payload
            }
        }
    }

    /** A number as RLP wants it: shortest big-endian, and nothing at all for zero. */
    fun number(value: BigInteger): ByteArray = bytes(minimal(value))

    fun number(value: Long): ByteArray = number(BigInteger.valueOf(value))

    /** The bare big-endian bytes, without the RLP prefix. */
    fun minimal(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "rlp has no negatives" }
        if (value.signum() == 0) return ByteArray(0)
        val raw = value.toByteArray()
        var from = 0
        while (from < raw.size - 1 && raw[from] == 0.toByte()) from++
        return raw.copyOfRange(from, raw.size)
    }

    /** `0x…` to bytes, tolerating the prefix being absent and odd lengths. */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").let { if (it.length % 2 == 1) "0$it" else it }
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    fun toHex(bytes: ByteArray): String =
        "0x" + bytes.joinToString("") { "%02x".format(it) }
}
