package com.polybot.btc5m.bot

import java.util.concurrent.ConcurrentHashMap

/**
 * What this app has actually bought and sold, per outcome.
 *
 * Polymarket's data API is the right source for a position's average entry —
 * it accounts for every fill, including ones made elsewhere — but it indexes a
 * trade a moment after the exchange reports it. In that gap it answers with the
 * size already updated and the cost basis still at zero, which reads on screen
 * as "bought at 0¢" and a profit equal to the whole position.
 *
 * So the app keeps its own record of the fills it saw with its own eyes and
 * uses it only to fill that gap. Once the API has a real average it wins, since
 * it knows about trades this record never saw.
 */
object LocalFills {

    private data class Held(var shares: Double, var costUsd: Double)

    private val byAsset = ConcurrentHashMap<String, Held>()

    private const val MAX_ASSETS = 64

    fun bought(asset: String, shares: Double, costUsd: Double) {
        if (asset.isEmpty() || shares <= 0.0) return
        synchronized(byAsset) {
            val held = byAsset.getOrPut(asset) { Held(0.0, 0.0) }
            held.shares += shares
            held.costUsd += costUsd
            // Five-minute markets mint two new outcomes every window, so this
            // would grow without bound if nothing evicted.
            if (byAsset.size > MAX_ASSETS) {
                byAsset.entries.firstOrNull { it.value.shares <= 1e-9 }
                    ?.let { byAsset.remove(it.key) }
            }
        }
    }

    /** Average-cost accounting: a partial sale leaves the average where it was. */
    fun sold(asset: String, shares: Double) {
        if (asset.isEmpty() || shares <= 0.0) return
        synchronized(byAsset) {
            val held = byAsset[asset] ?: return
            if (held.shares <= 1e-9) return
            val fraction = (shares / held.shares).coerceIn(0.0, 1.0)
            held.costUsd -= held.costUsd * fraction
            held.shares -= held.shares * fraction
            if (held.shares < 1e-9) byAsset.remove(asset)
        }
    }

    fun avgFor(asset: String): Double? {
        val held = byAsset[asset] ?: return null
        if (held.shares <= 1e-9 || held.costUsd <= 0.0) return null
        return held.costUsd / held.shares
    }

    fun clear() = byAsset.clear()
}
