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

    /** The pair strategy, sharing this engine's feed and signing session. */
    private val pair: PairEngine get() = EngineHolder.pair(context)

    override fun load() {
        EngineHolder.onState = { notifyState() }
        EngineHolder.onLogEntry = { entry -> notifyLog(entry) }
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
        val settings = call.getObject("settings")?.let { parseSettings(it) }
            ?: engine.settings

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
                    settings,
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
                call.reject(e.message ?: "не удалось подключиться", e)
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
    fun updateSettings(call: PluginCall) {
        val raw = call.getObject("settings")
        if (raw == null) {
            call.reject("settings required")
            return
        }
        engine.updateSettings(parseSettings(raw))
        call.resolve()
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
        engine.stop()
        BotService.stop(context)
        call.resolve()
        notifyState()
    }

    @PluginMethod
    fun resetStats(call: PluginCall) {
        engine.resetStats()
        call.resolve()
    }

    @PluginMethod
    fun getBalance(call: PluginCall) {
        Thread {
            try {
                call.resolve(JSObject().put("usdc", engine.usdcBalance()))
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
            call.resolve(
                JSObject()
                    .put("conditionId", market.conditionId)
                    .put("question", market.question)
                    .put("upTokenId", market.up.tokenId)
                    .put("downTokenId", market.down.tokenId)
                    .put("tickSize", market.tickSize)
                    .put("minimumOrderSize", market.minimumOrderSize)
                    .put("windowStart", market.windowStart)
                    .put("windowEnd", market.windowEnd),
            )
        }.start()
    }

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
     *
     * The header carries the configuration and the model's self-assessment, so
     * the file explains on its own what produced the numbers below it — a log
     * without its settings is not analysable.
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

    private fun buildHeader(bot: BotEngine): String {
        val settings = bot.settings
        val ladder = settings.exitLadder.joinToString(", ") {
            "${it.fromSec}с→${(it.price * 100).toInt()}¢"
        }
        return buildString {
            appendLine("PolyBot · журнал")
            appendLine("Выгружено: ${isoUtc(System.currentTimeMillis())}")
            appendLine("Версия: ${appVersion()}")
            appendLine()
            appendLine("--- Настройки ---")
            appendLine("Режим: ${settings.mode}, тестовый режим: ${settings.dryRun}")
            appendLine("Ставка: ${settings.stakeUsd} $, вход через ${settings.entryDelaySec} с")
            appendLine(
                "Попыток входа: ${settings.entryAttempts}, " +
                    "интервал ${settings.entryRetryDelaySec} с",
            )
            appendLine("Мин. преимущество (после комиссии): ${settings.minEdge}")
            appendLine("Ценовой коридор: ${settings.minPrice}–${settings.maxPrice}")
            appendLine("Поднимать до минимума биржи: ${settings.autoBumpToMinimum}")
            appendLine(
                "Лесенка выхода: ${if (settings.exitEnabled) ladder else "выключена"}, " +
                    "задержка ${settings.exitDelaySec} с",
            )
            appendLine(
                "Тейк-профит: ${settings.takeProfitEnabled}, " +
                    "×${settings.takeProfitMultiple}, доля ${settings.takeProfitFraction}",
            )
            appendLine(
                "Докупка: ${settings.averageDownEnabled}, " +
                    "×${settings.averageDownMultiple}, до ${settings.averageDownMaxTimes} раз, " +
                    "дедлайн ${settings.averageDownDeadlineSec} с",
            )
            appendLine(
                "Стопы: убыток ${settings.dailyLossLimitUsd} $, " +
                    "${settings.maxConsecutiveLosses} убытков подряд",
            )
            appendLine()
            appendLine("--- Калибровка модели ---")
            appendLine("Оценённых окон: ${bot.calibration.samples}")
            appendLine("Доверие (сжатие): ${bot.calibration.shrinkage()}")
            appendLine("Brier: ${bot.calibration.brier ?: "нет данных"}")
            appendLine()
            appendLine("--- Статистика за ${bot.statsDay} ---")
            appendLine("Сделок: ${bot.stats.trades}, побед ${bot.stats.wins}, поражений ${bot.stats.losses}")
            appendLine("Результат: ${bot.stats.realisedPnlUsd} $, оборот ${bot.stats.stakedUsd} $")
            appendLine()
            appendLine("--- Как читать ---")
            appendLine("Строки [WINDOW] — по одной на закрытое окно, JSON.")
            appendLine("pModel — сырая вероятность модели, pUsed — после сжатия.")
            appendLine("netEdge — преимущество уже за вычетом комиссии тейкера.")
            appendLine("Комиссии учтены в entryCostUsd и marketProceedsUsd.")
        }
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
            .put("running", bot.running)
            .put("haltReason", bot.haltReason)
            .put("feedStatus", bot.feed.status.name.lowercase())
            .put("clockOffsetSec", Clock.offset())
            .put("statsDay", bot.statsDay)
            .put(
                "calibration",
                JSObject()
                    .put("samples", bot.calibration.samples)
                    .put("shrinkage", bot.calibration.shrinkage())
                    .put("brier", bot.calibration.brier),
            )

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

        bot.feed.spot?.let {
            state.put(
                "spotTick",
                JSObject().put("timestamp", it.timestamp).put("value", it.value),
            )
        }

        state.put(
            "stats",
            JSObject()
                .put("trades", bot.stats.trades)
                .put("wins", bot.stats.wins)
                .put("losses", bot.stats.losses)
                .put("consecutiveLosses", bot.stats.consecutiveLosses)
                .put("realisedPnlUsd", bot.stats.realisedPnlUsd)
                .put("stakedUsd", bot.stats.stakedUsd),
        )
        bot.current?.let { state.put("current", cycleToJson(it)) }

        val history = JSArray()
        bot.history.take(30).forEach { history.put(cycleToJson(it)) }
        state.put("history", history)

        return state
    }

    private fun cycleToJson(cycle: Cycle): JSObject {
        val json = JSObject()
            .put("windowStart", cycle.windowStart)
            .put("windowEnd", cycle.windowEnd)
            .put("state", cycle.state.name.lowercase())
            .put("strike", cycle.strike)
            .put("spotAtEntry", cycle.spotAtEntry)
            .put("winner", cycle.winner)
            .put("pnlUsd", cycle.pnlUsd)
            .put("note", cycle.note)

        cycle.fair?.let {
            json.put(
                "fair",
                JSObject()
                    .put("pUp", it.pUp)
                    .put("rawPUp", it.rawPUp)
                    .put("sigmaHorizon", it.sigmaHorizon)
                    .put("drift", it.drift),
            )
        }
        cycle.market?.let { m ->
            json.put(
                "market",
                JSObject()
                    .put("conditionId", m.conditionId)
                    .put("question", m.question)
                    .put("upTokenId", m.up.tokenId)
                    .put("downTokenId", m.down.tokenId)
                    .put("tickSize", m.tickSize)
                    .put("minimumOrderSize", m.minimumOrderSize),
            )
        }
        if (cycle.exits.isNotEmpty()) {
            val exits = JSArray()
            cycle.exits.forEach {
                exits.put(
                    JSObject()
                        .put("orderId", it.orderId)
                        .put("price", it.price)
                        .put("size", it.size)
                        .put("matched", it.matched)
                        .put("cancelled", it.cancelled),
                )
            }
            json.put("exits", exits)
            json.put("exitFrozen", cycle.exitFrozen)
        }
        json.put("takeProfitDone", cycle.takeProfitDone)
        json.put("averageDownCount", cycle.averageDownCount)
        json.put("soldAtMarket", cycle.soldAtMarket)
        json.put("marketProceedsUsd", cycle.marketProceedsUsd)
        cycle.entry?.let {
            json.put(
                "entry",
                JSObject()
                    .put("side", it.side)
                    .put("price", it.price)
                    .put("shares", it.shares)
                    .put("costUsd", it.costUsd)
                    .put("orderId", it.orderId)
                    .put("dryRun", it.dryRun),
            )
        }
        return json
    }

    private fun parseSettings(raw: JSObject): Settings {
        val defaults = Settings()
        return Settings(
            mode = StrategyMode.from(raw.getString("mode")),
            stakeUsd = raw.optDouble("stakeUsd", defaults.stakeUsd),
            entryDelaySec = raw.optInt("entryDelaySec", defaults.entryDelaySec),
            entryAttempts = raw.optInt("entryAttempts", defaults.entryAttempts),
            entryRetryDelaySec = raw.optInt(
                "entryRetryDelaySec",
                defaults.entryRetryDelaySec,
            ),
            minEdge = raw.optDouble("minEdge", defaults.minEdge),
            maxPrice = raw.optDouble("maxPrice", defaults.maxPrice),
            minPrice = raw.optDouble("minPrice", defaults.minPrice),
            autoBumpToMinimum = raw.optBoolean(
                "autoBumpToMinimum",
                defaults.autoBumpToMinimum,
            ),
            dryRun = raw.optBoolean("dryRun", defaults.dryRun),
            dailyLossLimitUsd = raw.optDouble(
                "dailyLossLimitUsd",
                defaults.dailyLossLimitUsd,
            ),
            maxConsecutiveLosses = raw.optInt(
                "maxConsecutiveLosses",
                defaults.maxConsecutiveLosses,
            ),
            exitEnabled = raw.optBoolean("exitEnabled", defaults.exitEnabled),
            exitDelaySec = raw.optInt("exitDelaySec", defaults.exitDelaySec),
            exitLadder = parseLadder(raw.optJSONArray("exitLadder")) ?: defaults.exitLadder,
            takeProfitEnabled = raw.optBoolean(
                "takeProfitEnabled",
                defaults.takeProfitEnabled,
            ),
            takeProfitMultiple = raw.optDouble(
                "takeProfitMultiple",
                defaults.takeProfitMultiple,
            ),
            takeProfitFraction = raw.optDouble(
                "takeProfitFraction",
                defaults.takeProfitFraction,
            ),
            averageDownEnabled = raw.optBoolean(
                "averageDownEnabled",
                defaults.averageDownEnabled,
            ),
            averageDownMultiple = raw.optDouble(
                "averageDownMultiple",
                defaults.averageDownMultiple,
            ),
            averageDownMaxTimes = raw.optInt(
                "averageDownMaxTimes",
                defaults.averageDownMaxTimes,
            ),
            averageDownDeadlineSec = raw.optInt(
                "averageDownDeadlineSec",
                defaults.averageDownDeadlineSec,
            ),
        )
    }

    // ------------------------------------------------------- pair strategy

    @PluginMethod
    fun pairUpdateSettings(call: PluginCall) {
        val raw = call.getObject("settings")
        if (raw == null) {
            call.reject("settings required")
            return
        }
        pair.updateSettings(parsePairSettings(raw))
        call.resolve()
    }

    @PluginMethod
    fun pairStart(call: PluginCall) {
        val raw = call.getObject("settings")
        if (raw != null) pair.updateSettings(parsePairSettings(raw))
        if (!pair.settings.dryRun && !engine.isConfigured()) {
            call.reject("Сначала подключите кошелёк")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState(NOTIFICATIONS) != com.getcapacitor.PermissionState.GRANTED
        ) {
            requestPermissionForAlias(NOTIFICATIONS, call, "afterPairNotificationsPermission")
            return
        }
        BotService.startPair(context)
        call.resolve()
    }

    @PermissionCallback
    private fun afterPairNotificationsPermission(call: PluginCall) {
        BotService.startPair(context)
        call.resolve()
    }

    @PluginMethod
    fun pairStop(call: PluginCall) {
        pair.stop()
        BotService.stopPair(context)
        call.resolve()
        notifyState()
    }

    @PluginMethod
    fun pairReset(call: PluginCall) {
        pair.reset()
        call.resolve()
    }

    @PluginMethod
    fun pairGetState(call: PluginCall) {
        call.resolve(buildPairState())
    }

    private fun buildPairState(): JSObject {
        val bot = pair
        val state = JSObject()
            .put("running", bot.running)
            .put("dryRun", bot.settings.dryRun)
            .put("haltReason", bot.haltReason)

        bot.quotes?.let { q ->
            fun quoteJson(quote: Quote?) = quote?.let {
                JSObject().put("bestBid", it.bestBid).put("bestAsk", it.bestAsk).put("mid", it.mid)
            }
            state.put(
                "quotes",
                JSObject().put("up", quoteJson(q.up)).put("down", quoteJson(q.down))
                    .put("atMs", q.atMs),
            )
        }

        bot.book?.let { b ->
            state.put(
                "book",
                JSObject()
                    .put("windowStart", b.windowStart)
                    .put("windowEnd", b.windowEnd)
                    .put("upShares", b.up.shares)
                    .put("upAvg", b.up.avg)
                    .put("downShares", b.down.shares)
                    .put("downAvg", b.down.avg)
                    .put("pairs", b.pairs)
                    .put("pairAvg", b.pairAvg)
                    .put("imbalance", b.imbalance)
                    .put("exposureUsd", b.exposureUsd)
                    .put("spentUsd", b.spentUsd)
                    .put("proceedsUsd", b.proceedsUsd)
                    .put("feesUsd", b.feesUsd)
                    .put("lockedProfitUsd", PairMath.lockedProfit(b.up, b.down)),
            )
        }

        bot.book?.let { b ->
            fun trackJson(track: LevelTrack): JSObject {
                val levels = JSArray()
                track.visits.entries.sortedByDescending { it.key }.forEach { (level, n) ->
                    levels.put(JSObject().put("level", level).put("visits", n))
                }
                return JSObject()
                    .put("levels", levels)
                    .put("lowAsk", track.lowAsk)
                    .put("lowMid", track.lowMid)
                    .put("highMid", track.highMid)
            }
            state.put(
                "profile",
                JSObject()
                    .put("tickSize", b.market?.tickSize ?: 0.01)
                    .put("up", trackJson(b.trackUp))
                    .put("down", trackJson(b.trackDown)),
            )
        }

        val orders = JSArray()
        bot.orders.filter { it.live }.forEach {
            orders.put(
                JSObject()
                    .put("localId", it.localId)
                    .put("orderId", it.orderId)
                    .put("side", it.side)
                    .put("action", it.action)
                    .put("price", it.price)
                    .put("size", it.size)
                    .put("matched", it.matched)
                    .put("dryRun", it.dryRun)
                    .put("placedAt", it.placedAt)
                    .put("note", it.note),
            )
        }
        state.put("orders", orders)

        val fills = JSArray()
        bot.fills.asReversed().take(60).forEach {
            fills.put(
                JSObject()
                    .put("at", it.at)
                    .put("side", it.side)
                    .put("action", it.action)
                    .put("shares", it.shares)
                    .put("price", it.price)
                    .put("feeUsd", it.feeUsd)
                    .put("dryRun", it.dryRun)
                    .put("note", it.note),
            )
        }
        state.put("fills", fills)

        val windows = JSArray()
        bot.history.take(20).forEach {
            windows.put(
                JSObject()
                    .put("windowStart", it.windowStart)
                    .put("pairs", it.pairs)
                    .put("pairAvg", it.pairAvg)
                    .put("winner", it.winner)
                    .put("pnlUsd", it.pnlUsd)
                    .put("feesUsd", it.feesUsd),
            )
        }
        state.put("windows", windows)

        fun statsJson(s: PairStats) = JSObject()
            .put("windows", s.windows)
            .put("buys", s.buys)
            .put("sells", s.sells)
            .put("pairsLocked", s.pairsLocked)
            .put("feesUsd", s.feesUsd)
            .put("realisedPnlUsd", s.realisedPnlUsd)

        state.put("stats", statsJson(bot.stats))
        state.put("testStats", statsJson(bot.testStats))
        state.put("liveStats", statsJson(bot.liveStats))
        state.put("paperCash", bot.paperCash)
        state.put("paperEquity", bot.paperEquity())
        return state
    }

    private fun parsePairSettings(raw: JSObject): PairSettings {
        val d = PairSettings()
        return PairSettings(
            dryRun = raw.optBoolean("dryRun", d.dryRun),
            lotShares = raw.optDouble("lotShares", d.lotShares),
            cheapSideBonusPct = raw.optDouble("cheapSideBonusPct", d.cheapSideBonusPct),
            minIntervalSec = raw.optInt("minIntervalSec", d.minIntervalSec),
            maxIntervalSec = raw.optInt("maxIntervalSec", d.maxIntervalSec),
            maxSeedPrice = raw.optDouble("maxSeedPrice", d.maxSeedPrice),
            maxPairAvg = raw.optDouble("maxPairAvg", d.maxPairAvg),
            minPairProfitPct = raw.optDouble("minPairProfitPct", d.minPairProfitPct),
            rotateProfitPct = raw.optDouble("rotateProfitPct", d.rotateProfitPct),
            cheapLegUnder = raw.optDouble("cheapLegUnder", d.cheapLegUnder),
            cheapRotateProfitPct = raw.optDouble(
                "cheapRotateProfitPct",
                d.cheapRotateProfitPct,
            ),
            rotateFraction = raw.optDouble("rotateFraction", d.rotateFraction),
            takerEntry = raw.optBoolean("takerEntry", d.takerEntry),
            maxExposureUsd = raw.optDouble("maxExposureUsd", d.maxExposureUsd),
            maxImbalanceShares = raw.optDouble("maxImbalanceShares", d.maxImbalanceShares),
            flattenSec = raw.optInt("flattenSec", d.flattenSec),
            paperStartUsd = raw.optDouble("paperStartUsd", d.paperStartUsd),
            lowBiasCents = raw.optDouble("lowBiasCents", d.lowBiasCents),
        )
    }

    /** An empty or malformed ladder falls back to the default rather than
     *  leaving the bot with no way to exit a position. */
    private fun parseLadder(raw: org.json.JSONArray?): List<ExitStep>? {
        if (raw == null || raw.length() == 0) return null
        val steps = ArrayList<ExitStep>(raw.length())
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: continue
            val price = o.optDouble("price", Double.NaN)
            if (price.isNaN() || price <= 0.0 || price >= 1.0) continue
            steps.add(ExitStep(o.optInt("fromSec", 0), price))
        }
        return steps.sortedBy { it.fromSec }.ifEmpty { null }
    }
}
