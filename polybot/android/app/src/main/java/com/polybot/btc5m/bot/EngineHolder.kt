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
    private var pulseBot: PulseBot? = null

    @Volatile
    private var takeBot: TakeBot? = null

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
                // Shares a rule is holding are its own to exit: it prices them
                // itself, and two rules pulling each other's orders would leave
                // the position naked between them.
                botShares = { asset ->
                    pulseBot?.takeIf { it.running }?.heldShares(asset) ?: 0.0
                },
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
     * The bot that trades one clip at a time on the side four signals agree
     * about. Created on first ask rather than only when switched on, so the
     * panel shows its books whether it is running or not.
     */
    fun pulse(context: Context): PulseBot {
        pulseBot?.let { return it }
        val host = get(context)
        return synchronized(this) {
            pulseBot ?: PulseBot(
                engine = host,
                store = PulseStore(context),
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                pulseBot = it
                if (it.settings.enabled) it.start()
            }
        }
    }

    fun peekPulse(): PulseBot? = pulseBot

    /**
     * The rule that takes a gain the book is showing but the standing offer
     * will not reach. It works the desk's own positions, so it is told which
     * shares belong to the other rules and leaves those alone.
     */
    fun taker(context: Context): TakeBot {
        takeBot?.let { return it }
        val host = get(context)
        return synchronized(this) {
            takeBot ?: TakeBot(
                engine = host,
                store = TakeStore(context),
                botShares = { asset ->
                    pulseBot?.takeIf { it.running }?.heldShares(asset) ?: 0.0
                },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                takeBot = it
                if (it.settings.enabled) it.start()
            }
        }
    }

    fun peekTaker(): TakeBot? = takeBot

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine

    fun peekAutoSell(): AutoSell? = autoSell
}
