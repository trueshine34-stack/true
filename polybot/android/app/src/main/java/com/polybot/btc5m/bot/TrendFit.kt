package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * The direction of the last stretch, fitted rather than eyeballed.
 *
 * The same arithmetic the chart draws, so the rule that uses it trades the
 * line that is on the screen: least squares through the closes of the span,
 * the slope in dollars an hour, and a check on whether the word "trend"
 * applies at all. Price that wanders around the line as much as it follows it
 * is chop, and a rule that trades chop as a direction is a rule that trades
 * noise.
 */
object TrendFit {

    /** A line has to explain this much of the movement to be a direction. */
    const val MIN_FIT = 0.25

    /** And carry the span this far against its own range. */
    const val MIN_TRAVEL = 0.3

    data class Trend(
        val perHour: Double,
        /** "Up", "Down", or empty when the fit refuses to call it. */
        val way: String,
        val fit: Double,
    )

    fun of(candles: List<BinanceCandles.Candle>, overMinutes: Int): Trend? {
        val clean = candles.filter { it.open > 0 && it.high > 0 && it.low > 0 && it.close > 0 }
        if (clean.size < 4) return null

        val step = if (clean.size > 1) clean[1].time - clean[0].time else 60L
        if (step <= 0L) return null

        val want = maxOf(4, ((overMinutes * 60L) / step).toInt())
        val use = clean.takeLast(minOf(clean.size, want))
        val n = use.size

        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sx += i
            sy += use[i].close
        }
        val mx = sx / n
        val my = sy / n

        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (i - mx) * (use[i].close - my)
            sxx += (i - mx) * (i - mx)
        }
        if (sxx <= 0.0) return null

        val slope = sxy / sxx
        val intercept = my - slope * mx

        var ssTot = 0.0
        var ssRes = 0.0
        for (i in 0 until n) {
            val fitted = intercept + slope * i
            ssTot += (use[i].close - my) * (use[i].close - my)
            ssRes += (use[i].close - fitted) * (use[i].close - fitted)
        }
        val fit = if (ssTot > 0) maxOf(0.0, 1 - ssRes / ssTot) else 0.0

        val travel = slope * (n - 1)
        val low = use.minOf { it.low }
        val high = use.maxOf { it.high }
        val range = high - low
        val strong = fit >= MIN_FIT && range > 0 && abs(travel) >= range * MIN_TRAVEL

        return Trend(
            perHour = slope * 3600.0 / step,
            way = if (!strong) "" else if (travel > 0) "Up" else "Down",
            fit = fit,
        )
    }

    /**
     * The line the one-minute chart is drawing, which is the one on screen.
     *
     * Half an hour of one-minute candles: the same span that panel fits, so a
     * rule that follows this follows what the user is looking at rather than a
     * second opinion computed elsewhere. The closer chart is the one that
     * matters for a five-minute bet — an hour of five-minute candles describes
     * the afternoon, and the next five minutes are decided by the last thirty.
     */
    fun onScreen(): Trend? = of(BinanceCandles.oneMinute.list(), 30)

    /** The wider line, for anything that wants the afternoon's direction. */
    fun wide(): Trend? = of(BinanceCandles.fiveMinute.list(), 60)
}
