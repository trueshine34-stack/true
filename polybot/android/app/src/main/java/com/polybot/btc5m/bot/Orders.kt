package com.polybot.btc5m.bot

import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Order amount arithmetic, ported field for field from py-clob-client-v2.
 *
 * The exchange rejects an order whose amounts do not round exactly the way its
 * own client would, so the rounding sequence here is deliberately literal
 * rather than simplified.
 */
object Orders {

    data class RoundConfig(val price: Int, val size: Int, val amount: Int)

    private val ROUNDING = mapOf(
        "0.1" to RoundConfig(1, 2, 3),
        "0.01" to RoundConfig(2, 2, 4),
        "0.005" to RoundConfig(3, 2, 5),
        "0.0025" to RoundConfig(4, 2, 6),
        "0.001" to RoundConfig(3, 2, 5),
        "0.0001" to RoundConfig(4, 2, 6),
    )

    fun roundConfigFor(tickSize: Double): RoundConfig {
        val key = formatTick(tickSize)
        return ROUNDING[key] ?: error("unsupported tick size $tickSize")
    }

    private fun formatTick(tickSize: Double): String {
        // 0.01 must key as "0.01", never "0.010000000000000002".
        val text = java.math.BigDecimal(tickSize)
            .setScale(6, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return text
    }

    private fun pow10(n: Int): Double = 10.0.pow(n)

    private fun roundDown(x: Double, d: Int): Double = floor(x * pow10(d)) / pow10(d)
    private fun roundUp(x: Double, d: Int): Double = ceil(x * pow10(d)) / pow10(d)
    private fun roundNormal(x: Double, d: Int): Double =
        Math.round(x * pow10(d)).toDouble() / pow10(d)

    internal fun decimalPlaces(x: Double): Int {
        val s = x.toString()
        val eIndex = s.indexOfFirst { it == 'e' || it == 'E' }
        if (eIndex >= 0) {
            val mantissa = s.substring(0, eIndex)
            val exponent = s.substring(eIndex + 1).toInt()
            val fraction = mantissa.substringAfter('.', "").length
            return maxOf(0, fraction - exponent)
        }
        val fraction = s.substringAfter('.', "")
        // Kotlin renders whole doubles as "5.0" where the reference renders "5";
        // a lone trailing zero is not a real decimal place.
        return if (fraction == "0") 0 else fraction.length
    }

    /** USDC and CTF shares both carry six decimals. */
    private fun toTokenDecimals(x: Double): BigInteger {
        var f = 1_000_000.0 * x
        if (decimalPlaces(f) > 0) f = f.roundToLong().toDouble()
        return BigInteger.valueOf(f.roundToLong())
    }

    data class Amounts(val makerAmount: BigInteger, val takerAmount: BigInteger)

    private fun fit(value: Double, cfg: RoundConfig): Double {
        var v = value
        if (decimalPlaces(v) > cfg.amount) {
            v = roundUp(v, cfg.amount + 4)
            if (decimalPlaces(v) > cfg.amount) v = roundDown(v, cfg.amount)
        }
        return v
    }

    /** `amount` is USDC for a BUY and shares for a SELL. */
    fun marketOrderAmounts(
        side: String,
        amount: Double,
        price: Double,
        cfg: RoundConfig,
    ): Amounts {
        val rawPrice = roundDown(price, cfg.price)
        return if (side == "BUY") {
            val rawMaker = roundDown(amount, cfg.size)
            val rawTaker = fit(rawMaker / rawPrice, cfg)
            Amounts(toTokenDecimals(rawMaker), toTokenDecimals(rawTaker))
        } else {
            val rawMaker = roundDown(amount, cfg.size)
            val rawTaker = fit(rawMaker * rawPrice, cfg)
            Amounts(toTokenDecimals(rawMaker), toTokenDecimals(rawTaker))
        }
    }

    /** `size` is always shares. */
    fun limitOrderAmounts(
        side: String,
        size: Double,
        price: Double,
        cfg: RoundConfig,
    ): Amounts {
        val rawPrice = roundNormal(price, cfg.price)
        return if (side == "BUY") {
            val rawTaker = roundDown(size, cfg.size)
            val rawMaker = fit(rawTaker * rawPrice, cfg)
            Amounts(toTokenDecimals(rawMaker), toTokenDecimals(rawTaker))
        } else {
            val rawMaker = roundDown(size, cfg.size)
            val rawTaker = fit(rawMaker * rawPrice, cfg)
            Amounts(toTokenDecimals(rawMaker), toTokenDecimals(rawTaker))
        }
    }

    data class SignedOrder(
        val salt: BigInteger,
        val maker: String,
        val signer: String,
        val tokenId: String,
        val makerAmount: String,
        val takerAmount: String,
        val side: String,
        val expiration: String,
        val signatureType: Int,
        val timestamp: String,
        val metadata: String,
        val builder: String,
        val signature: String,
    )

    fun buildAndSign(
        keyPair: Secp256k1.KeyPair,
        signerAddress: String,
        funder: String,
        signatureType: SignatureType,
        tokenId: String,
        side: String,
        amounts: Amounts,
        negRisk: Boolean,
        salt: BigInteger = randomSalt(),
        timestampMs: Long = System.currentTimeMillis(),
    ): SignedOrder {
        val verifyingContract =
            if (negRisk) Contracts.NEG_RISK_EXCHANGE_V2 else Contracts.EXCHANGE_V2

        val struct = Signing.OrderStruct(
            salt = salt,
            maker = funder,
            signer = signerAddress,
            tokenId = BigInteger(tokenId),
            makerAmount = amounts.makerAmount,
            takerAmount = amounts.takerAmount,
            side = if (side == "BUY") 0 else 1,
            signatureType = signatureType.value,
            timestamp = BigInteger.valueOf(timestampMs),
        )

        val signature = Signing.signOrder(
            struct,
            verifyingContract,
            Contracts.CHAIN_ID,
            keyPair,
        )

        return SignedOrder(
            salt = salt,
            maker = funder,
            signer = signerAddress,
            tokenId = tokenId,
            makerAmount = amounts.makerAmount.toString(),
            takerAmount = amounts.takerAmount.toString(),
            side = side,
            expiration = "0",
            signatureType = signatureType.value,
            timestamp = timestampMs.toString(),
            metadata = Contracts.BYTES32_ZERO,
            builder = Contracts.BYTES32_ZERO,
            signature = signature,
        )
    }

    private fun randomSalt(): BigInteger =
        BigInteger.valueOf((Math.random() * System.currentTimeMillis()).toLong())
}
