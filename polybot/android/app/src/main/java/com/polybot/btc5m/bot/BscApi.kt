package com.polybot.btc5m.bot

import java.math.BigInteger
import org.json.JSONObject

/**
 * USDT on BNB Smart Chain, read straight off the chain.
 *
 * Profit taken out of Polymarket lands here, and money that has left the venue
 * is still the run's money — a balance line that drops by the amount withdrawn
 * reads a good week as a bad one. So the desk asks the chain what this address
 * holds and counts it alongside the collateral.
 *
 * Read-only and keyless: `balanceOf` is a view call, and nothing here can move
 * anything. The address is the user's to set and nothing is ever sent to it.
 *
 * Note the decimals. BSC-USD is an eighteen-decimal token, unlike the six of
 * USDC on Polygon, and dividing by the wrong one is off by a factor of a
 * million in the direction that looks like a fortune.
 */
object BscApi {

    private val RPCS = listOf(
        "https://bsc-dataseed.binance.org",
        "https://bsc-rpc.publicnode.com",
        "https://bsc-dataseed1.defibit.io",
    )

    /** BSC-USD, the Binance-pegged USDT. */
    private const val USDT = "0x55d398326f99059fF775485246999027B3197955"

    /** `balanceOf(address)` */
    private const val SELECTOR = "0x70a08231"

    private const val DECIMALS = 1_000_000_000_000_000_000.0

    /** Whether this looks like an address at all, before anything is asked. */
    fun looksLikeAddress(address: String): Boolean =
        address.matches(Regex("^0x[0-9a-fA-F]{40}$"))

    fun usdtBalance(address: String): Double {
        val clean = address.trim()
        require(looksLikeAddress(clean)) { "адрес не похож на адрес BEP-20" }

        val data = SELECTOR + clean.removePrefix("0x").lowercase().padStart(64, '0')
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "eth_call")
            .put(
                "params",
                org.json.JSONArray()
                    .put(JSONObject().put("to", USDT).put("data", data))
                    .put("latest"),
            )
            .toString()

        var last: Exception? = null
        for (rpc in RPCS) {
            try {
                val json = JSONObject(
                    Http.postJson(rpc, body, mapOf("content-type" to "application/json")),
                )
                json.optJSONObject("error")?.let {
                    throw IllegalStateException(it.optString("message", "узел отказал"))
                }
                val hex = json.optString("result").removePrefix("0x")
                if (hex.isEmpty()) throw IllegalStateException("пустой ответ узла")
                return BigInteger(hex, 16).toDouble() / DECIMALS
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("сеть BSC недоступна")
    }
}
