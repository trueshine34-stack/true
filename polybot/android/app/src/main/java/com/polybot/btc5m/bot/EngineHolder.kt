package com.polybot.btc5m.bot

import android.content.Context

/**
 * Process-wide owner of the trading engine.
 *
 * The engine deliberately outlives the foreground service. Stopping the service
 * should stop the standing sell rule, not lock the user out of managing their
 * resting orders or forget the window's order log.
 */
object EngineHolder {

    @Volatile
    private var engine: BotEngine? = null

    @Volatile
    private var autoSell: AutoSell? = null

    @Volatile
    private var ladderBot: LadderBot? = null

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
                journal = Journal(context).also { it.prune() },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
                onLog = { entry -> onLogEntry?.invoke(entry) },
            ).also {
                engine = it
                // Quotes, positions and the price feed are screen data: they
                // must flow from the moment the app opens.
                it.startFeed()
            }
        }
    }

    /** Standing sell rule for hand trading, sharing the engine's session. */
    fun autoSell(context: Context): AutoSell {
        autoSell?.let { return it }
        val host = get(context)
        return synchronized(this) {
            autoSell ?: AutoSell(
                engine = host,
                // Shares the ladder bot is holding are its own to exit: it
                // offers them at the same rung, and two rules pulling each
                // other's orders would leave the position naked between them.
                botShares = { asset -> ladderBot?.takeIf { it.running }?.heldShares(asset) ?: 0.0 },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                autoSell = it
                // A sell is only ever needed just after a buy, so that is when
                // the rule starts looking.
                host.onBought = { asset -> it.watch(asset) }
            }
        }
    }

    /**
     * The bot that buys the favourite while it is still under its own exit.
     * Created on first ask rather than only when switched on, so the panel can
     * show its books while it is off.
     */
    fun ladder(context: Context): LadderBot {
        ladderBot?.let { return it }
        val host = get(context)
        return synchronized(this) {
            ladderBot ?: LadderBot(
                engine = host,
                store = LadderStore(context),
                ladder = { autoSell(context).settings.ladder },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                ladderBot = it
                if (it.settings.enabled) it.start()
            }
        }
    }

    fun peekLadder(): LadderBot? = ladderBot

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine

    fun peekAutoSell(): AutoSell? = autoSell
}
