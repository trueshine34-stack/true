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
 * Binance's order book for BTC/USDT, kept locally and current to a tenth of a
 * second.
 *
 * The book is where the next few dollars of price are decided, and a depth
 * curve says what it would cost to move it — which is exactly the question a
 * five-minute Up or Down is about. Twenty levels either side of the mid on
 * BTC/USDT is about a dollar of range and draws as a vertical wall, so this
 * keeps the real book instead: one REST snapshot, then the diff stream applied
 * to it every hundred milliseconds, which is the same thing their own chart
 * does and the only way to have both depth and speed.
 *
 * The data-only mirror is used for both, for the same reason as everywhere
 * else here: api.binance.com refuses whole countries and answers a refusal
 * with a 200.
 */
object BinanceBook {

    private const val REST = "https://data-api.binance.vision"
    private const val STREAM = "wss://data-stream.binance.vision"

    /** How far either side of the mid the curve reaches: eight hundredths of a percent. */
    const val SPAN = 0.0008

    /** Buckets per side. Sixty across sixty-odd dollars is a dollar a step. */
    const val BUCKETS = 60

    private const val SNAPSHOT_LIMIT = 1000
    private const val STALE_MS = 10_000L
    private const val MAX_BACKOFF_SEC = 20L

    data class Depth(
        val bid: Double,
        val ask: Double,
        val at: Long,
        /** Size in each bucket walking away from the mid, nearest first. */
        val bids: DoubleArray,
        val asks: DoubleArray,
    )

    private val lock = Any()
    private val bids = TreeMap<Double, Double>(reverseOrder())
    private val asks = TreeMap<Double, Double>()

    /** Events that arrived before the snapshot they have to be applied on top of. */
    private val pending = ArrayList<JSONObject>()

    private var lastUpdateId = 0L
    private var synced = false
    private var touchedAt = 0L
    private var attempt = 0

    @Volatile
    private var stopped = true

    /** Whether the stall guard is already scheduled. */
    private var watching = false

    @Volatile
    private var socket: WebSocket? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "binance-book").apply { isDaemon = true }
    }

    fun start() {
        if (!stopped) return
        stopped = false
        connect()
        // Once per process: the guard outlives any one coin's socket.
        if (!watching) {
            watching = true
            scheduler.scheduleWithFixedDelay({ checkStall() }, 5, 5, TimeUnit.SECONDS)
        }
    }

    /** Follow the desk onto another coin, book and all. */
    fun switchCoin() {
        val wasRunning = !stopped
        stop()
        if (wasRunning) start()
    }

    fun stop() {
        stopped = true
        socket?.close(1000, null)
        socket = null
        synchronized(lock) {
            reset()
            touchedAt = 0L
        }
    }

    /**
     * The book as a curve: what is bid and offered in each step of price away
     * from the mid.
     *
     * Buckets rather than levels because the book has thousands of them and
     * the screen has a few hundred pixels; summing here also means the bridge
     * carries a hundred and twenty numbers a frame instead of four thousand.
     */
    fun depth(): Depth? = synchronized(lock) {
        if (!synced) return null
        val bestBid = bids.firstEntry()?.key ?: return null
        val bestAsk = asks.firstEntry()?.key ?: return null
        val mid = (bestBid + bestAsk) / 2
        if (mid <= 0.0) return null

        val step = mid * SPAN / BUCKETS
        val bidSide = DoubleArray(BUCKETS)
        val askSide = DoubleArray(BUCKETS)

        for ((price, qty) in bids) {
            val away = mid - price
            if (away < 0) continue
            val i = (away / step).toInt()
            if (i >= BUCKETS) break // sorted downwards: everything after is further
            bidSide[i] += qty
        }
        for ((price, qty) in asks) {
            val away = price - mid
            if (away < 0) continue
            val i = (away / step).toInt()
            if (i >= BUCKETS) break
            askSide[i] += qty
        }
        return Depth(bestBid, bestAsk, touchedAt, bidSide, askSide)
    }

    private fun reset() {
        bids.clear()
        asks.clear()
        pending.clear()
        lastUpdateId = 0L
        synced = false
    }

    /**
     * A book that stops being updated is worse than no book: it looks live and
     * is not. Silence past the threshold tears the socket down and starts over.
     */
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
        synchronized(lock) { reset() }

        val request = Request.Builder()
            .url("$STREAM/ws/${Coins.current.stream}@depth@100ms")
            .build()
        socket = Http.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    // The snapshot is fetched after the stream is open, so that
                    // every event that could have been missed is in the buffer
                    // waiting rather than lost.
                    scheduler.execute { snapshot(webSocket) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== socket) return
                    val event = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        return
                    }
                    handle(event)
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

    private fun snapshot(owner: WebSocket) {
        if (stopped) return
        val json = try {
            JSONObject(
                Http.get(
                    "$REST/api/v3/depth?symbol=${Coins.current.pair}" +
                        "&limit=$SNAPSHOT_LIMIT",
                ),
            )
        } catch (e: Exception) {
            scheduleReconnect()
            return
        }
        if (owner !== socket) return

        synchronized(lock) {
            bids.clear()
            asks.clear()
            seed(json.optJSONArray("bids"), bids)
            seed(json.optJSONArray("asks"), asks)
            lastUpdateId = json.optLong("lastUpdateId")

            // Everything that arrived while the snapshot was in flight, minus
            // what the snapshot already contains.
            val queued = pending.toList()
            pending.clear()
            synced = true
            touchedAt = System.currentTimeMillis()
            queued.forEach { apply(it) }
        }
    }

    private fun seed(rows: JSONArray?, into: TreeMap<Double, Double>) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            if (qty > 0.0) into[price] = qty
        }
    }

    private fun handle(event: JSONObject) {
        synchronized(lock) {
            if (!synced) {
                // Bounded: a snapshot that never lands must not eat memory.
                if (pending.size < 2_000) pending.add(event)
                return
            }
            apply(event)
        }
    }

    /**
     * One diff, in order.
     *
     * Binance numbers every change, and a gap means the local book has silently
     * stopped matching theirs — which shows up as a depth curve that is subtly
     * wrong rather than as an error, so a gap resyncs from scratch.
     */
    private fun apply(event: JSONObject) {
        val first = event.optLong("U")
        val final = event.optLong("u")
        if (final <= lastUpdateId) return
        if (lastUpdateId > 0L && first > lastUpdateId + 1) {
            synced = false
            scheduler.execute { socket?.let { snapshot(it) } }
            return
        }

        merge(event.optJSONArray("b"), bids)
        merge(event.optJSONArray("a"), asks)
        lastUpdateId = final
        touchedAt = System.currentTimeMillis()
    }

    /** A level quoted at zero is a level that is gone, not a level of nothing. */
    private fun merge(rows: JSONArray?, into: TreeMap<Double, Double>) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            if (qty > 0.0) into[price] = qty else into.remove(price)
        }
    }
}
