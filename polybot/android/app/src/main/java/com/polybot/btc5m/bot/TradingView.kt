package com.polybot.btc5m.bot

import org.json.JSONArray
import org.json.JSONObject

/**
 * TradingView's own technical read on five-minute Bitcoin.
 *
 * The page everyone actually looks at shows three gauges — the oscillators,
 * the moving averages, and the summary that combines them — each a number
 * between −1 and 1 that TradingView turns into the words "Strong Sell" through
 * "Strong Buy". This is the endpoint that page itself calls, so the numbers
 * here are the numbers on the screen rather than an approximation of them.
 *
 * The symbol is Binance's BTCUSDT because that is what the request names; the
 * market being traded settles on Polymarket's own feed, so this is a signal,
 * not a price, and nothing is priced off it.
 */
object TradingView {

    private const val HOST = "https://scanner.tradingview.com/crypto/scan"

    const val SYMBOL = "BINANCE:BTCUSDT"

    /** The three gauges, on TradingView's own −1..1 scale. */
    data class Gauges(
        val summary: Double,
        val movingAverages: Double,
        val oscillators: Double,
        val close: Double,
        val at: Long,
    )

    fun read(interval: String = "5"): Gauges {
        val body = JSONObject()
            .put(
                "symbols",
                JSONObject()
                    .put("tickers", JSONArray().put(SYMBOL))
                    .put("query", JSONObject().put("types", JSONArray())),
            )
            .put(
                "columns",
                JSONArray()
                    .put("Recommend.All|$interval")
                    .put("Recommend.MA|$interval")
                    .put("Recommend.Other|$interval")
                    .put("close|$interval"),
            )
            .toString()

        val raw = Http.postJson(
            HOST,
            body,
            // The scanner refuses a request with no browser about it.
            mapOf("User-Agent" to "Mozilla/5.0", "Origin" to "https://www.tradingview.com"),
        )
        val data = JSONObject(raw).optJSONArray("data")
            ?: error("TradingView не ответил")
        val row = data.optJSONObject(0)?.optJSONArray("d")
            ?: error("TradingView не дал показатели")

        return Gauges(
            summary = row.optDouble(0, Double.NaN),
            movingAverages = row.optDouble(1, Double.NaN),
            oscillators = row.optDouble(2, Double.NaN),
            close = row.optDouble(3, 0.0),
            at = System.currentTimeMillis(),
        )
    }
}
