package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chainlink BTC/USD relayed by Polymarket's Real-Time Data Service.
 *
 * This is the stream the 5-minute markets settle against, so the strike
 * recorded here is the strike the resolver will use. Running it on OkHttp
 * inside the service — rather than in the WebView — is what keeps it alive
 * while the app is in the background.
 *
 * The service treats a subscription carrying `filters` as a one-shot history
 * request: it replays the recent window and then never sends another frame on
 * that socket, even for topics that were subscribed without filters. So the
 * live socket subscribes to whole topics and filters by symbol here, and the
 * backfill is fetched separately on its own short-lived connection.
 */
class ChainlinkFeed(
    private val symbol: String = "btc/usd",
    private val spotSymbol: String = "btcusdt",
) {

    enum class Status { CONNECTING, LIVE, STALLED, CLOSED }

    @Volatile
    var status: Status = Status.CLOSED
        private set

    /** Latest Chainlink TWAP tick — the number the market resolves against. */
    @Volatile
    var last: Tick? = null
        private set

    /** Latest spot tick, carried for display only; never feeds the model. */
    @Volatile
    var spot: Tick? = null
        private set

    private val history = CopyOnWriteArrayList<Tick>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "chainlink-feed").apply { isDaemon = true }
    }

    private var socket: WebSocket? = null
    private var attempt = 0
    @Volatile
    private var stopped = true
    @Volatile
    private var backfillInFlight = false

    private companion object {
        const val MAX_HISTORY = 1200
        const val STALL_AFTER_MS = 15_000L
        const val RECONNECT_AFTER_MS = 40_000L
        const val MAX_BACKOFF_SEC = 30L
        const val CHAINLINK_TOPIC = "crypto_prices_chainlink"
        const val SPOT_TOPIC = "crypto_prices"
    }

    fun start() {
        if (!stopped) return
        stopped = false
        connect()
        backfill()
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

    /**
     * A silent socket looks identical to a healthy idle one, so age of the last
     * tick is the only honest liveness signal. Past the stall threshold we mark
     * it, and past the reconnect threshold we tear the socket down rather than
     * waiting for a close that may never come.
     */
    private fun checkStall() {
        if (stopped) return
        val age = last?.let { System.currentTimeMillis() - it.timestamp } ?: Long.MAX_VALUE
        if (status == Status.LIVE && age > STALL_AFTER_MS) status = Status.STALLED
        if (status != Status.CONNECTING && age > RECONNECT_AFTER_MS) {
            socket?.cancel()
            socket = null
            scheduleReconnect()
        }
    }

    private fun subscription(topic: String): JSONObject =
        JSONObject().put("topic", topic).put("type", "*")

    private fun connect() {
        if (stopped) return
        status = Status.CONNECTING

        val request = Request.Builder().url(Endpoints.RTDS).build()
        socket = Http.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    val message = JSONObject()
                        .put("action", "subscribe")
                        .put(
                            "subscriptions",
                            JSONArray()
                                .put(subscription(CHAINLINK_TOPIC))
                                .put(subscription(SPOT_TOPIC)),
                        )
                    webSocket.send(message.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket === socket) scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket === socket) scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (stopped) return
        status = Status.CONNECTING
        val delay = minOf(1L shl attempt, MAX_BACKOFF_SEC)
        attempt = minOf(attempt + 1, 5)
        scheduler.schedule({
            connect()
            backfill()
        }, delay, TimeUnit.SECONDS)
    }

    /**
     * Seeds recent history on its own connection. A filtered subscription gets
     * one replay batch and is then dropped by the service, which is exactly the
     * shape we want here — and exactly why the live socket must not use one.
     */
    private fun backfill() {
        if (stopped || backfillInFlight) return
        backfillInFlight = true

        val request = Request.Builder().url(Endpoints.RTDS).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val message = JSONObject()
                    .put("action", "subscribe")
                    .put(
                        "subscriptions",
                        JSONArray().put(
                            subscription(CHAINLINK_TOPIC)
                                .put("filters", "{\"symbol\":\"$symbol\"}"),
                        ),
                    )
                webSocket.send(message.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val batch = try {
                    JSONObject(text).optJSONObject("payload")?.optJSONArray("data")
                } catch (e: Exception) {
                    null
                } ?: return

                seed(batch)
                backfillInFlight = false
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                backfillInFlight = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                backfillInFlight = false
            }
        }
        Http.client.newWebSocket(request, listener)

        // The service simply stops answering a filtered subscription it does not
        // like, so release the latch on a timer as well as on close.
        scheduler.schedule({ backfillInFlight = false }, 20, TimeUnit.SECONDS)
    }

    /**
     * Routes one live frame. The live socket carries every symbol on the topic,
     * so this is where BTC is picked out and everything else dropped.
     */
    internal fun handleMessage(text: String) {
        if (text.isEmpty() || text == "PONG" || text == "PING") return

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val payload = json.optJSONObject("payload") ?: return

        val itemSymbol = payload.optString("symbol")
        val timestamp = payload.optLong("timestamp", 0L)
        val value = payload.optDouble("value", Double.NaN)
        if (timestamp <= 0L || value.isNaN()) return

        when (itemSymbol) {
            symbol -> push(Tick(timestamp, value), live = true)
            spotSymbol -> spot = Tick(timestamp, value)
        }
    }

    /**
     * Backfill and live ticks land on different sockets, so a replayed batch can
     * arrive after newer live ticks are already in. Insert by timestamp instead
     * of appending, or seeding history would silently drop the whole batch.
     */
    /** Absorbs one replayed history batch. */
    internal fun seed(batch: JSONArray) {
        for (i in 0 until batch.length()) {
            val item = batch.optJSONObject(i) ?: continue
            push(Tick(item.optLong("timestamp"), item.optDouble("value")), live = false)
        }
    }

    private fun push(tick: Tick, live: Boolean) {
        if (tick.timestamp <= 0L || tick.value <= 0.0) return

        synchronized(history) {
            val previous = history.lastOrNull()
            when {
                previous == null || tick.timestamp > previous.timestamp -> history.add(tick)
                else -> {
                    val at = history.indexOfFirst { it.timestamp >= tick.timestamp }
                    if (at < 0 || history[at].timestamp == tick.timestamp) return
                    history.add(at, tick)
                }
            }
            while (history.size > MAX_HISTORY) history.removeAt(0)
        }

        val newest = last
        if (newest == null || tick.timestamp >= newest.timestamp) last = tick
        if (live) status = Status.LIVE
    }
}
