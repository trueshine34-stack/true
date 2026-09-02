package com.polybot.btc5m.bot

import org.json.JSONArray

/**
 * Binance's own five-minute candle for Bitcoin.
 *
 * The desk used to show Polymarket's TWAP, which is the number the market
 * settles on but not the number anyone is watching. Binance is where the price
 * is actually made and where every chart in the room is pointed, so that is
 * what the header shows: the open of the current five minutes, and how far
 * price has moved from it.
 *
 * Worth being plain about: this is a reference, not a strike. Polymarket
 * settles Up or Down on its own thirty-second TWAP, so a window that is a
 * dollar up on Binance can still settle the other way. Nothing here is priced
 * or decided off these numbers.
 *
 * The data-only mirror is tried first. api.binance.com refuses whole countries
 * outright — the answer is a 200 with a "restricted location" message rather
 * than an error — while the mirror serves the same spot data to anyone.
 */
object BinanceApi {

    private val HOSTS = listOf(
        "https://data-api.binance.vision",
        "https://api.binance.com",
    )

    /** The candle in progress: where it opened and where price is now. */
    data class Candle(
        val openTime: Long,
        val open: Double,
        val last: Double,
        val at: Long,
    )

    fun current(symbol: String = Coins.current.pair, interval: String = "5m"): Candle {
        var last: Exception? = null
        for (host in HOSTS) {
            try {
                val raw = Http.get(
                    "$host/api/v3/klines?symbol=$symbol&interval=$interval&limit=1",
                )
                val rows = JSONArray(raw)
                val row = rows.optJSONArray(0) ?: continue
                return Candle(
                    openTime = row.optLong(0) / 1000,
                    open = row.optString(1).toDoubleOrNull() ?: continue,
                    last = row.optString(4).toDoubleOrNull() ?: continue,
                    at = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Binance не ответил")
    }
}
