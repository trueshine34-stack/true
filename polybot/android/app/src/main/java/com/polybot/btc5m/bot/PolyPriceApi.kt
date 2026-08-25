package com.polybot.btc5m.bot

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/**
 * The price series Polymarket itself draws.
 *
 * Their chart is not the raw Chainlink tick: it is the thirty-second TWAP, the
 * same average the five-minute markets settle against. Charting anything else —
 * a spot exchange, another oracle's aggregation — puts a visibly different
 * number next to the one the market is judged by, which is worse than useless
 * on a desk where that judgement is the whole trade.
 *
 * The endpoint answers one five-minute window per call, so a longer chart is
 * several calls stitched together. Windows that have closed can never change,
 * so they are kept.
 */
object PolyPriceApi {

    private const val HOST = "https://polymarket.com"
    private const val TWAP_LOOKBACK_SEC = 30

    data class Point(val timestamp: Long, val value: Double)

    data class Candle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
    )

    /** Closed windows only; the live one is refetched every time. */
    private val cache = ConcurrentHashMap<Long, List<Point>>()

    fun window(windowStart: Long, symbol: String = "BTC"): List<Point> {
        cache[windowStart]?.let { return it }

        val url = "$HOST/api/crypto/price-history?symbol=$symbol" +
            "&eventStartTime=$windowStart&variant=fiveminute" +
            "&twapEnabled=true&twapLookbackSeconds=$TWAP_LOOKBACK_SEC"
        val array = JSONArray(Http.get(url))

        val out = ArrayList<Point>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val timestamp = o.optLong("timestamp")
            val value = o.optDouble("value", Double.NaN)
            if (timestamp <= 0L || value.isNaN()) continue
            out.add(Point(timestamp, value))
        }

        // A window still running will keep growing; only a finished one is
        // worth remembering.
        if (windowStart + WINDOW_SECONDS <= Clock.nowSec() && out.isNotEmpty()) {
            cache[windowStart] = out
            if (cache.size > 48) {
                cache.keys.minOrNull()?.let { cache.remove(it) }
            }
        }
        return out
    }

    /**
     * One-minute candles over the last `minutes`, stitched from as many windows
     * as that spans. A window that fails to load is skipped rather than
     * aborting the chart — a gap in the middle beats no chart at all.
     */
    fun candles(minutes: Int, symbol: String = "BTC"): List<Candle> {
        val now = Clock.nowSec()
        val currentWindow = now - (now % WINDOW_SECONDS)
        val windows = ((minutes * 60 + WINDOW_SECONDS - 1) / WINDOW_SECONDS).coerceAtLeast(1)

        val points = ArrayList<Point>()
        for (i in windows - 1 downTo 0) {
            val start = currentWindow - i * WINDOW_SECONDS
            try {
                points.addAll(window(start, symbol))
            } catch (e: Exception) {
                continue
            }
        }
        return toCandles(points, now - minutes * 60L)
    }

    /** Groups points into whole minutes. Public so the shaping can be tested. */
    fun toCandles(points: List<Point>, fromSec: Long = 0L): List<Candle> {
        if (points.isEmpty()) return emptyList()

        val byMinute = LinkedHashMap<Long, MutableList<Double>>()
        for (point in points.sortedBy { it.timestamp }) {
            val sec = point.timestamp / 1000
            if (sec < fromSec) continue
            byMinute.getOrPut(sec - sec % 60) { ArrayList() }.add(point.value)
        }

        return byMinute.map { (minute, values) ->
            Candle(
                time = minute,
                open = values.first(),
                high = values.max(),
                low = values.min(),
                close = values.last(),
            )
        }
    }

    fun clear() = cache.clear()
}
