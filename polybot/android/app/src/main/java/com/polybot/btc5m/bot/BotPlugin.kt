package com.polybot.btc5m.bot

import android.Manifest
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Bridge between the WebView UI and the trading service.
 *
 * The UI never trades; it unlocks the key, hands it to the service, and then
 * only observes. That split is what lets the bot keep running once the WebView
 * is suspended.
 */
@CapacitorPlugin(
    name = "PolyBot",
    permissions = [
        Permission(
            alias = BotPlugin.NOTIFICATIONS,
            strings = [Manifest.permission.POST_NOTIFICATIONS],
        ),
    ],
)
class BotPlugin : Plugin() {

    companion object {
        const val NOTIFICATIONS = "notifications"
    }

    /** The engine outlives the service, so it is always available here. */
    private val engine: BotEngine get() = EngineHolder.get(context)

    /** Standing sell rule for the manual desk. */
    private val autoSell: AutoSell get() = EngineHolder.autoSell(context)

    /** The bot that buys the favourite while it is under its own exit. */
    private val pulseBot: PulseBot get() = EngineHolder.pulse(context)

    private val takeBot: TakeBot get() = EngineHolder.taker(context)

    private val probeBot: ProbeBot get() = EngineHolder.probe(context)

    override fun load() {
        EngineHolder.onState = { notifyState() }
        EngineHolder.onLogEntry = { entry -> notifyLog(entry) }
        // What the app has measured about the venue's own delays outlives the
        // process; re-measuring on every launch would mean never having a
        // number when it is wanted.
        Timings.store = object : Timings.Store {
            private val prefs = context.applicationContext
                .getSharedPreferences("polybot_timings", android.content.Context.MODE_PRIVATE)

            override fun read(key: String): String? = prefs.getString(key, null)
            override fun write(key: String, value: String) {
                prefs.edit().putString(key, value).apply()
            }
        }
    }

    override fun handleOnDestroy() {
        EngineHolder.onState = null
        EngineHolder.onLogEntry = null
        super.handleOnDestroy()
    }

    /**
     * Mint the wallet's CLOB credentials and stage everything the service needs.
     *
     * This runs natively rather than in the WebView on purpose: the WebView's
     * fetch reports every transport failure as an opaque "Failed to fetch",
     * which hides whether the exchange is unreachable, the TLS handshake was
     * reset, or the request was simply refused.
     */
    @PluginMethod
    fun connect(call: PluginCall) {
        val privateKey = call.getString("privateKey")
        if (privateKey.isNullOrEmpty()) {
            call.reject("Нужен приватный ключ")
            return
        }
        val signatureType = SignatureType.from(call.getInt("signatureType") ?: 0)

        Thread {
            try {
                val keyPair = Secp256k1.keyPairFromPrivateKey(privateKey.trim())
                val funder = call.getString("funderAddress")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: keyPair.address

                try {
                    Clock.sync()
                } catch (e: Exception) {
                    // Not fatal on its own; the signature check below will fail
                    // loudly if the drift actually matters.
                }

                val creds = ClobApi.createOrDeriveApiCreds(keyPair)

                engine.configure(
                    Account(
                        privateKey = privateKey.trim(),
                        signerAddress = keyPair.address,
                        funderAddress = funder,
                        signatureType = signatureType,
                    ),
                    creds,
                )

                val result = JSObject()
                    .put("address", keyPair.address)
                    .put("clockOffsetSec", Clock.offset())
                // A balance read is the cheapest proof that signer, funder and
                // wallet type line up; a mismatch shows here, not on the first
                // live order.
                try {
                    result.put(
                        "usdc",
                        ClobApi.usdcBalance(creds, keyPair.address, signatureType),
                    )
                } catch (e: Exception) {
                    result.put("balanceError", e.message)
                }
                call.resolve(result)
            } catch (e: Exception) {
                // The venue's own words, in words: a rejected signature and a
                // wrong wallet type look identical in the raw 400.
                call.reject(ClobApi.humanError(e.message ?: "", 0), e)
            }
        }.start()
    }

    /**
     * Reachability probe. Polymarket is geo-restricted and commonly blocked at
     * the ISP, which is indistinguishable from a bug unless the real transport
     * error is surfaced.
     */
    @PluginMethod
    fun diagnose(call: PluginCall) {
        Thread {
            val checks = listOf(
                // The OS's own captive-portal probe, as a control: if this
                // fails too, the phone has no working connection at all.
                Triple("Интернет", "https://connectivitycheck.gstatic.com/generate_204", true),
                Triple("CLOB Polymarket", "${Endpoints.CLOB}/time", false),
                Triple("Gamma Polymarket", "${Endpoints.GAMMA}/events?slug=btc-updown-5m-0", false),
            )

            val results = JSArray()
            for ((name, url, isControl) in checks) {
                val started = System.currentTimeMillis()
                val entry = JSObject().put("name", name).put("control", isControl)
                try {
                    Http.get(url)
                    entry.put("ok", true)
                } catch (e: Exception) {
                    entry.put("ok", false)
                    entry.put("error", "${e.javaClass.simpleName}: ${e.message}")
                }
                entry.put("ms", System.currentTimeMillis() - started)
                results.put(entry)
            }
            call.resolve(JSObject().put("checks", results))
        }.start()
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (!engine.isConfigured()) {
            call.reject("Сначала подключите кошелёк")
            return
        }
        // Android 13+ hides the foreground notification without this grant, and
        // a bot trading real money with no visible indicator is not acceptable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState(NOTIFICATIONS) != com.getcapacitor.PermissionState.GRANTED
        ) {
            requestPermissionForAlias(NOTIFICATIONS, call, "afterNotificationsPermission")
            return
        }
        launchService(call)
    }

    @PermissionCallback
    private fun afterNotificationsPermission(call: PluginCall) {
        // Proceed either way: a suppressed notification is worse than nothing,
        // but refusing to trade because of it would surprise the user more.
        launchService(call)
    }

    private fun launchService(call: PluginCall) {
        BotService.start(context)
        // The service starts asynchronously; the engine reports the real state
        // through the state listener a moment later.
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        BotService.stop(context)
        call.resolve()
        notifyState()
    }

    @PluginMethod
    fun getBalance(call: PluginCall) {
        Thread {
            try {
                val usdc = engine.usdcBalance()
                // Every reading is also the baseline the next sale is timed
                // against, so the desk's own poll feeds the checker too.
                Timings.balanceRead(usdc, System.currentTimeMillis())
                call.resolve(JSObject().put("usdc", usdc))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось прочитать баланс")
            }
        }.start()
    }

    @PluginMethod
    fun getOpenOrders(call: PluginCall) {
        val market = call.getString("market")
        Thread {
            try {
                val orders = JSArray()
                engine.openOrders(market).forEach {
                    orders.put(
                        JSObject()
                            .put("id", it.id)
                            .put("status", it.status)
                            .put("market", it.market)
                            .put("assetId", it.assetId)
                            .put("side", it.side)
                            .put("price", it.price)
                            .put("originalSize", it.originalSize)
                            .put("sizeMatched", it.sizeMatched)
                            .put("remaining", it.remaining)
                            .put("outcome", it.outcome),
                    )
                }
                call.resolve(JSObject().put("orders", orders))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось прочитать ордера")
            }
        }.start()
    }

    /** The window's market, so the UI can offer Up/Down without guessing ids. */
    @PluginMethod
    fun getCurrentMarket(call: PluginCall) {
        Thread {
            val market = engine.currentMarket()
            if (market == null) {
                call.reject("Рынок текущего окна не найден")
                return@Thread
            }
            call.resolve(marketJson(market))
        }.start()
    }

    private fun marketJson(market: Market): JSObject = JSObject()
        .put("conditionId", market.conditionId)
        .put("question", market.question)
        .put("upTokenId", market.up.tokenId)
        .put("downTokenId", market.down.tokenId)
        .put("tickSize", market.tickSize)
        .put("minimumOrderSize", market.minimumOrderSize)
        .put("windowStart", market.windowStart)
        .put("windowEnd", market.windowEnd)

    @PluginMethod
    fun placeOrder(call: PluginCall) {
        val tokenId = call.getString("tokenId")
        val conditionId = call.getString("conditionId")
        val side = call.getString("side")?.uppercase()
        val price = call.getDouble("price")
        val size = call.getDouble("size")
        val orderType = call.getString("orderType") ?: "GTC"

        if (tokenId.isNullOrEmpty() || conditionId.isNullOrEmpty() ||
            side !in setOf("BUY", "SELL") || price == null || size == null
        ) {
            call.reject("Нужны рынок, сторона, цена и размер")
            return
        }

        Thread {
            try {
                val result = engine.placeManualOrder(
                    tokenId, conditionId, side!!, price, size, orderType,
                )
                call.resolve(
                    JSObject()
                        .put("success", result.success)
                        .put("orderId", result.orderId)
                        .put("status", result.status)
                        .put("error", result.error),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось выставить ордер")
            }
        }.start()
    }

    @PluginMethod
    fun cancelOrder(call: PluginCall) {
        val orderId = call.getString("orderId")
        if (orderId.isNullOrEmpty()) {
            call.reject("Нужен идентификатор ордера")
            return
        }
        Thread {
            try {
                call.resolve(JSObject().put("cancelled", engine.cancelOrder(orderId)))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось отменить ордер")
            }
        }.start()
    }

    @PluginMethod
    fun cancelMarketOrders(call: PluginCall) {
        val conditionId = call.getString("conditionId")
        if (conditionId.isNullOrEmpty()) {
            call.reject("Нужен рынок")
            return
        }
        Thread {
            try {
                call.resolve(JSObject().put("cancelled", engine.cancelMarketOrders(conditionId)))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось отменить ордера")
            }
        }.start()
    }

    /**
     * Editing a resting order means cancelling and re-placing it: the exchange
     * has no amend, and the price and size are inside the signature.
     */
    @PluginMethod
    fun replaceOrder(call: PluginCall) {
        val orderId = call.getString("orderId")
        val tokenId = call.getString("tokenId")
        val conditionId = call.getString("conditionId")
        val side = call.getString("side")?.uppercase()
        val price = call.getDouble("price")
        val size = call.getDouble("size")

        if (orderId.isNullOrEmpty() || tokenId.isNullOrEmpty() ||
            conditionId.isNullOrEmpty() || side !in setOf("BUY", "SELL") ||
            price == null || size == null
        ) {
            call.reject("Нужны ордер, рынок, сторона, цена и размер")
            return
        }

        Thread {
            try {
                if (!engine.cancelOrder(orderId)) {
                    call.reject("Ордер уже исполнен или снят — изменить нечего")
                    return@Thread
                }
                val result = engine.placeManualOrder(
                    tokenId, conditionId, side!!, price, size, "GTC",
                )
                call.resolve(
                    JSObject()
                        .put("success", result.success)
                        .put("orderId", result.orderId)
                        .put("status", result.status)
                        .put("error", result.error),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось изменить ордер")
            }
        }.start()
    }

    /**
     * Write the whole journal to a shareable text file.
     */
    @PluginMethod
    fun exportJournal(call: PluginCall) {
        Thread {
            try {
                val bot = engine
                val stamp = java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss",
                    java.util.Locale.US,
                ).format(java.util.Date())
                val target = File(context.cacheDir, "polybot-journal-$stamp.txt")

                bot.journal.exportTo(target, buildHeader(bot))

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "PolyBot журнал $stamp")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(share, "Отправить журнал")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )

                call.resolve(
                    JSObject()
                        .put("file", target.name)
                        .put("bytes", target.length()),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось выгрузить журнал")
            }
        }.start()
    }

    @PluginMethod
    fun clearJournal(call: PluginCall) {
        engine.journal.clear()
        call.resolve()
    }

    @PluginMethod
    fun getJournalSize(call: PluginCall) {
        call.resolve(JSObject().put("bytes", engine.journal.totalBytes()))
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** A short preamble so an exported log says what produced it. */
    /**
     * A rung list from the screen, or nothing.
     *
     * An empty or malformed ladder falls back to the default rather than
     * leaving the rule with no price to sell at.
     */
    private fun parseSellLadder(raw: JSArray?): List<Double>? {
        if (raw == null || raw.length() == 0) return null
        val out = ArrayList<Double>(raw.length())
        for (i in 0 until raw.length()) {
            val price = raw.optDouble(i, Double.NaN)
            if (price.isNaN() || price <= 0.0 || price >= 1.0) continue
            out.add(price)
        }
        return out.ifEmpty { null }
    }

    private fun buildHeader(bot: BotEngine): String = buildString {
        appendLine("PolyBot — журнал ручного стола")
        appendLine("Кошелёк подключён: ${if (bot.isConfigured()) "да" else "нет"}")
        appendLine("Сдвиг часов: ${Clock.offset()} с")
        appendLine("Строки — события приложения, новые снизу.")
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        call.resolve(buildState())
    }

    @PluginMethod
    fun getLogs(call: PluginCall) {
        val array = JSArray()
        engine.logs.forEach { array.put(logToJson(it)) }
        call.resolve(JSObject().put("entries", array))
    }

    /**
     * Aggressive OEM battery managers will still freeze the process. Sending the
     * user straight to the exemption dialog is the only reliable fix.
     */
    @PluginMethod
    fun requestBatteryExemption(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            call.resolve(JSObject().put("exempt", true))
            return
        }
        val power = context.getSystemService(PowerManager::class.java)
        val packageName = context.packageName
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            call.resolve(JSObject().put("exempt", true))
            return
        }
        val intent = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        call.resolve(JSObject().put("exempt", false))
    }

    @PluginMethod
    fun isBatteryExempt(call: PluginCall) {
        val exempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
        call.resolve(JSObject().put("exempt", exempt))
    }

    private fun notifyState() {
        notifyListeners("state", buildState())
    }

    private fun notifyLog(entry: LogEntry) {
        notifyListeners("log", logToJson(entry))
    }

    private fun logToJson(entry: LogEntry): JSObject = JSObject()
        .put("id", entry.id)
        .put("at", entry.at)
        .put("level", entry.level)
        .put("message", entry.message)

    private fun buildState(): JSObject {
        val bot = engine
        val state = JSObject()
            .put("serviceAlive", bot.isConfigured())
            .put("feedStatus", bot.feed.status.name.lowercase())
            .put("clockOffsetSec", Clock.offset())

        bot.quotes?.let { q ->
            fun quoteJson(quote: Quote?) = quote?.let {
                JSObject()
                    .put("bestBid", it.bestBid)
                    .put("bestAsk", it.bestAsk)
                    .put("mid", it.mid)
            }
            state.put(
                "quotes",
                JSObject()
                    .put("up", quoteJson(q.up))
                    .put("down", quoteJson(q.down))
                    .put("atMs", q.atMs),
            )
        }

        val positions = JSArray()
        bot.positions.forEach {
            positions.put(
                JSObject()
                    .put("asset", it.asset)
                    .put("conditionId", it.conditionId)
                    .put("title", it.title)
                    .put("outcome", it.outcome)
                    .put("size", it.size)
                    .put("avgPrice", it.avgPrice)
                    .put("curPrice", it.curPrice)
                    .put("cashPnl", it.cashPnl)
                    .put("redeemable", it.redeemable),
            )
        }
        state.put("positions", positions)

        bot.feed.last?.let {
            state.put(
                "lastTick",
                JSObject().put("timestamp", it.timestamp).put("value", it.value),
            )
        }

        bot.feed.twap?.let {
            state.put(
                "twapTick",
                JSObject().put("timestamp", it.timestamp).put("value", it.value),
            )
        }

        bot.feed.spot?.let {
            state.put(
                "spotTick",
                JSObject().put("timestamp", it.timestamp).put("value", it.value),
            )
        }

        return state
    }

    // ---------------------------------------------------------------- vault

    @PluginMethod
    fun vaultStore(call: PluginCall) {
        val privateKey = call.getString("privateKey")
        if (privateKey.isNullOrEmpty()) {
            call.reject("privateKey required")
            return
        }
        try {
            KeyVault.store(context, privateKey)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "не удалось сохранить ключ")
        }
    }

    @PluginMethod
    fun vaultLoad(call: PluginCall) {
        call.resolve(JSObject().put("privateKey", KeyVault.load(context)))
    }

    @PluginMethod
    fun vaultClear(call: PluginCall) {
        KeyVault.clear(context)
        call.resolve()
    }

    // --------------------------------------------------------- manual desk

    /**
     * Polymarket's own price series for the chart.
     *
     * Their chart is the thirty-second TWAP — the average the five-minute
     * markets settle against — so this is that same series, stitched from as
     * many five-minute windows as the chart spans. The live figure comes from
     * the TWAP socket, which is the number the site puts on screen.
     */
    @PluginMethod
    fun polyCandles(call: PluginCall) {
        val minutes = call.getInt("minutes") ?: 40
        Thread {
            try {
                val candles = JSArray()
                PolyPriceApi.candles(minutes).forEach {
                    candles.put(
                        JSObject()
                            .put("time", it.time)
                            .put("open", it.open)
                            .put("high", it.high)
                            .put("low", it.low)
                            .put("close", it.close),
                    )
                }
                val result = JSObject().put("candles", candles)
                engine.feed.twap?.let {
                    result.put(
                        "ticker",
                        JSObject().put("mid", it.value).put("at", it.timestamp),
                    )
                }
                call.resolve(result)
            } catch (e: Exception) {
                call.reject(e.message ?: "цены Polymarket недоступны")
            }
        }.start()
    }

    /**
     * One five-minute window of Polymarket's own price series, and the price
     * that window has to beat.
     *
     * Two sources, because neither is enough alone. The socket carries the
     * sixty-second TWAP once a second — the number the market settles on, live
     * — but only from the moment the app connected. The HTTP series covers the
     * whole window including the opening price the market resolves against,
     * but it is downsampled and runs about half a minute behind. So the HTTP
     * answer is the backbone and the socket is the growing end of the line;
     * where they overlap they agree to the cent, being the same series.
     *
     * `since` makes the second call onwards cheap: a chart redrawn every
     * second wants the one new tick, not three hundred old ones.
     */
    @PluginMethod
    fun polyWindow(call: PluginCall) {
        val windowStart = call.getInt("windowStart")?.toLong()
        if (windowStart == null || windowStart <= 0L) {
            call.reject("windowStart required")
            return
        }
        val since = call.getString("since")?.toLongOrNull() ?: 0L
        Thread {
            try {
                val fromMs = windowStart * 1000
                val toMs = (windowStart + WINDOW_SECONDS) * 1000

                // Missing history is a thinner chart; a missing socket is a
                // chart that stops moving. Neither is worth failing the call.
                val backbone = try {
                    PolyPriceApi.window(windowStart)
                } catch (e: Exception) {
                    emptyList()
                }
                val merged = sortedMapOf<Long, Double>()
                backbone.forEach {
                    if (it.timestamp in fromMs..toMs) merged[it.timestamp] = it.value
                }
                engine.feed.twap60Between(fromMs, toMs).forEach {
                    merged[it.timestamp] = it.value
                }

                val out = JSArray()
                merged.forEach { (t, v) ->
                    if (t > since) out.put(JSArray().apply { put(t); put(v) })
                }

                val result = JSObject()
                    .put("windowStart", windowStart)
                    .put("points", out)
                // The target is the price at the very start of the window, not
                // merely the earliest reading we happen to hold: a window the
                // app joined late opens with a tick from the middle of it, and
                // charting that as the line to beat would be a lie.
                merged.entries.firstOrNull()?.let {
                    if (it.key <= fromMs + 2_000) result.put("target", it.value)
                }
                call.resolve(result)
            } catch (e: Exception) {
                call.reject(e.message ?: "цены Polymarket недоступны")
            }
        }.start()
    }

    /**
     * The two numbers Polymarket prints over its own chart: the price the
     * window has to beat, and where the price is now.
     *
     * Both come out of memory, so this can be asked for as fast as the screen
     * can draw it. The target — the window's very first sixty-second reading —
     * cannot change once the window has opened, so it is fetched once per
     * window on a background thread and then simply remembered; the live end
     * is the socket's own tick, which arrives once a second.
     *
     * When the target is not known yet the call still answers, with the price
     * and nothing else. A readout that blinks out while a fetch is in flight
     * is worse than one that fills in a moment later.
     */
    @PluginMethod
    fun polyMark(call: PluginCall) {
        val windowStart = call.getInt("windowStart")?.toLong()
            ?: (Clock.nowSec() - SellLadder.elapsedInWindow(Clock.nowSec()))

        val target = WindowOpen.of(windowStart, engine.feed)
        val live = engine.feed.twap60 ?: engine.feed.twap

        val result = JSObject()
            .put("windowStart", windowStart)
            .put("target", target)
            .put("price", live?.value)
            .put("at", live?.timestamp ?: 0L)
        if (target != null && live != null) {
            result.put("change", live.value - target)
        }
        call.resolve(result)
    }

    /**
     * Binance's book for BTC/USDT, as a depth curve.
     *
     * The book is kept locally off the hundred-millisecond diff stream, so
     * this reads memory rather than the network and can be asked for as often
     * as the screen can draw. Sizes come out bucketed by distance from the
     * mid, nearest bucket first; the running total is the curve.
     */
    @PluginMethod
    fun binanceDepth(call: PluginCall) {
        val depth = BinanceBook.depth()
        if (depth == null) {
            // Not an error: the book is still syncing, and the panel says so.
            call.resolve(JSObject().put("ready", false))
            return
        }
        val bids = JSArray()
        depth.bids.forEach { bids.put(it) }
        val asks = JSArray()
        depth.asks.forEach { asks.put(it) }
        call.resolve(
            JSObject()
                .put("ready", true)
                .put("bid", depth.bid)
                .put("ask", depth.ask)
                .put("at", depth.at)
                .put("span", BinanceBook.SPAN)
                .put("bids", bids)
                .put("asks", asks),
        )
    }

    /**
     * Binance's candles for one interval, from the streams the app keeps open.
     *
     * Flat rows — open time, open, high, low, close — because a chart of fifty
     * candles is two hundred and fifty numbers and none of them need a name.
     */
    @PluginMethod
    fun binanceCandles(call: PluginCall) {
        val rows = JSArray()
        BinanceCandles.of(call.getString("interval") ?: "5m").list().forEach {
            rows.put(
                JSArray().apply {
                    put(it.time)
                    put(it.open)
                    put(it.high)
                    put(it.low)
                    put(it.close)
                },
            )
        }
        call.resolve(JSObject().put("candles", rows))
    }

    /**
     * GMX candles for the chart. This runs natively for the same reason every
     * other request does: the WebView reports transport failures as an opaque
     * "Failed to fetch", which is impossible to act on.
     */
    @PluginMethod
    fun gmxCandles(call: PluginCall) {
        val symbol = call.getString("symbol") ?: "BTC"
        val period = call.getString("period") ?: "1m"
        val limit = call.getInt("limit") ?: 120
        Thread {
            try {
                val candles = JSArray()
                // Oldest first: a chart is drawn left to right.
                GmxApi.candles(symbol, period, limit).asReversed().forEach {
                    candles.put(
                        JSObject()
                            .put("time", it.time)
                            .put("open", it.open)
                            .put("high", it.high)
                            .put("low", it.low)
                            .put("close", it.close),
                    )
                }
                val result = JSObject().put("candles", candles)
                GmxApi.ticker(symbol)?.let {
                    result.put(
                        "ticker",
                        JSObject().put("min", it.min).put("max", it.max)
                            .put("mid", it.mid).put("at", it.at),
                    )
                }
                call.resolve(result)
            } catch (e: Exception) {
                call.reject(e.message ?: "GMX недоступен")
            }
        }.start()
    }

    /**
     * Positions, read fresh.
     *
     * The engine's own list is screen data on a slow ambient poll; a desk being
     * traded by hand needs to see a fill sooner than that.
     */
    @PluginMethod
    fun getPositions(call: PluginCall) {
        Thread {
            try {
                val session = engine.session()
                if (session == null) {
                    call.reject("Кошелёк не подключён")
                    return@Thread
                }
                val out = JSArray()
                DataApi.positions(session.account.funderAddress).forEach {
                    // While the data API is still indexing a fresh trade it
                    // reports the size but no cost basis, which would show as a
                    // purchase at zero and a profit equal to the whole position.
                    val local = if (it.avgPrice > 0.0) null else LocalFills.avgFor(it.asset)
                    val avg = local ?: it.avgPrice
                    val pnl = if (local != null) {
                        (it.curPrice - local) * it.size
                    } else {
                        it.cashPnl
                    }
                    out.put(
                        JSObject()
                            .put("asset", it.asset)
                            .put("conditionId", it.conditionId)
                            .put("title", it.title)
                            .put("outcome", it.outcome)
                            .put("size", it.size)
                            .put("avgPrice", avg)
                            .put("curPrice", it.curPrice)
                            .put("cashPnl", pnl)
                            .put("redeemable", it.redeemable),
                    )
                }
                call.resolve(JSObject().put("positions", out))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось прочитать позиции")
            }
        }.start()
    }

    /**
     * Orders sent this window and what became of them.
     *
     * The open-orders listing drops an order the moment it fills, which is the
     * one you most want to see afterwards, so the log keeps the whole round.
     */
    /**
     * How each five-minute event went: which side it closed on and what the
     * round made. The window is the unit this app trades, so it is the unit
     * worth scoring.
     */
    @PluginMethod
    fun getEvents(call: PluginCall) {
        val limit = call.getInt("limit") ?: 12
        Thread {
            try {
                val out = JSArray()
                EventStats.recent(limit).forEach {
                    out.put(
                        JSObject()
                            .put("windowStart", it.windowStart)
                            .put("winner", it.winner)
                            .put("settled", it.settled)
                            .put("spent", it.spent)
                            .put("got", it.got)
                            .put("held", it.held)
                            .put("settlement", it.settlement)
                            .put("pnl", it.pnl)
                            .put("trades", it.trades),
                    )
                }
                call.resolve(
                    JSObject()
                        .put("events", out)
                        .put("session", EventStats.sessionPnl()),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "итоги недоступны")
            }
        }.start()
    }

    @PluginMethod
    fun getOrderLog(call: PluginCall) {
        val windowStart = call.getInt("windowStart")?.toLong()
            ?: GammaApi.windowStartFor()
        Thread {
            try {
                engine.session()?.let { session ->
                    val open = ClobApi.openOrders(session.creds, session.account.signerAddress)
                    OrderLog.reconcile(open) { id ->
                        ClobApi.order(session.creds, session.account.signerAddress, id)
                    }
                    // The listing says what is still working; only the trade
                    // feed says what actually changed hands. Asking it here as
                    // well as in the sell rule is what makes the panel right
                    // when the rule is off — twice a minute is enough for a
                    // five-minute market and gentle on the data API.
                    TradeSync.poll(session.account.funderAddress, minGapMs = 30_000L)
                }
                val out = JSArray()
                OrderLog.forWindow(windowStart).forEach {
                    out.put(
                        JSObject()
                            .put("id", it.id)
                            .put("orderId", it.orderId)
                            .put("asset", it.asset)
                            .put("outcome", it.outcome)
                            .put("action", it.action)
                            .put("price", it.price)
                            // What it actually went at, where that is known —
                            // the exit is priced off this, not off the ask.
                            .put("fillPrice", it.fillPrice)
                            .put("size", it.size)
                            .put("matched", it.matched)
                            .put("status", it.status)
                            .put("placedAt", it.placedAt)
                            .put("auto", it.auto),
                    )
                }
                call.resolve(JSObject().put("orders", out))
            } catch (e: Exception) {
                call.reject(e.message ?: "журнал ордеров недоступен")
            }
        }.start()
    }

    /** The market for a given window, so the desk can look one ahead. */
    @PluginMethod
    fun getMarketForWindow(call: PluginCall) {
        val windowStart = call.getInt("windowStart")?.toLong()
        if (windowStart == null) {
            call.reject("windowStart required")
            return
        }
        Thread {
            val market = engine.marketForWindow(windowStart)
            if (market == null) {
                call.reject("Окно ещё не открыто")
                return@Thread
            }
            call.resolve(marketJson(market))
        }.start()
    }

    /** Full depth for one outcome, for the manual order book. */
    @PluginMethod
    fun getBookLevels(call: PluginCall) {
        val tokenId = call.getString("tokenId")
        if (tokenId == null) {
            call.reject("tokenId required")
            return
        }
        val depth = call.getInt("depth") ?: 12
        Thread {
            try {
                val book = ClobApi.getBook(tokenId)
                fun levels(side: List<ClobApi.Level>, best: Boolean): JSArray {
                    val sorted = if (best) {
                        side.sortedByDescending { it.price }
                    } else {
                        side.sortedBy { it.price }
                    }
                    val out = JSArray()
                    sorted.take(depth).forEach {
                        out.put(JSObject().put("price", it.price).put("size", it.size))
                    }
                    return out
                }
                call.resolve(
                    JSObject()
                        .put("bids", levels(book.bids, best = true))
                        .put("asks", levels(book.asks, best = false)),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "стакан недоступен")
            }
        }.start()
    }

    @PluginMethod
    fun autoSellUpdate(call: PluginCall) {
        val defaults = autoSell.settings
        val next = AutoSell.Settings(
            enabled = call.getBoolean("enabled") ?: defaults.enabled,
            ladder = parseSellLadder(call.getArray("ladder")) ?: defaults.ladder,
            retryEverySec = call.getInt("retryEverySec") ?: defaults.retryEverySec,
            rebuyEnabled = call.getBoolean("rebuyEnabled") ?: defaults.rebuyEnabled,
            rebuyDropPct = call.getDouble("rebuyDropPct") ?: defaults.rebuyDropPct,
            watchSec = call.getInt("watchSec") ?: defaults.watchSec,
            rebuySlicePauseSec = call.getInt("rebuySlicePauseSec")
                ?: defaults.rebuySlicePauseSec,
            ladderLeadSec = call.getInt("ladderLeadSec") ?: defaults.ladderLeadSec,
            ladderStepSec = call.getInt("ladderStepSec")?.toLong() ?: defaults.ladderStepSec,
            percentMode = call.getBoolean("percentMode") ?: defaults.percentMode,
            profitPct = call.getDouble("profitPct") ?: defaults.profitPct,
            sliceGapSec = call.getInt("sliceGapSec") ?: defaults.sliceGapSec,
            panicSec = call.getInt("panicSec") ?: defaults.panicSec,
            closeFloor = call.getDouble("closeFloor") ?: defaults.closeFloor,
            lateFloor = call.getDouble("lateFloor") ?: defaults.lateFloor,
            lateBandSec = call.getInt("lateBandSec") ?: defaults.lateBandSec,
        )
        if (next.enabled && !engine.isConfigured()) {
            call.reject("Сначала подключите кошелёк")
            return
        }
        autoSell.update(next)
        // The service keeps it alive while the app is backgrounded. Either rule
        // on is reason enough: the buy-back runs in the same loop.
        if (next.enabled || next.rebuyEnabled) {
            BotService.startAutoSell(context)
        } else {
            BotService.stopAutoSell(context)
        }
        call.resolve()
    }

    @PluginMethod
    fun autoSellState(call: PluginCall) {
        val bot = autoSell
        val rows = JSArray()
        bot.rows.forEach {
            rows.put(
                JSObject()
                    .put("asset", it.asset)
                    .put("title", it.title)
                    .put("outcome", it.outcome)
                    .put("size", it.size)
                    .put("resting", it.resting)
                    .put("restingPrice", it.restingPrice)
                    .put("status", it.status)
                    .put("attempts", it.attempts)
                    .put("lastTryAt", it.lastTryAt)
                    .put("lastError", it.lastError)
                    .put("step", it.step)
                    .put("target", it.target),
            )
        }
        val ladder = JSArray()
        bot.settings.ladder.forEach { ladder.put(it) }

        val waiting = JSArray()
        bot.rebuys.forEach {
            waiting.put(
                JSObject()
                    .put("outcome", bot.rows.firstOrNull { r -> r.asset == it.asset }?.outcome)
                    .put("shares", it.shares)
                    .put("remaining", it.remaining)
                    .put("lot", it.lot)
                    .put("soldAt", it.soldAt)
                    .put("trigger", it.trigger)
                    .put("note", it.note)
                    .put("lastAsk", it.lastAsk)
                    .put("bestAsk", it.bestAsk)
                    .put("lastCheckAt", it.lastCheckAt)
                    .put("checks", it.checks),
            )
        }
        call.resolve(
            JSObject()
                .put("enabled", bot.settings.enabled)
                .put("running", bot.running)
                .put("ladder", ladder)
                .put("retryEverySec", bot.settings.retryEverySec)
                .put("lastSweepAt", bot.lastSweepAt)
                .put("lastFault", bot.lastFault)
                .put("watching", bot.watchingCount)
                .put("watchSec", bot.settings.watchSec)
                .put("rebuyEnabled", bot.settings.rebuyEnabled)
                .put("rebuyDropPct", bot.settings.rebuyDropPct)
                .put("percentMode", bot.settings.percentMode)
                .put("profitPct", bot.settings.profitPct)
                .put("sliceGapSec", bot.settings.sliceGapSec)
                .put("panicSec", bot.settings.panicSec)
                .put("closeFloor", bot.settings.closeFloor)
                .put("lateFloor", bot.settings.lateFloor)
                .put("lateBandSec", bot.settings.lateBandSec)
                .put("timings", JSObject()
                    .put("sellReadyMs", Timings.readyMs())
                    .put("sellReadySamples", Timings.readySamples())
                    .put("cashMs", Timings.cashMs())
                    .put("cashSamples", Timings.cashSamples())
                    .put("cashPending", Timings.cashPending()))
                .put("rebuys", waiting)
                .put("rebuysDone", JSArray().also { arr ->
                    bot.recentRebuys.forEach {
                        arr.put(
                            JSObject()
                                .put("outcome", it.outcome)
                                .put("shares", it.shares)
                                .put("soldAt", it.soldAt)
                                .put("trigger", it.trigger)
                                .put("bestAsk", it.bestAsk)
                                .put("result", it.result)
                                .put("at", it.at),
                        )
                    }
                })
                .put("rows", rows),
        )
    }

    /** Binance's current five-minute candle, for the header's open and move. */
    @PluginMethod
    fun binancePrice(call: PluginCall) {
        Thread {
            try {
                val candle = BinanceApi.current()
                call.resolve(
                    JSObject()
                        .put("openTime", candle.openTime)
                        .put("open", candle.open)
                        .put("last", candle.last)
                        .put("at", candle.at),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "Binance недоступен")
            }
        }.start()
    }

    // -------------------------------------------------------------- the bots

    @PluginMethod
    fun pulseUpdate(call: PluginCall) {
        val d = pulseBot.settings
        pulseBot.update(
            PulsePlan.Settings(
                enabled = call.getBoolean("enabled") ?: d.enabled,
                bankUsd = call.getDouble("bankUsd") ?: d.bankUsd,
                shares = call.getDouble("shares") ?: d.shares,
                fromSec = d.fromSec,
                untilSec = d.untilSec,
                rideSec = d.rideSec,
                minEdge = call.getDouble("minEdge") ?: d.minEdge,
                minLean = call.getDouble("minLean") ?: d.minLean,
                minVolume = call.getDouble("minVolume") ?: d.minVolume,
                minPrice = d.minPrice,
                maxPrice = d.maxPrice,
                takePct = call.getDouble("takePct") ?: d.takePct,
                cutUsd = call.getDouble("cutUsd") ?: d.cutUsd,
                demo = call.getBoolean("demo") ?: d.demo,
            ),
        )
        call.resolve()
    }

    @PluginMethod
    fun pulseReset(call: PluginCall) {
        pulseBot.resetBank()
        call.resolve()
    }

    /** What the pulse bot holds, what it is looking at, and how it has done. */
    @PluginMethod
    fun pulseState(call: PluginCall) {
        val bot = pulseBot
        val t = bot.totals
        val read = bot.read

        call.resolve(
            JSObject()
                .put("enabled", bot.settings.enabled)
                .put("running", bot.running)
                .put("bankUsd", bot.settings.bankUsd)
                .put("shares", bot.settings.shares)
                .put("minEdge", bot.settings.minEdge)
                .put("takePct", bot.settings.takePct)
                .put("demo", bot.settings.demo)
                .put("cash", bot.cash)
                .put("note", bot.note)
                .put("lastFault", bot.lastFault)
                .put("rounds", t.rounds)
                .put("wins", t.wins)
                .put("losses", t.losses)
                .put("spent", t.spent)
                .put("got", t.got)
                .put("settled", t.settled)
                .put("pnl", t.pnl)
                .put("read", read?.let {
                    JSObject()
                        .put("lead", it.lead)
                        .put("momentum", it.momentum)
                        .put("volume", it.volume)
                        .put("lean", it.lean)
                        .put("upAsk", it.upAsk)
                        .put("downAsk", it.downAsk)
                })
                .put("lot", bot.lot?.let {
                    JSObject()
                        .put("outcome", it.outcome)
                        .put("shares", it.open)
                        .put("price", it.price)
                        .put("sellPrice", it.sellPrice)
                        .put("note", it.note)
                }),
        )
    }

    /**
     * What a BEP-20 address holds in USDT.
     *
     * Read-only, keyless and off the public nodes: this is the pocket profit
     * is withdrawn to, and the desk counts it so that taking money out does
     * not read as losing it.
     */
    @PluginMethod
    fun chainBalance(call: PluginCall) {
        val address = call.getString("address")?.trim().orEmpty()
        if (address.isEmpty()) {
            call.resolve(JSObject().put("usdt", 0.0))
            return
        }
        Thread {
            // Two chains, one address. USDT on BSC is where profit is taken
            // out to by hand; USDC on Polygon is where the desk's own
            // withdrawal lands, and a total that ignored it would dip by the
            // amount withdrawn the moment it arrived.
            val usdt = try {
                BscApi.usdtBalance(address)
            } catch (e: Exception) {
                0.0
            }
            val polygon = try {
                val purse = PolygonApi.purse(address)
                purse.usdcE + purse.usdc
            } catch (e: Exception) {
                0.0
            }
            call.resolve(
                JSObject()
                    .put("usdt", usdt)
                    .put("polygon", polygon)
                    .put("total", usdt + polygon),
            )
        }.start()
    }

    /**
     * What a withdrawal would have to work with, before one is attempted.
     *
     * The two things that stop it are worth knowing separately: collateral
     * that sits on a Polymarket proxy rather than on the key's own address,
     * and no POL to pay for the block. Both are answered here so the screen can
     * say which it is instead of failing at the moment of sending.
     */
    @PluginMethod
    fun withdrawInfo(call: PluginCall) {
        val session = engine.session()
        if (session == null) {
            call.reject("Сначала подключите кошелёк")
            return
        }
        Thread {
            try {
                val signer = session.account.signerAddress
                val purse = PolygonApi.purse(signer)
                call.resolve(
                    JSObject()
                        .put("signer", signer)
                        .put("funder", session.account.funderAddress)
                        .put("proxy", !session.account.funderAddress.equals(signer, true))
                        .put("usdcE", purse.usdcE)
                        .put("usdc", purse.usdc)
                        .put("pol", purse.pol)
                        .put("sendable", purse.amount)
                        .put("gasReady", purse.pol > 0.01),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "Polygon недоступен")
            }
        }.start()
    }

    /**
     * Sends USDC from the key's own address to an address on Polygon.
     *
     * One transfer of one token to the address given, signed here and sent to
     * the public nodes. Nothing about it is a contract call the app did not
     * compose itself, and the amount is the amount on the screen.
     */
    @PluginMethod
    fun withdraw(call: PluginCall) {
        val session = engine.session()
        if (session == null) {
            call.reject("Сначала подключите кошелёк")
            return
        }
        val to = call.getString("to")?.trim().orEmpty()
        val usd = call.getDouble("usd") ?: 0.0
        if (!BscApi.looksLikeAddress(to)) {
            call.reject("Адрес получателя не похож на адрес")
            return
        }
        if (usd <= 0.0) {
            call.reject("Сумма должна быть больше нуля")
            return
        }

        Thread {
            try {
                val purse = PolygonApi.purse(session.account.signerAddress)
                if (purse.pol <= 0.0) {
                    call.reject("На кошельке нет POL на газ — пополните немного POL")
                    return@Thread
                }
                if (purse.amount + 1e-9 < usd) {
                    call.reject(
                        "На адресе ключа только " + String.format("%.2f", purse.amount) +
                            " USDC" +
                            if (!session.account.funderAddress
                                    .equals(session.account.signerAddress, true)
                            ) {
                                " — остальное на прокси-кошельке Polymarket, оттуда выводите с сайта"
                            } else {
                                ""
                            },
                    )
                    return@Thread
                }

                val hash = PolygonApi.sendUsdc(session.keys, purse.token, to, usd)
                engine.log(
                    "trade",
                    "Вывод: " + String.format("%.2f", usd) + " USDC → " +
                        to.take(6) + "…" + to.takeLast(4),
                )
                call.resolve(JSObject().put("hash", hash))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось отправить перевод")
            }
        }.start()
    }

    @PluginMethod
    fun takeUpdate(call: PluginCall) {
        val d = takeBot.settings
        takeBot.update(
            d.copy(
                enabled = call.getBoolean("enabled") ?: d.enabled,
                gain = call.getDouble("gain") ?: d.gain,
            ),
        )
        // The service keeps it alive with the screen off, which is when a move
        // this rule exists to catch is most likely to be missed.
        if (takeBot.settings.enabled) BotService.startAutoSell(context)
        call.resolve()
    }

    @PluginMethod
    fun takeState(call: PluginCall) {
        val bot = takeBot
        val watching = JSArray()
        bot.watching.forEach {
            watching.put(
                JSObject()
                    .put("outcome", it.outcome)
                    .put("shares", it.shares)
                    .put("cost", it.cost)
                    .put("bid", it.bid)
                    .put("gain", it.gain),
            )
        }
        call.resolve(
            JSObject()
                .put("enabled", bot.settings.enabled)
                .put("running", bot.running)
                .put("gain", bot.settings.gain)
                .put("lastFault", bot.lastFault)
                .put("takes", bot.totals.takes)
                .put("shares", bot.totals.shares)
                .put("got", bot.totals.got)
                .put("watching", watching),
        )
    }

    @PluginMethod
    fun probeUpdate(call: PluginCall) {
        val d = probeBot.settings
        probeBot.update(
            d.copy(
                enabled = call.getBoolean("enabled") ?: d.enabled,
                stakeUsd = call.getDouble("stakeUsd") ?: d.stakeUsd,
                leadSec = call.getInt("leadSec")?.toLong() ?: d.leadSec,
                roomShare = call.getDouble("roomShare") ?: d.roomShare,
                roundBand = call.getDouble("roundBand") ?: d.roundBand,
                demo = call.getBoolean("demo") ?: d.demo,
                bankUsd = call.getDouble("bankUsd") ?: d.bankUsd,
            ),
        )
        // The entry lands ten seconds before a window opens, which is usually
        // with the screen off. Without the service there is nothing awake to
        // place it.
        if (probeBot.settings.enabled) BotService.startAutoSell(context)
        call.resolve()
    }

    @PluginMethod
    fun probeReset(call: PluginCall) {
        probeBot.reset()
        call.resolve()
    }

    /** The test bot's settings, what it is riding, and the whole record. */
    @PluginMethod
    fun probeState(call: PluginCall) {
        val bot = probeBot

        fun row(r: ProbeBot.Round, open: Boolean): JSObject = JSObject()
            .put("windowStart", r.windowStart)
            .put("demo", r.demo)
            .put("side", r.side)
            .put("perHour", r.perHour)
            .put("shares", r.shares)
            .put("price", r.price)
            .put("sold", r.sold)
            .put("proceeds", r.proceeds)
            .put("settled", r.settled)
            .put("winner", r.winner)
            .put("pnl", r.pnl)
            .put("right", r.right)
            .put("note", r.note)
            .put("open", open)

        val rounds = JSArray()
        // Newest first: the report is read from the top.
        bot.rounds.asReversed().forEach { rounds.put(row(it, open = false)) }

        val riding = JSArray()
        bot.working.forEach { riding.put(row(it, open = true)) }

        call.resolve(
            JSObject()
                .put("enabled", bot.settings.enabled)
                .put("running", bot.running)
                .put("stakeUsd", bot.settings.stakeUsd)
                .put("leadSec", bot.settings.leadSec)
                .put("roomShare", bot.settings.roomShare)
                .put("roundBand", bot.settings.roundBand)
                .put("roundNear", bot.roundNear)
                .put("roomToRound", bot.roomToRound)
                .put("demo", bot.settings.demo)
                .put("bankUsd", bot.settings.bankUsd)
                .put("bank", bot.bank)
                .put("levelAhead", bot.levelAhead)
                .put("roomToLevel", bot.roomToLevel)
                .put("note", bot.note)
                .put("lastFault", bot.lastFault)
                .put("trend", bot.trend?.let {
                    JSObject()
                        .put("way", it.way)
                        .put("perHour", it.perHour)
                        .put("fit", it.fit)
                })
                .put("rounds", rounds)
                .put("riding", riding),
        )
    }
}
