package com.polybot.btc5m.bot

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(message: String, val status: Int = 0) : IOException(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // The RTDS socket must survive an idle stretch between price ticks.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build(), url)
    }

    fun postJson(url: String, body: String, headers: Map<String, String>): String {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build(), url)
    }

    private fun execute(request: Request, url: String): String {
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException("${response.code} $url: ${text.take(300)}", response.code)
            }
            return text
        }
    }
}

/**
 * The exchange checks the timestamp inside every signed request against its own
 * clock, so signing against a phone whose clock has drifted fails everywhere at
 * once. Hold the offset instead of trusting the device.
 */
object Clock {
    @Volatile
    private var offsetSec: Long = 0

    fun nowSec(): Long = System.currentTimeMillis() / 1000 + offsetSec

    fun offset(): Long = offsetSec

    fun sync() {
        val body = Http.get("${Endpoints.CLOB}/time").trim()
        val server = body.trim('"').toLongOrNull() ?: return
        offsetSec = server - System.currentTimeMillis() / 1000
    }
}

object ClobApi {

    data class Level(val price: Double, val size: Double)
    data class Book(val bids: List<Level>, val asks: List<Level>)

    fun getBook(tokenId: String): Book {
        val json = JSONObject(Http.get("${Endpoints.CLOB}/book?token_id=$tokenId"))
        return Book(parseLevels(json.optJSONArray("bids")), parseLevels(json.optJSONArray("asks")))
    }

    private fun parseLevels(array: JSONArray?): List<Level> {
        if (array == null) return emptyList()
        val out = ArrayList<Level>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val price = o.optString("price").toDoubleOrNull() ?: continue
            val size = o.optString("size").toDoubleOrNull() ?: continue
            out.add(Level(price, size))
        }
        return out
    }

    /**
     * Price at which a marketable order of `amount` fully fills, walking the
     * book inward from the touch. Returns null when the book is too thin for a
     * fill-or-kill order, which would be rejected anyway.
     */
    fun marketablePrice(book: Book, side: String, amount: Double): Double? {
        val levels = if (side == "BUY") {
            book.asks.sortedBy { it.price }
        } else {
            book.bids.sortedByDescending { it.price }
        }
        if (levels.isEmpty()) return null

        var total = 0.0
        for (level in levels) {
            total += if (side == "BUY") level.size * level.price else level.size
            if (total >= amount) return level.price
        }
        return null
    }

    /** Winning outcome once the market has resolved, or null while it is open. */
    fun resolvedWinner(conditionId: String): String? {
        val json = JSONObject(Http.get("${Endpoints.CLOB}/markets/$conditionId"))
        val tokens = json.optJSONArray("tokens") ?: return null
        for (i in 0 until tokens.length()) {
            val t = tokens.getJSONObject(i)
            if (t.optBoolean("winner")) return t.optString("outcome")
        }
        return null
    }

    /** USDC the funder can actually spend, in whole dollars. */
    fun usdcBalance(
        creds: Credentials,
        signerAddress: String,
        signatureType: SignatureType,
    ): Double {
        val path = "/balance-allowance"
        val ts = Clock.nowSec()
        val signature = Signing.hmacSignature(creds.secret, "${ts}GET$path")
        val headers = mapOf(
            "POLY_ADDRESS" to signerAddress,
            "POLY_SIGNATURE" to signature,
            "POLY_TIMESTAMP" to ts.toString(),
            "POLY_API_KEY" to creds.apiKey,
            "POLY_PASSPHRASE" to creds.passphrase,
        )
        val url = "${Endpoints.CLOB}$path?signature_type=${signatureType.value}" +
            "&asset_type=COLLATERAL"
        val json = JSONObject(Http.get(url, headers))
        return (json.optString("balance").toDoubleOrNull() ?: 0.0) / 1_000_000.0
    }

    data class OrderResult(
        val success: Boolean,
        val orderId: String?,
        val status: String?,
        val makingAmount: Double?,
        val takingAmount: Double?,
        val error: String?,
    )

    fun postOrder(
        order: Orders.SignedOrder,
        creds: Credentials,
        signerAddress: String,
        orderType: String = "FOK",
    ): OrderResult {
        // The L2 signature covers the exact bytes on the wire, so the payload is
        // built as a string once and reused for both the HMAC and the request.
        val payload = buildString {
            append("{\"order\":{")
            append("\"salt\":").append(order.salt).append(',')
            append("\"maker\":\"").append(order.maker).append("\",")
            append("\"signer\":\"").append(order.signer).append("\",")
            append("\"tokenId\":\"").append(order.tokenId).append("\",")
            append("\"makerAmount\":\"").append(order.makerAmount).append("\",")
            append("\"takerAmount\":\"").append(order.takerAmount).append("\",")
            append("\"side\":\"").append(order.side).append("\",")
            append("\"expiration\":\"").append(order.expiration).append("\",")
            append("\"signatureType\":").append(order.signatureType).append(',')
            append("\"timestamp\":\"").append(order.timestamp).append("\",")
            append("\"metadata\":\"").append(order.metadata).append("\",")
            append("\"builder\":\"").append(order.builder).append("\",")
            append("\"signature\":\"").append(order.signature).append("\"},")
            append("\"owner\":\"").append(creds.apiKey).append("\",")
            append("\"orderType\":\"").append(orderType).append("\",")
            append("\"deferExec\":false,")
            append("\"postOnly\":false}")
        }

        val ts = Clock.nowSec()
        val signature = Signing.hmacSignature(creds.secret, "${ts}POST/order$payload")
        val headers = mapOf(
            "POLY_ADDRESS" to signerAddress,
            "POLY_SIGNATURE" to signature,
            "POLY_TIMESTAMP" to ts.toString(),
            "POLY_API_KEY" to creds.apiKey,
            "POLY_PASSPHRASE" to creds.passphrase,
        )

        val text = Http.postJson("${Endpoints.CLOB}/order", payload, headers)
        val json = JSONObject(text)
        val error = json.optString("errorMsg").ifEmpty { null }
        return OrderResult(
            success = json.optBoolean("success", error == null),
            orderId = json.optString("orderID").ifEmpty { null },
            status = json.optString("status").ifEmpty { null },
            makingAmount = json.optString("makingAmount").toDoubleOrNull(),
            takingAmount = json.optString("takingAmount").toDoubleOrNull(),
            error = error,
        )
    }
}

object GammaApi {

    fun windowStartFor(atMs: Long = System.currentTimeMillis()): Long =
        (atMs / 1000 / WINDOW_SECONDS) * WINDOW_SECONDS

    /**
     * Each window is its own event under a fully deterministic slug, so the
     * market can be addressed directly instead of paging a listing that filters
     * these markets out.
     */
    fun marketForWindow(windowStart: Long): Market? {
        val slug = "btc-updown-5m-$windowStart"
        val events = JSONArray(Http.get("${Endpoints.GAMMA}/events?slug=$slug"))
        if (events.length() == 0) return null

        val event = events.getJSONObject(0)
        val markets = event.optJSONArray("markets") ?: return null
        if (markets.length() == 0) return null
        val market = markets.getJSONObject(0)

        val outcomes = parseStringArray(market.optString("outcomes"))
        val tokenIds = parseStringArray(market.optString("clobTokenIds"))
        if (outcomes.size < 2 || tokenIds.size < 2) return null

        val upIndex = outcomes.indexOfFirst { it.equals("Up", ignoreCase = true) }
        val downIndex = outcomes.indexOfFirst { it.equals("Down", ignoreCase = true) }
        if (upIndex < 0 || downIndex < 0) return null

        val accepting = market.optBoolean("acceptingOrders", true) &&
            market.optBoolean("enableOrderBook", true) &&
            !market.optBoolean("closed", false)

        return Market(
            slug = event.optString("slug", slug),
            conditionId = market.optString("conditionId"),
            question = market.optString("question", event.optString("title")),
            windowStart = windowStart,
            windowEnd = windowStart + WINDOW_SECONDS,
            negRisk = market.optBoolean("negRisk", false),
            tickSize = market.optDouble("orderPriceMinTickSize", 0.01),
            minimumOrderSize = market.optDouble("orderMinSize", 5.0),
            acceptingOrders = accepting,
            up = Outcome("Up", tokenIds[upIndex]),
            down = Outcome("Down", tokenIds[downIndex]),
        )
    }

    private fun parseStringArray(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
