package com.polybot.btc5m.bot

/**
 * Buy against a move that has just run out of breath.
 *
 * A five-minute candle that makes the highest high of the last twenty and
 * then closes in the top quarter of its own range has spent itself: it went
 * as far as it could and finished there, with nothing left over. The next
 * five minutes more often gives some of it back than takes it further. The
 * mirror is the same statement upside down — a new twenty-candle low closing
 * in the bottom quarter is a fall that has finished falling.
 *
 * The numbers are the user's, from eight months of five-minute candles
 * (69 120 of them, January to August 2026) searched over thirty-two features
 * and every combination up to four, fitted on the first seventy per cent and
 * checked once on the last thirty. The two patterns here are the ones with
 * enough cases to believe:
 *
 *  - new high, close in the top quarter: 59.2% on 737 fitted, 56.4% on 335 held back
 *  - new low, close in the bottom quarter: 58.2% on 732 fitted, 57.6% on 304 held back
 *
 * And the discipline that makes those numbers worth anything: the same search
 * run over shuffled labels invents patterns at 57–58% on the data it is
 * fitted to, and they collapse to about 51% on data they have not seen.
 * These do not collapse. Narrower versions score higher — a weekend variant
 * reaches 67% — on a hundred cases, which is not yet a fact.
 *
 * What this is not is a licence to pay anything for the side. At 58c a share
 * the fee makes break-even about 60% and this is 57%; the edge lives at the
 * cheap end of the book and nowhere else.
 */
object FadePlan {

    /** How many candles back the high or low has to be the highest or lowest. */
    const val REACH = 20

    /** How near its own extreme the candle has to close, as a share of range. */
    const val QUARTER = 0.25

    /**
     * The side to buy off the candle that has just closed, or empty.
     *
     * [candles] is the five-minute series with the closing candle last. The
     * twenty before it are what its extreme is measured against; the candle
     * itself is not one of them, or every candle would be its own high.
     */
    fun side(candles: List<BinanceCandles.Candle>, reach: Int = REACH): String {
        if (reach < 1 || candles.size < reach + 1) return ""
        val last = candles.last()
        if (last.high <= 0.0 || last.low <= 0.0 || last.close <= 0.0) return ""

        val range = last.high - last.low
        if (range <= 0.0) return ""

        val before = candles.subList(candles.size - reach - 1, candles.size - 1)
        if (before.any { it.high <= 0.0 || it.low <= 0.0 }) return ""

        // Where in its own range it finished: one is the high, nought the low.
        val at = (last.close - last.low) / range

        val newHigh = last.high > before.maxOf { it.high }
        val newLow = last.low < before.minOf { it.low }

        // A candle that is both — an outside bar taking out twenty either way
        // — has made no statement about which end it ran out at.
        if (newHigh && newLow) return ""

        return when {
            newHigh && at >= 1.0 - QUARTER -> "Down"
            newLow && at <= QUARTER -> "Up"
            else -> ""
        }
    }

    /** Why there is no side, in the words the record uses. */
    fun why(candles: List<BinanceCandles.Candle>, reach: Int = REACH): String {
        if (candles.size < reach + 1) return "мало свечей"
        val last = candles.last()
        val range = last.high - last.low
        if (range <= 0.0) return "свеча без размаха"
        val before = candles.subList(candles.size - reach - 1, candles.size - 1)
        val newHigh = last.high > before.maxOf { it.high }
        val newLow = last.low < before.minOf { it.low }
        if (newHigh && newLow) return "свеча накрыла $reach в обе стороны"
        if (!newHigh && !newLow) return "нет экстремума за $reach"
        val at = (last.close - last.low) / range
        return "экстремум есть, но закрытие на " +
            Math.round(at * 100) + "% размаха"
    }

    /** Where in its own range the candle closed, for the record. */
    fun closedAt(candle: BinanceCandles.Candle?): Double {
        if (candle == null) return -1.0
        val range = candle.high - candle.low
        if (range <= 0.0) return -1.0
        return (candle.close - candle.low) / range
    }
}
