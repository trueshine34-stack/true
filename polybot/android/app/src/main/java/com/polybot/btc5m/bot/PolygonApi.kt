package com.polybot.btc5m.bot

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Polygon, for the one thing the desk does outside the exchange: moving its
 * own collateral to an address the user owns.
 *
 * Everything here is deliberately narrow. It can read balances and it can send
 * one kind of transaction — an ERC-20 `transfer` of USDC — signed with the key
 * the app already holds. There is no approval, no contract call with a payload
 * anyone else composed, and no path that spends the balance on anything but a
 * transfer to the address on the screen.
 *
 * The collateral is USDC.e, the bridged six-decimal token Polymarket settles
 * in. Native USDC is read too, because a wallet that has been topped up from
 * an exchange often holds that instead, and telling the user their money is
 * missing when it is simply the other token is worse than useless.
 */
object PolygonApi {

    private val RPCS = listOf(
        "https://polygon-bor-rpc.publicnode.com",
        "https://polygon.drpc.org",
        "https://polygon.blockpi.network/v1/rpc/public",
    )

    /** Bridged USDC.e — what Polymarket settles in. */
    const val USDC_E = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"

    /** Native USDC, in case the wallet was funded with that instead. */
    const val USDC = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"

    private const val CHAIN_ID = 137L
    private const val DECIMALS = 1_000_000.0

    /** A plain transfer costs about this; the rest of a limit is refunded. */
    private const val GAS_LIMIT = 120_000L

    /** Polygon's validators drop anything under thirty gwei of priority. */
    private val MIN_PRIORITY: BigInteger = BigInteger.valueOf(30_000_000_000L)

    private const val SELECTOR_BALANCE = "0x70a08231"
    private const val SELECTOR_TRANSFER = "0xa9059cbb"

    data class Purse(
        val usdcE: Double,
        val usdc: Double,
        val pol: Double,
    ) {
        /** What can actually be sent, and which token it is. */
        val token: String get() = if (usdcE >= usdc) USDC_E else USDC
        val amount: Double get() = maxOf(usdcE, usdc)
    }

    fun purse(address: String): Purse = Purse(
        usdcE = tokenBalance(USDC_E, address),
        usdc = tokenBalance(USDC, address),
        pol = BigInteger(
            rpc("eth_getBalance", JSONArray().put(address).put("latest")).removePrefix("0x")
                .ifEmpty { "0" },
            16,
        ).toDouble() / 1e18,
    )

    fun tokenBalance(token: String, address: String): Double {
        val data = SELECTOR_BALANCE + address.removePrefix("0x").lowercase().padStart(64, '0')
        val hex = rpc(
            "eth_call",
            JSONArray()
                .put(JSONObject().put("to", token).put("data", data))
                .put("latest"),
        ).removePrefix("0x").ifEmpty { "0" }
        return BigInteger(hex, 16).toDouble() / DECIMALS
    }

    /**
     * Sends USDC to an address, and returns the transaction hash.
     *
     * The whole transaction is built here rather than handed to anything: the
     * only contract it can touch is the token, and the only method is
     * `transfer(to, amount)` with the two arguments this function was given.
     */
    fun sendUsdc(
        keys: Secp256k1.KeyPair,
        token: String,
        to: String,
        amountUsd: Double,
    ): String {
        require(BscApi.looksLikeAddress(to)) { "адрес не похож на адрес" }
        require(amountUsd > 0.0) { "сумма должна быть больше нуля" }

        val units = BigInteger.valueOf(Math.round(amountUsd * DECIMALS))
        require(units.signum() > 0) { "сумма меньше цента" }

        val from = keys.address
        val nonce = BigInteger(
            rpc("eth_getTransactionCount", JSONArray().put(from).put("pending"))
                .removePrefix("0x").ifEmpty { "0" },
            16,
        )

        val (maxFee, priority) = fees()

        val data = SELECTOR_TRANSFER +
            to.removePrefix("0x").lowercase().padStart(64, '0') +
            units.toString(16).padStart(64, '0')

        // EIP-1559: the payload is the same list the signature covers, with the
        // signature appended afterwards.
        val body = listOf(
            Rlp.number(CHAIN_ID),
            Rlp.number(nonce),
            Rlp.number(priority),
            Rlp.number(maxFee),
            Rlp.number(GAS_LIMIT),
            Rlp.bytes(Rlp.hexToBytes(token)),
            Rlp.number(BigInteger.ZERO),
            Rlp.bytes(Rlp.hexToBytes(data)),
            Rlp.list(emptyList()),
        )

        val unsigned = byteArrayOf(0x02) + Rlp.list(body)
        val signature = Secp256k1.sign(Secp256k1.keccak256(unsigned), keys)
        val yParity = (signature.v.toInt() - 27).toLong()

        val signed = byteArrayOf(0x02) + Rlp.list(
            body + listOf(
                Rlp.number(yParity),
                Rlp.number(BigInteger(1, signature.r)),
                Rlp.number(BigInteger(1, signature.s)),
            ),
        )

        return rpc("eth_sendRawTransaction", JSONArray().put(Rlp.toHex(signed)))
    }

    /**
     * What to pay for the block.
     *
     * Twice the base fee plus the tip: a base fee that doubles between reading
     * it and being mined is the ordinary case a transaction has to survive, and
     * anything not spent comes back.
     */
    private fun fees(): Pair<BigInteger, BigInteger> {
        val base = try {
            val block = JSONObject(
                rpcRaw("eth_getBlockByNumber", JSONArray().put("latest").put(false)),
            )
            BigInteger(block.optString("baseFeePerGas", "0x0").removePrefix("0x"), 16)
        } catch (e: Exception) {
            BigInteger.ZERO
        }
        val tip = try {
            BigInteger(
                rpc("eth_maxPriorityFeePerGas", JSONArray()).removePrefix("0x").ifEmpty { "0" },
                16,
            )
        } catch (e: Exception) {
            BigInteger.ZERO
        }
        val priority = tip.max(MIN_PRIORITY)
        return base.multiply(BigInteger.TWO).add(priority) to priority
    }

    /** One call, over whichever node answers, returning the `result` string. */
    private fun rpc(method: String, params: JSONArray): String =
        rpcRaw(method, params).let { it }

    private fun rpcRaw(method: String, params: JSONArray): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()

        var last: Exception? = null
        for (host in RPCS) {
            try {
                val json = JSONObject(
                    Http.postJson(host, body, mapOf("content-type" to "application/json")),
                )
                json.optJSONObject("error")?.let {
                    // A refusal from the chain is the chain's answer, not a
                    // broken node: asking another one gets the same refusal.
                    throw IllegalStateException(it.optString("message", "узел отказал"))
                }
                json.optJSONObject("result")?.let { return it.toString() }
                val result = json.opt("result")
                if (result != null && result != JSONObject.NULL) return result.toString()
                throw IllegalStateException("пустой ответ узла")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("сеть Polygon недоступна")
    }
}
