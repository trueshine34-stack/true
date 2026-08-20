package com.polybot.btc5m.bot

import android.content.Context

/**
 * Process-wide owner of the trading engine.
 *
 * The engine deliberately outlives the foreground service. Stopping the bot
 * should stop trading, not forget the day's statistics or lock the user out of
 * managing their resting orders, so the service now owns only the foreground
 * lifecycle and the wake lock.
 */
object EngineHolder {

    @Volatile
    private var engine: BotEngine? = null

    @Volatile
    var onState: (() -> Unit)? = null

    @Volatile
    var onLogEntry: ((LogEntry) -> Unit)? = null

    /** Lets the running service refresh its notification on every change. */
    @Volatile
    var onServiceState: (() -> Unit)? = null

    fun get(context: Context): BotEngine {
        engine?.let { return it }
        return synchronized(this) {
            engine ?: BotEngine(
                statsStore = StatsStore(context),
                journal = Journal(context).also { it.prune() },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
                onLog = { entry -> onLogEntry?.invoke(entry) },
            ).also {
                engine = it
                // Quotes, positions and the price feed are screen data: they
                // must flow from the moment the app opens, not only once the
                // bot is started.
                it.startFeed()
            }
        }
    }

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine
}
