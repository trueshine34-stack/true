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

    /** Dump part of the position at market once it has doubled. */
    val takeProfitEnabled: Boolean = false,
    /** Trigger multiple on the average entry price. */
    val takeProfitMultiple: Double = 2.0,
    /** Share of the position to sell when it triggers. */
    val takeProfitFraction: Double = 0.5,

    /** Buy the same amount again once the price has halved. */
    val averageDownEnabled: Boolean = false,
    /** Trigger multiple on the average entry price. */
    val averageDownMultiple: Double = 0.5,
    /** How many times per window this may fire. */
    val averageDownMaxTimes: Int = 1,
    /**
     * No averaging down inside this many seconds of the close. Late in a window
     * a halved price usually means the outcome is nearly decided, not that the
     * side is cheap.
     */
    val averageDownDeadlineSec: Int = 60,
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

/**
 * What the model has learned about its own confidence.
 *
 * Every settled window contributes one point: how far the model leaned from
 * 50/50, and whether the lean was right. The least-squares shrinkage that
 * minimises Brier score over those points needs only two running sums, so the
 * whole history compresses into a handful of numbers.
 */
data class Calibration(
    val sumXY: Double = 0.0,
    val sumXX: Double = 0.0,
    val samples: Int = 0,
    val brierSum: Double = 0.0,
) {
    /** Mean Brier score, lower is better; 0.25 is a coin flip. */
    val brier: Double? get() = if (samples > 0) brierSum / samples else null

    fun plus(modelledPUp: Double, upWon: Boolean): Calibration {
        val x = modelledPUp - 0.5
        val y = (if (upWon) 1.0 else 0.0) - 0.5
        val error = modelledPUp - (if (upWon) 1.0 else 0.0)
        return Calibration(
            sumXY = sumXY + x * y,
            sumXX = sumXX + x * x,
            samples = samples + 1,
            brierSum = brierSum + error * error,
        )
    }

    /**
     * Shrinkage applied to the model's lean, in [0, 1].
     *
     * The least-squares fit is `sumXY / sumXX`; it is blended against a
     * deliberately timid prior with the weight of thirty windows, so a short
     * lucky streak cannot talk the bot into trusting itself. Weighting by
     * sample count rather than by a fixed constant matters: a handful of
     * strongly-leaning calls would otherwise swamp a prior scaled for ordinary
     * ones. Never above 1 — the model may be discounted, never amplified.
     */
    fun shrinkage(): Double {
        if (samples <= 0 || sumXX <= 0.0) return PRIOR_LAMBDA
        val fitted = sumXY / sumXX
        val blended = (samples * fitted + PRIOR_SAMPLES * PRIOR_LAMBDA) /
            (samples + PRIOR_SAMPLES)
        return blended.coerceIn(0.0, 1.0)
    }

    private companion object {
        const val PRIOR_SAMPLES = 30.0
        const val PRIOR_LAMBDA = 0.5
    }
}

/** Forecast mean and standard deviation of the settlement TWAP, in price units. */
data class TwapForecast(val mean: Double, val sd: Double)

data class FairValue(
    /** Probability actually traded on, after shrinkage. */
    val pUp: Double,
    /**
     * The model's unshrunk output. Calibration must be fitted against this, not
     * against pUp — grading the already-shrunk number would compose one
     * shrinkage on top of another and slowly forget the correction.
     */
    val rawPUp: Double,
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
    /** Forces the ladder to rebuild its order after the position size changed. */
    var exitNeedsResync: Boolean = false,
    /** Cost of the first entry, which is what each average-down repeats. */
    var baseCostUsd: Double = 0.0,
    var takeProfitDone: Boolean = false,
    var averageDownCount: Int = 0,
    /** Shares sold at market rather than through the ladder, and their proceeds. */
    var soldAtMarket: Double = 0.0,
    var marketProceedsUsd: Double = 0.0,
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
