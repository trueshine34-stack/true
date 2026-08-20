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

enum class StrategyMode {
    EDGE,
    MOMENTUM,
    CONTRARIAN,
    OFF,
    ;

    companion object {
        fun from(raw: String?): StrategyMode = when (raw?.lowercase()) {
            "momentum" -> MOMENTUM
            "contrarian" -> CONTRARIAN
            "off" -> OFF
            else -> EDGE
        }
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

data class Settings(
    val mode: StrategyMode = StrategyMode.EDGE,
    val stakeUsd: Double = 2.0,
    val entryDelaySec: Int = 20,
    val minEdge: Double = 0.04,
    val maxPrice: Double = 0.90,
    val minPrice: Double = 0.05,
    val autoBumpToMinimum: Boolean = true,
    val dryRun: Boolean = true,
    val dailyLossLimitUsd: Double = 20.0,
    val maxConsecutiveLosses: Int = 6,
    /** Park a resting sell on the position once it is filled. */
    val exitEnabled: Boolean = true,
    /**
     * Delay between the buy filling and the sell going out. The shares are not
     * sellable the instant the buy matches, so an immediate sell is rejected.
     */
    val exitDelaySec: Int = 21,
    /** Sell price by elapsed second of the window, cheapest step first. */
    val exitLadder: List<ExitStep> = DEFAULT_EXIT_LADDER,
)

/** One rung: from `fromSec` into the window, rest the sell at `price`. */
data class ExitStep(val fromSec: Int, val price: Double)

val DEFAULT_EXIT_LADDER = listOf(
    ExitStep(0, 0.89),
    ExitStep(180, 0.93),
    ExitStep(240, 0.96),
    ExitStep(270, 0.99),
)

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

data class FairValue(
    val pUp: Double,
    val sigmaPerSec: Double,
    val sigmaHorizon: Double,
    val drift: Double,
)

data class Decision(
    val act: Boolean,
    val side: String?,
    val price: Double,
    val edge: Double,
    val reason: String,
)

/** A resting sell parked on the position. */
data class ExitOrder(
    val orderId: String,
    val price: Double,
    val size: Double,
    var matched: Double = 0.0,
    var cancelled: Boolean = false,
)

data class Entry(
    val side: String,
    val price: Double,
    val shares: Double,
    val costUsd: Double,
    val orderId: String?,
    val dryRun: Boolean,
)

enum class CycleState { WAITING, ARMED, ENTERED, SKIPPED, SETTLED, FAILED }

data class Cycle(
    val windowStart: Long,
    val windowEnd: Long,
    var market: Market? = null,
    var strike: Double? = null,
    var spotAtEntry: Double? = null,
    var fair: FairValue? = null,
    var entry: Entry? = null,
    /** When the buy filled, which is what the sell delay counts from. */
    var entryFilledAtMs: Long = 0,
    val exits: MutableList<ExitOrder> = mutableListOf(),
    /** Price of the last sell we tried to rest, for retry throttling. */
    var lastExitPriceTried: Double? = null,
    var lastExitAttemptMs: Long = 0,
    /** Set once the ladder can no longer act, so it stops retrying. */
    var exitFrozen: Boolean = false,
    var winner: String? = null,
    var pnlUsd: Double? = null,
    var state: CycleState = CycleState.WAITING,
    var note: String? = null,
)

data class Stats(
    var trades: Int = 0,
    var wins: Int = 0,
    var losses: Int = 0,
    var consecutiveLosses: Int = 0,
    var realisedPnlUsd: Double = 0.0,
    var stakedUsd: Double = 0.0,
)

data class LogEntry(
    val id: Long,
    val at: Long,
    val level: String,
    val message: String,
)
