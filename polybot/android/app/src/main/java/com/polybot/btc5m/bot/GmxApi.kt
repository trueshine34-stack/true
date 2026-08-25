package com.polybot.btc5m.bot

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * GMX price feed, for the manual trading chart.
 *
 * This is GMX's own oracle aggregation, not the Chainlink TWAP the 5-minute
 * markets settle against. It is here because it is what the chart on app.gmx.io
 * shows, and the manual panel is for reading the market by eye; nothing that
 * decides a settlement or a strike is allowed to come from it.
 */
object GmxApi {

    /** Both hosts serve the same data; the second is GMX's own fallback. */
    private val HOSTS = listOf(
        "https://arbitrum-api.gmxinfra.io",
        "https://arbitrum-api.gmxinfra2.io",
    )

    /** Prices arrive scaled to 30 decimals minus the token's own. */
    private const val BTC_DECIMALS = 8

    data class Candle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
    )

    data class Ticker(val min: Double, val max: Double, val at: Long) {
        val mid: Double get() = (min + max) / 2
    }

    private fun fetch(path: String): String {
        var last: Exception? = null
        for (host in HOSTS) {
            try {
                return Http.get("$host$path")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("GMX недоступен")
    }

    /**
     * Newest candle first, as GMX returns them. Each is
     * `[timestamp, open, high, low, close]`.
     */
    fun candles(symbol: String = "BTC", period: String = "1m", limit: Int = 120): List<Candle> {
        val text = fetch("/prices/candles?tokenSymbol=$symbol&period=$period&limit=$limit")
        val array = JSONObject(text).optJSONArray("candles") ?: return emptyList()

        val out = ArrayList<Candle>(array.length())
        for (i in 0 until array.length()) {
            val c: JSONArray = array.optJSONArray(i) ?: continue
            if (c.length() < 5) continue
            out.add(
                Candle(
                    time = c.optLong(0),
                    open = c.optDouble(1),
                    high = c.optDouble(2),
                    low = c.optDouble(3),
                    close = c.optDouble(4),
                ),
            )
        }
        return out
    }

    /**
     * Latest price. GMX quotes a min and a max — the two sides of its oracle
     * band — so both are carried rather than being flattened here.
     */
    fun ticker(symbol: String = "BTC", decimals: Int = BTC_DECIMALS): Ticker? {
        val array = JSONArray(fetch("/prices/tickers"))
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            if (row.optString("tokenSymbol") != symbol) continue
            return Ticker(
                min = scale(row.optString("minPrice"), decimals),
                max = scale(row.optString("maxPrice"), decimals),
                at = row.optLong("updatedAt"),
            )
        }
        return null
    }

    /**
     * A BTC price at 22 decimal places overflows a double's exact range long
     * before the division happens, so the shift is done in BigDecimal.
     */
    private fun scale(raw: String, decimals: Int): Double {
        if (raw.isEmpty()) return Double.NaN
        return BigDecimal(raw).movePointLeft(30 - decimals).toDouble()
    }
}
