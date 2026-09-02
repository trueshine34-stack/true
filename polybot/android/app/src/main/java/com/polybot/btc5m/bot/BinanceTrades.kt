package com.polybot.btc5m.bot

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Every trade on BTC/USDT, as it happens.
 *
 * Binance's candle stream pushes the forming candle about every two seconds,
 * which is fine for a chart of the last four hours and slow for the right-hand
 * edge of a five-minute bet. The trade stream has no interval at all — it
 * carries each trade as it prints — so the candle in progress is kept current
 * from here and the kline frames are left to be the authority on everything
 * else: the open, the volume, and the final word when the candle closes.
 */
object BinanceTrades {

    private const val STREAM = "wss://data-stream.binance.vision"
    private const val STALE_MS = 60_000L
    private const val MAX_BACKOFF_SEC = 20L

    /** The last print, for anything that wants the price and not a candle. */
    @Volatile
    var last: Double = 0.0
        private set

    @Volatile
    private var stopped = true

    /** Whether the stall guard is already scheduled. */
    private var watching = false

    @Volatile
    private var socket: WebSocket? = null

    private var touchedAt = 0L
    private var attempt = 0

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "binance-trades").apply { isDaemon = true }
    }

    fun start() {
        if (!stopped) return
        stopped = false
        connect()
        // Scheduled once per process; a coin switch restarts the socket, not
        // the guard that watches it.
        if (!watching) {
            watching = true
            scheduler.scheduleWithFixedDelay({ checkStall() }, 15, 15, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        stopped = true
        socket?.close(1000, null)
        socket = null
        // The last print belonged to the coin that just went away.
        last = 0.0
        touchedAt = 0L
    }

    /** Follow the desk onto another coin. */
    fun switchCoin() {
        val wasRunning = !stopped
        stop()
        if (wasRunning) start()
    }

    /**
     * A market with no trades for a minute is a market that has gone quiet, or
     * a socket that has died, and only one of those is worth waiting through.
     * Bitcoin prints several times a second, so silence this long is the socket.
     */
    private fun checkStall() {
        if (stopped) return
        if (touchedAt > 0L && System.currentTimeMillis() - touchedAt > STALE_MS) {
            socket?.cancel()
            socket = null
            scheduleReconnect()
        }
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder()
            .url("$STREAM/ws/${Coins.current.stream}@aggTrade")
            .build()
        socket = Http.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== socket) return
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        return
                    }
                    val price = json.optString("p").toDoubleOrNull() ?: return
                    val at = json.optLong("T").takeIf { it > 0L } ?: return
                    if (price <= 0.0) return

                    last = price
                    touchedAt = System.currentTimeMillis()
                    BinanceCandles.all.forEach { it.applyTrade(price, at / 1000) }
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
        val delay = minOf(1L shl attempt, MAX_BACKOFF_SEC)
        attempt = minOf(attempt + 1, 4)
        scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }
}
