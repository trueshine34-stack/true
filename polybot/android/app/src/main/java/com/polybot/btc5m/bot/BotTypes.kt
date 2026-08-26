package com.polybot.btc5m.bot

/** Contract addresses and endpoints, mirroring the web layer's config. */
object Contracts {
    const val CHAIN_ID = 137L
    const val EXCHANGE_V2 = "0xE111180000d2663C0091e4f400237545B87B996B"
    const val NEG_RISK_EXCHANGE_V2 = "0xe2222d279d744050d28e00520010520000310F59"
    const val BYTES32_ZERO =
        "0x0000000000000000000000000000000000000000000000000000000000000000"
}

object Endpoints {
    const val CLOB = "https://clob.polymarket.com"
    const val GAMMA = "https://gamma-api.polymarket.com"
    const val RTDS = "wss://ws-live-data.polymarket.com"
}

const val WINDOW_SECONDS = 300L
const val TWAP_WINDOW_SECONDS = 30.0

enum class SignatureType(val value: Int) {
    EOA(0),
    POLY_PROXY(1),
    POLY_GNOSIS_SAFE(2),
    ;

    companion object {
        fun from(value: Int): SignatureType =
            entries.firstOrNull { it.value == value } ?: EOA
    }
}

data class Credentials(
    val apiKey: String,
    val secret: String,
    val passphrase: String,
)

data class Account(
    val privateKey: String,
    val signerAddress: String,
    val funderAddress: String,
    val signatureType: SignatureType,
)

/** One rung: from `fromSec` into the window, rest the sell at `price`. */
data class Outcome(val label: String, val tokenId: String)

data class Market(
    val slug: String,
    val conditionId: String,
    val question: String,
    val windowStart: Long,
    val windowEnd: Long,
    val negRisk: Boolean,
    val tickSize: Double,
    val minimumOrderSize: Double,
    val acceptingOrders: Boolean,
    val up: Outcome,
    val down: Outcome,
)

data class Tick(val timestamp: Long, val value: Double)

/** Live top of book for one outcome. */
data class Quote(
    val bestBid: Double?,
    val bestAsk: Double?,
) {
    val mid: Double? get() =
        if (bestBid != null && bestAsk != null) (bestBid + bestAsk) / 2 else bestBid ?: bestAsk
}

data class Quotes(val up: Quote?, val down: Quote?, val atMs: Long)

/** An open position as Polymarket's data API reports it. */
data class Position(
    val asset: String,
    val conditionId: String,
    val title: String,
    val outcome: String,
    val size: Double,
    val avgPrice: Double,
    val curPrice: Double,
    val cashPnl: Double,
    val redeemable: Boolean,
)

/**
 * What the model has learned about its own confidence.
 *
 * Every settled window contributes one point: how far the model leaned from
 * 50/50, and whether the lean was right. The least-squares shrinkage that
 * minimises Brier score over those points needs only two running sums, so the
 * whole history compresses into a handful of numbers.
 */
/** Forecast mean and standard deviation of the settlement TWAP, in price units. */
/** A resting sell parked on the position. */
data class LogEntry(
    val id: Long,
    val at: Long,
    val level: String,
    val message: String,
)
