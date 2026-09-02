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

        // And which way each of the last day's windows settled. The chart asks
        // for a few hours of them on every launch; without this it asks the
        // venue, one window at a time, for answers that were settled before
        // the app was last closed.
        WindowResults.store = object : WindowResults.Store {
            private val prefs = context.applicationContext
                .getSharedPreferences("polybot_results", android.content.Context.MODE_PRIVATE)

            override fun read(key: String): String? = prefs.getString(key, null)
            override fun write(key: String, value: String) {
                prefs.edit().putString(key, value).apply()
            }
        }
        // The coin is settled by the time the engine exists, and it is what
        // decides which file to read.
        engine
        WindowResults.reload()
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
                val wallet = engine.usdcWallet()
                // What may be spent is what the desk sizes from, so that is
                // what "usdc" means everywhere above this line. The wallet
                // itself comes with it, for the sheet that is about the
                // wallet rather than about the next order.
                val locked = engine.lockedAgainst(wallet)
                val usdc = Reserve.free(wallet, locked)
                // Every reading is also the baseline the next sale is timed
                // against, so the desk's own poll feeds the checker too. The
                // reserve does not move, so either figure tracks a sale.
                Timings.balanceRead(usdc, System.currentTimeMillis())
                call.resolve(
                    JSObject()
                        .put("usdc", usdc)
                        .put("wallet", wallet)
                        .put("locked", locked)
                        .put("lockedUsd", engine.lockedUsd)
                        .put("lockedPct", engine.lockedPct),
                )
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось прочитать баланс")
            }
        }.start()
    }

    /**
     * Sets money aside that no order may reach.
     *
     * Kept on the engine, where every buy reads the balance, and written
     * through to disk in the same breath: a reserve that a restart forgot
     * would be spent by the first window after it.
     */
    @PluginMethod
    fun setLocked(call: PluginCall) {
        val usd = call.getDouble("usd") ?: 0.0
        val pct = call.getDouble("pct") ?: 0.0
        engine.lockedUsd = usd
        engine.lockedPct = pct
        // Read back off the engine, which has already clamped both.
        LockStore(context).save(engine.lockedUsd, engine.lockedPct)
        call.resolve(
            JSObject()
                .put("lockedUsd", engine.lockedUsd)
                .put("lockedPct", engine.lockedPct),
        )
        notifyState()
    }

    /**
     * Plays one cue now, so the sounds can be heard without a trade.
     *
     * "up", "down", or anything else for the coin.
     */
    @PluginMethod
    fun playChime(call: PluginCall) {
        Chime.demo(call.getString("kind") ?: "sold")
        call.resolve()
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

    /**
     * The coins this desk can be pointed at, and the one it is on.
     *
     * The list comes from the native side rather than being written twice:
     * a coin is a slug prefix and three stream names, and the screen only
     * needs its label and how finely to print its price.
     */
    @PluginMethod
    fun getCoins(call: PluginCall) {
        val array = JSArray()
        Coins.all.forEach {
            array.put(
                JSObject()
                    .put("id", it.id)
                    .put("label", it.label)
                    .put("digits", it.digits),
            )
        }
        call.resolve(JSObject().put("coins", array).put("current", Coins.current.id))
    }

    /**
     * Move the desk to another coin.
     *
     * Off the main thread: this closes four sockets and opens four more, and
     * the answer is only sent once the desk is actually pointed there — the
     * screen redraws off it, and redrawing early shows the new coin's name
     * over the old coin's numbers.
     */
    @PluginMethod
    fun setCoin(call: PluginCall) {
        val id = call.getString("id")
        Thread {
            val changed = try {
                EngineHolder.selectCoin(context, id)
            } catch (e: Exception) {
                call.reject(e.message ?: "Не вышло сменить монету")
                return@Thread
            }
            call.resolve(
                JSObject().put("id", Coins.current.id).put("changed", changed),
            )
        }.start()
    }

    /**
     * The cue before each window opens: on, off, or as it stands.
     *
     * Answering with the state it is in either way means the switch on the
     * screen is drawn from the rule rather than from what was last pressed.
     */
    @PluginMethod
    fun setCountdown(call: PluginCall) {
        call.getBoolean("enabled")?.let {
            Countdown.set(it)
            CueStore(context).save(it)
            // The cue exists for a phone in a pocket, which is a phone whose
            // WebView Android has stopped running. So it holds the service up
            // while it is on — and lets it go again without touching the sell
            // rule, which may be working a position.
            if (it) BotService.start(context) else BotService.stopDesk(context)
        }
        call.resolve(JSObject().put("enabled", Countdown.on))
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
            // Which market everything else in here is about. The screen can be
            // switched from the screen, but the service can also come back on
            // another coin after a restart, and the two have to agree.
            .put("coin", Coins.current.id)

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
     * Which way the window is leaning, as a hint and nothing more.
     *
     * The four readings the pulse rule used to trade on, with no rule behind
     * them any more: the lead off the window's own open, the last few minutes
     * of momentum, whether the last minute traded, and which way the book
     * leans. Everything it needs is already in memory, so the screen can ask
     * as often as it likes.
     */
    @PluginMethod
    fun signal(call: PluginCall) {
        val nowSec = Clock.nowSec()
        val windowStart = nowSec - SellLadder.elapsedInWindow(nowSec)
        val open = WindowOpen.of(windowStart, engine.feed)
        val live = (engine.feed.twap60 ?: engine.feed.twap)?.value

        val depth = BinanceBook.depth()
        val bid = depth?.bids?.sum() ?: 0.0
        val ask = depth?.asks?.sum() ?: 0.0

        val read = Signal.Read(
            elapsedSec = nowSec - windowStart,
            lead = if (open != null && live != null) live - open else 0.0,
            momentum = BinanceCandles.oneMinute.momentum(),
            volume = BinanceCandles.oneMinute.volumeRatio(),
            lean = if (bid + ask > 0.0) bid / (bid + ask) else 0.5,
            price = open ?: live ?: BinanceTrades.last,
        )
        val hint = Signal.of(read)

        call.resolve(
            JSObject()
                .put("side", hint.side)
                .put("agree", hint.agree)
                .put("against", hint.against)
                .put("ready", open != null && live != null)
                .put("elapsedSec", read.elapsedSec)
                .put("lead", read.lead)
                .put("momentum", read.momentum)
                .put("volume", read.volume)
                .put("lean", read.lean),
        )
    }

    /**
     * The day's support and resistance, as the rule holds them.
     *
     * Read from memory: the levels are merged into a kept set once a minute
     * and asked for as often as the screen draws. They come out as the rule
     * has them rather than being recomputed here, so the line a window was
     * refused at is the line under the candle.
     */
    @PluginMethod
    fun dayLevels(call: PluginCall) {
        val here = call.getDouble("price")
            ?: BinanceCandles.oneMinute.list().lastOrNull()?.close
            ?: 0.0
        DayLevels.refresh(Clock.nowSec())
        val out = JSArray()
        DayLevels.all(here).forEach {
            out.put(
                JSObject()
                    .put("price", it.price)
                    .put("touches", it.touches)
                    .put("kind", it.kind)
                    .put("low", it.low)
                    .put("high", it.high),
            )
        }
        call.resolve(JSObject().put("levels", out).put("price", here))
    }

    /**
     * How the five-minute windows settled, by Polymarket's own reckoning.
     *
     * Not which way the Binance candle closed: Polymarket settles against its
     * own sixty-second average read at the boundary and again at the close, so
     * a candle that finishes green can settle Down. Over a candle, only the
     * one that paid out is worth an arrow.
     *
     * Answers with what is already known and fetches the rest in the
     * background, so the panel fills in over a few seconds rather than
     * blocking on a request per candle.
     */
    @PluginMethod
    fun windowResults(call: PluginCall) {
        val wanted = call.getArray("windows")
        val windows = ArrayList<Long>()
        if (wanted != null) {
            for (i in 0 until wanted.length()) {
                val at = wanted.optLong(i)
                if (at > 0L) windows.add(at)
            }
        }
        val known = WindowResults.want(windows, Clock.nowSec())
        val out = JSObject()
        known.forEach { (at, side) -> out.put(at.toString(), side) }
        call.resolve(JSObject().put("results", out))
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
        val series = BinanceCandles.of(call.getString("interval") ?: "5m")
        // More than the chart draws, when it asks: the screen can be pinched
        // out to more candles than fit at rest, and the extra ones are already
        // in memory.
        series.list(call.getInt("limit") ?: series.limit).forEach {
            rows.put(
                JSArray().apply {
                    put(it.time)
                    put(it.open)
                    put(it.high)
                    put(it.low)
                    put(it.close)
                    // What traded in it, which is what says where a move will
                    // have to push through something and where it will not.
                    put(it.volume)
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
                // Bringing the log up to date is worth doing and not worth
                // failing over: a rate-limited listing used to take the whole
                // answer down with it, so the desk got nothing back and drew
                // the last thing it had. The log it already holds is always
                // better than no log.
                try {
                    engine.session()?.let { session ->
                        val open =
                            ClobApi.openOrders(session.creds, session.account.signerAddress)
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
                } catch (e: Exception) {
                    // Reported on the next sweep; the rows still come back.
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
            // And the one after it, off this thread. The desk asks for the
            // window it is pointed at every twenty seconds, so warming the
            // next one here means tapping the timer to look ahead finds it
            // already in memory instead of waiting on Gamma.
            engine.warmMarket(windowStart + WINDOW_SECONDS)
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
            watchSec = call.getInt("watchSec") ?: defaults.watchSec,
            chime = call.getBoolean("chime") ?: defaults.chime,
            dipRescue = call.getBoolean("dipRescue") ?: defaults.dipRescue,
            ladderLeadSec = call.getInt("ladderLeadSec") ?: defaults.ladderLeadSec,
            ladderStepSec = call.getInt("ladderStepSec")?.toLong() ?: defaults.ladderStepSec,
            percentMode = call.getBoolean("percentMode") ?: defaults.percentMode,
            profitPct = call.getDouble("profitPct") ?: defaults.profitPct,
            sliceGapSec = call.getInt("sliceGapSec") ?: defaults.sliceGapSec,
            panicSec = call.getInt("panicSec") ?: defaults.panicSec,
            closeFloor = call.getDouble("closeFloor") ?: defaults.closeFloor,
            lateFloor = call.getDouble("lateFloor") ?: defaults.lateFloor,
            lateBandSec = call.getInt("lateBandSec") ?: defaults.lateBandSec,
            ride = call.getBoolean("ride") ?: defaults.ride,
            rideWaitMs = call.getInt("rideWaitMs")?.toLong() ?: defaults.rideWaitMs,
            anyProfit = call.getBoolean("anyProfit") ?: defaults.anyProfit,
        )
        if (next.enabled && !engine.isConfigured()) {
            call.reject("Сначала подключите кошелёк")
            return
        }
        autoSell.update(next)
        // The service keeps it alive while the app is backgrounded.
        if (next.enabled) {
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
                .put("ride", bot.settings.ride)
                .put("rideWaitMs", bot.settings.rideWaitMs)
                // The one-shot switch, read back rather than remembered by the
                // screen: it takes itself off when it fires, and the screen has
                // to see that.
                .put("anyProfit", bot.settings.anyProfit)
                .put("chime", bot.settings.chime)
                .put("dipRescue", bot.settings.dipRescue)
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
}
