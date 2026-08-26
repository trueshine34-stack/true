package com.polybot.btc5m.bot

import org.json.JSONArray

/**
 * Polymarket's data API.
 *
 * Positions are not something the CLOB reports: it knows orders and trades, not
 * holdings. This is the same endpoint the website reads, and it already carries
 * the average entry price across every fill, which is exactly what the position
 * list needs to show.
 */
object DataApi {

    private const val HOST = "https://data-api.polymarket.com"

    /**
     * Open positions only.
     *
     * Without `redeemable=false` the answer is dominated by settled markets
     * waiting to be claimed — on a five-minute series that is a new dead entry
     * every five minutes, which would bury the one position that is still live.
     */
    fun positions(user: String, limit: Int = 50): List<Position> {
        val text = Http.get(
            "$HOST/positions?user=$user&limit=$limit&sizeThreshold=0.1&redeemable=false",
        )
        val array = JSONArray(text)
        val out = ArrayList<Position>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val size = o.optDouble("size", 0.0)
            if (size <= 0.0) continue
            out.add(
                Position(
                    asset = o.optString("asset"),
                    conditionId = o.optString("conditionId"),
                    title = o.optString("title"),
                    outcome = o.optString("outcome"),
                    size = size,
                    avgPrice = o.optDouble("avgPrice", 0.0),
                    curPrice = o.optDouble("curPrice", 0.0),
                    cashPnl = o.optDouble("cashPnl", 0.0),
                    redeemable = o.optBoolean("redeemable", false),
                ),
            )
        }
        return out
    }

    /**
     * Trades that actually happened, newest first.
     *
     * This is the only unambiguous answer to "did my sell fill". Asking the
     * exchange about a single order cannot tell a fill from a cancel once the
     * order has left the book — both come back as nothing — and treating that
     * nothing as a cancel is what kept the buy-back from ever triggering.
     */
    data class Trade(
        val hash: String,
        val asset: String,
        val conditionId: String,
        val side: String,
        val size: Double,
        val price: Double,
        val at: Long,
        val outcome: String,
    ) {
        /** A transaction can settle several outcomes; the asset separates them. */
        val key: String get() = "$hash:$asset:$side:$size"
    }

    fun trades(user: String, limit: Int = 25): List<Trade> {
        val array = JSONArray(Http.get("$HOST/trades?user=$user&limit=$limit"))
        val out = ArrayList<Trade>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val size = o.optDouble("size", 0.0)
            val price = o.optDouble("price", 0.0)
            if (size <= 0.0 || price <= 0.0) continue
            out.add(
                Trade(
                    hash = o.optString("transactionHash"),
                    asset = o.optString("asset"),
                    conditionId = o.optString("conditionId"),
                    side = o.optString("side").uppercase(),
                    size = size,
                    price = price,
                    // The feed stamps trades in seconds; everything here
                    // works in milliseconds, and a fill filed in seconds sorts
                    // as though it happened in 1970.
                    at = o.optLong("timestamp").let { if (it < 1_000_000_000_000L) it * 1000 else it },
                    outcome = o.optString("outcome"),
                ),
            )
        }
        return out
    }
}
