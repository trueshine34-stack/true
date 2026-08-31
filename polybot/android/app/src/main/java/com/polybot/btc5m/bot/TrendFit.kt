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

    /**
     * A line has to explain this much of the movement to be a direction.
     *
     * Fifteen per cent. It was a quarter, and a quarter refused a third of
     * all windows for untidiness rather than for standing still — "вбок" is
     * meant to mean the line has not gone anywhere, not that it got there by
     * a crooked path. Measured over 7907 windows split by time, the tighter
     * bar bought nothing: the side goes more than a quarter of a typical move
     * our way in 57.6% then 61.0% of the windows it allowed, against 57.8%
     * then 60.5% at this one and 57.3% then 61.0% with no bar at all. It cost
     * a third of the entries and separated nothing, so it is the loose one.
     */
    const val MIN_FIT = 0.15

    /** And carry the span this far against its own range. */
    const val MIN_TRAVEL = 0.2

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
     * How long the near line looks back, in minutes.
     *
     * A quarter of an hour. It was half an hour, and half an hour is three
     * windows of history deciding one window's bet: by the time a turn shows
     * up in a thirty-minute fit, five minutes of it have already happened.
     * Fifteen minutes is still fifteen points, which is enough to fit a line
     * through and few enough that the line is about now.
     */
    const val NEAR_MINUTES = 15

    /**
     * The line the one-minute chart is drawing, which is the one on screen.
     *
     * The same span that panel fits, so a rule that follows this follows what
     * the user is looking at rather than a second opinion computed elsewhere.
     * The closer chart is the one that matters for a five-minute bet — an
     * hour of five-minute candles describes the session, and the next five
     * minutes are decided by the last few.
     */
    fun onScreen(): Trend? = of(BinanceCandles.oneMinute.list(), NEAR_MINUTES)

    /**
     * How long the wider line looks back, in minutes.
     *
     * Half an hour, which on the five-minute chart is six candles.
     *
     * It has been three hours, then one. Each time the answer was the same:
     * what a long line fits is the session, and by the time it has turned,
     * the move it was describing is over. This one is only there to say
     * whether the wider frame disagrees with the minute chart, and for that
     * it has to be looking at roughly the same stretch of tape.
     */
    const val WIDE_MINUTES = 30

    /** The wider line, for anything that wants the session's direction. */
    fun wide(): Trend? = of(BinanceCandles.fiveMinute.list(), WIDE_MINUTES)

    /**
     * Which way the line points, whatever it is worth.
     *
     * [Trend.way] is a judgement: it refuses to call a direction the fit does
     * not support, which is right for anything choosing whether to act. A rule
     * that acts every window is not choosing whether — only which — and for
     * that the question is simply which end of the fitted line is higher. The
     * strength is still on the card, and still in the record beside every
     * round, so a run of weak lines can be read afterwards.
     */
    fun lean(trend: Trend?): String = when {
        trend == null -> ""
        trend.way.isNotEmpty() -> trend.way
        trend.perHour > 0.0 -> "Up"
        trend.perHour < 0.0 -> "Down"
        else -> ""
    }
}
