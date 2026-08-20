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
}
