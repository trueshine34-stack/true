package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Chainlink BTC/USD relayed by Polymarket's Real-Time Data Service.
 *
 * This is the stream the 5-minute markets settle against, so the strike
 * recorded here is the strike the resolver will use. Running it on OkHttp
 * inside the service — rather than in the WebView — is what keeps it alive
 * while the app is in the background.
 */
class ChainlinkFeed(private val symbol: String = "btc/usd") {

    enum class Status { CONNECTING, LIVE, STALLED, CLOSED }

    @Volatile
    var status: Status = Status.CLOSED
        private set

    @Volatile
    var last: Tick? = null
        private set

    private val history = CopyOnWriteArrayList<Tick>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "chainlink-feed").apply { isDaemon = true }
    }

    private var socket: WebSocket? = null
    private var attempt = 0
    @Volatile
    private var stopped = true

    private companion object {
        const val MAX_HISTORY = 1200
        const val STALL_AFTER_MS = 15_000L
        const val MAX_BACKOFF_SEC = 30L
    }

    fun start() {
        if (!stopped) return
        stopped = false
        connect()
        scheduler.scheduleWithFixedDelay({ checkStall() }, 2, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        stopped = true
        socket?.close(1000, null)
        socket = null
        status = Status.CLOSED
    }

    fun ticksBetween(fromMs: Long, toMs: Long): List<Tick> =
        history.filter { it.timestamp in fromMs..toMs }

    fun firstTickAtOrAfter(atMs: Long): Tick? = history.firstOrNull { it.timestamp >= atMs }

    private fun checkStall() {
        if (stopped) return
        val age = last?.let { System.currentTimeMillis() - it.timestamp } ?: Long.MAX_VALUE
        if (status == Status.LIVE && age > STALL_AFTER_MS) status = Status.STALLED
    }

    private fun connect() {
        if (stopped) return
        status = Status.CONNECTING

        val request = Request.Builder().url(Endpoints.RTDS).build()
        socket = Http.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    val subscription = JSONObject()
                        .put("action", "subscribe")
                        .put(
                            "subscriptions",
                            org.json.JSONArray().put(
                                JSONObject()
                                    .put("topic", "crypto_prices_chainlink")
                                    .put("type", "*")
                                    .put("filters", "{\"symbol\":\"$symbol\"}"),
                            ),
                        )
                    webSocket.send(subscription.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (stopped) return
        status = Status.CONNECTING
        val delay = minOf(1L shl attempt, MAX_BACKOFF_SEC)
        attempt = minOf(attempt + 1, 5)
        scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    private fun handleMessage(text: String) {
        if (text.isEmpty() || text == "PONG" || text == "PING") return

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val payload = json.optJSONObject("payload") ?: return

        // On subscribe the service replays recent history in one batch before
        // switching to per-second updates.
        val backfill = payload.optJSONArray("data")
        if (backfill != null) {
            for (i in 0 until backfill.length()) {
                val item = backfill.optJSONObject(i) ?: continue
                push(Tick(item.optLong("timestamp"), item.optDouble("value")), live = false)
            }
            return
        }

        val itemSymbol = payload.optString("symbol")
        if (itemSymbol.isNotEmpty() && itemSymbol != symbol) return

        val timestamp = payload.optLong("timestamp", 0L)
        val value = payload.optDouble("value", Double.NaN)
        if (timestamp <= 0L || value.isNaN()) return

        push(Tick(timestamp, value), live = true)
    }

    private fun push(tick: Tick, live: Boolean) {
        if (tick.timestamp <= 0L || tick.value <= 0.0) return

        val previous = history.lastOrNull()
        if (previous != null && tick.timestamp <= previous.timestamp) {
            // Duplicate or out-of-order replay; keep the series monotonic.
            return
        }

        history.add(tick)
        while (history.size > MAX_HISTORY) history.removeAt(0)
        last = tick
        if (live) status = Status.LIVE
    }
}
