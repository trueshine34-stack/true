package com.polybot.btc5m.bot

import android.Manifest
import android.content.Intent
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

    override fun load() {
        BotService.onState = { notifyState() }
        BotService.onLogEntry = { entry -> notifyLog(entry) }
    }

    override fun handleOnDestroy() {
        BotService.onState = null
        BotService.onLogEntry = null
        super.handleOnDestroy()
    }

    @PluginMethod
    fun configure(call: PluginCall) {
        val privateKey = call.getString("privateKey")
        val signerAddress = call.getString("signerAddress")
        val funderAddress = call.getString("funderAddress")
        val apiKey = call.getString("apiKey")
        val secret = call.getString("secret")
        val passphrase = call.getString("passphrase")

        if (privateKey.isNullOrEmpty() || signerAddress.isNullOrEmpty() ||
            funderAddress.isNullOrEmpty() || apiKey.isNullOrEmpty() ||
            secret.isNullOrEmpty() || passphrase.isNullOrEmpty()
        ) {
            call.reject("configure requires the wallet and CLOB credentials")
            return
        }

        BotService.pendingAccount = Account(
            privateKey = privateKey,
            signerAddress = signerAddress,
            funderAddress = funderAddress,
            signatureType = SignatureType.from(call.getInt("signatureType") ?: 0),
        )
        BotService.pendingCreds = Credentials(apiKey, secret, passphrase)
        call.getObject("settings")?.let { BotService.pendingSettings = parseSettings(it) }

        BotService.engine?.let { engine ->
            engine.configure(
                BotService.pendingAccount!!,
                BotService.pendingCreds!!,
                BotService.pendingSettings,
            )
        }
        call.resolve()
    }

    @PluginMethod
    fun updateSettings(call: PluginCall) {
        val raw = call.getObject("settings")
        if (raw == null) {
            call.reject("settings required")
            return
        }
        val settings = parseSettings(raw)
        BotService.pendingSettings = settings
        BotService.engine?.updateSettings(settings)
        call.resolve()
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (BotService.pendingAccount == null || BotService.pendingCreds == null) {
            call.reject("configure() must run before start()")
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
        BotService.engine?.stop()
        BotService.stop(context)
        call.resolve()
        notifyState()
    }

    @PluginMethod
    fun resetStats(call: PluginCall) {
        BotService.engine?.resetStats()
        call.resolve()
    }

    @PluginMethod
    fun getBalance(call: PluginCall) {
        val engine = BotService.engine
        if (engine == null) {
            call.reject("Сервис не запущен")
            return
        }
        Thread {
            try {
                call.resolve(JSObject().put("usdc", engine.usdcBalance()))
            } catch (e: Exception) {
                call.reject(e.message ?: "не удалось прочитать баланс")
            }
        }.start()
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        call.resolve(buildState())
    }

    @PluginMethod
    fun getLogs(call: PluginCall) {
        val engine = BotService.engine
        val array = JSArray()
        engine?.logs?.forEach { array.put(logToJson(it)) }
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
        val engine = BotService.engine
        val state = JSObject()
            .put("serviceAlive", engine != null)
            .put("running", engine?.running == true)
            .put("haltReason", engine?.haltReason)
            .put("feedStatus", engine?.feed?.status?.name?.lowercase() ?: "closed")
            .put("clockOffsetSec", Clock.offset())

        engine?.feed?.last?.let {
            state.put(
                "lastTick",
                JSObject().put("timestamp", it.timestamp).put("value", it.value),
            )
        }

        engine?.let { bot ->
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
        }

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
                    .put("sigmaHorizon", it.sigmaHorizon)
                    .put("drift", it.drift),
            )
        }
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
        )
    }
}
