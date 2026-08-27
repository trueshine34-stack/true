package com.polybot.btc5m.bot

import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Binance's candles for BTC/USDT, kept live.
 *
 * One of these per interval: the five-minute series is the hours behind the
 * window, the one-minute series is the last hour close up. A window opening
 * into the fourth green candle of a run is not the same bet as one opening
 * into chop, and which of those it is depends on how close you look.
 *
 * History comes over REST once and the stream keeps it current: Binance pushes
 * the forming candle every couple of seconds and opens the next one itself, so
 * there is nothing to poll.
 */
class BinanceCandles(
    private val interval: String,
    /** How many candles the chart shows. */
    val limit: Int,
) {

    companion object {
        private const val REST = "https://data-api.binance.vision"
        private const val STREAM = "wss://data-stream.binance.vision"
        private const val SYMBOL = "btcusdt"

        private const val STALE_MS = 30_000L
        private const val RESYNC_SEC = 600L
        private const val MAX_BACKOFF_SEC = 20L

        /**
         * Four hours of context, and the last half hour close up.
         *
         * The close view is deliberately short: thirty candles across a phone
         * is a body wide enough to read one candle at a time, which is what
         * that chart is for. Everything the rules need from it — three closes
         * of momentum, ten minutes of volume — fits inside thirty with room.
         */
        val fiveMinute = BinanceCandles("5m", 48)
        val oneMinute = BinanceCandles("1m", 30)

        val all = listOf(fiveMinute, oneMinute)

        fun of(interval: String): BinanceCandles =
            all.firstOrNull { it.interval == interval } ?: fiveMinute
    }

    data class Candle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        /** Base volume, in BTC. What a move was actually traded on. */
        val volume: Double,
    )

    private val lock = Any()
    private val candles = TreeMap<Long, Candle>()

    private var touchedAt = 0L
    private var attempt = 0

    /** The candle the stream is currently filling in; REST leaves it alone. */
    private var streamTime = 0L

    @Volatile
    private var stopped = true

    @Volatile
    private var socket: WebSocket? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "binance-candles-$interval").apply { isDaemon = true }
    }

    fun start() {
        if (!stopped) return
        stopped = false
        scheduler.execute { history() }
        connect()
        scheduler.scheduleWithFixedDelay({ checkStall() }, 10, 10, TimeUnit.SECONDS)
        // The stream opens each new candle itself, so this is only a guard
        // against a slow drift away from what Binance would say.
        scheduler.scheduleWithFixedDelay({ history() }, RESYNC_SEC, RESYNC_SEC, TimeUnit.SECONDS)
    }

    fun stop() {
        stopped = true
        socket?.close(1000, null)
        socket = null
        synchronized(lock) { candles.clear() }
    }

    /**
     * How the last completed candle's volume compares with the ten before it.
     *
     * The candle in progress is deliberately skipped: a minute ten seconds old
     * has a tenth of its volume, and comparing that with whole minutes says
     * "no volume" about every move for fifty seconds out of sixty.
     */
    fun volumeRatio(over: Int = 10): Double {
        val rows = list()
        if (rows.size < over + 2) return 1.0
        val last = rows[rows.size - 2]
        val before = rows.subList(rows.size - 2 - over, rows.size - 2)
        val average = before.sumOf { it.volume } / over
        if (average <= 0.0) return 1.0
        return last.volume / average
    }

    /**
     * Where the last few closes have gone, in dollars.
     *
     * A lead that is being handed back shows up here before it shows up
     * anywhere else: the window can still be a hundred dollars up while the
     * last three minutes have all closed lower.
     */
    fun momentum(over: Int = 3): Double {
        val rows = list()
        if (rows.size < over + 1) return 0.0
        return rows.last().close - rows[rows.size - 1 - over].close
    }

    /** Oldest first — a chart is drawn left to right. */
    fun list(): List<Candle> = synchronized(lock) {
        candles.values.toList().takeLast(limit)
    }

    private fun history() {
        if (stopped) return
        val rows = try {
            JSONArray(
                Http.get("$REST/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit"),
            )
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val candle = Candle(
                    time = row.optLong(0) / 1000,
                    open = row.optString(1).toDoubleOrNull() ?: continue,
                    high = row.optString(2).toDoubleOrNull() ?: continue,
                    low = row.optString(3).toDoubleOrNull() ?: continue,
                    close = row.optString(4).toDoubleOrNull() ?: continue,
                    volume = row.optString(5).toDoubleOrNull() ?: 0.0,
                )
                // The stream's own view of the candle in progress is newer
                // than anything REST can say about it, so history fills in
                // around it rather than over it.
                if (candle.time == streamTime) continue
                candles[candle.time] = candle
            }
            trim()
        }
    }

    private fun checkStall() {
        if (stopped) return
        val age = System.currentTimeMillis() - touchedAt
        if (touchedAt > 0L && age > STALE_MS) {
            socket?.cancel()
            socket = null
            scheduleReconnect()
        }
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder()
            .url("$STREAM/ws/$SYMBOL@kline_$interval")
            .build()
        socket = Http.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== socket) return
                    val k = try {
                        JSONObject(text).optJSONObject("k")
                    } catch (e: Exception) {
                        null
                    } ?: return
                    absorb(k)
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
        scheduler.schedule({
            connect()
            history()
        }, delay, TimeUnit.SECONDS)
    }

    private fun absorb(k: JSONObject) {
        val time = k.optLong("t") / 1000
        if (time <= 0L) return
        val candle = Candle(
            time = time,
            open = k.optString("o").toDoubleOrNull() ?: return,
            high = k.optString("h").toDoubleOrNull() ?: return,
            low = k.optString("l").toDoubleOrNull() ?: return,
            close = k.optString("c").toDoubleOrNull() ?: return,
            volume = k.optString("v").toDoubleOrNull() ?: 0.0,
        )
        synchronized(lock) {
            candles[time] = candle
            streamTime = time
            trim()
            touchedAt = System.currentTimeMillis()
        }
    }

    private fun trim() {
        while (candles.size > limit * 2) candles.remove(candles.firstKey())
    }
}
