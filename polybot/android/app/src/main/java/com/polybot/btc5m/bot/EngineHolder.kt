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
    private var softPulse: PulseBot? = null

    @Volatile
    private var probeBot: ProbeBot? = null

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
                // Before anything can spend: a reserve the app forgot on a
                // restart is money it would quietly go and trade with.
                val (lockedUsd, lockedPct) = LockStore(context).load()
                it.lockedUsd = lockedUsd
                it.lockedPct = lockedPct
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
                    (pulseBot?.takeIf { it.running }?.heldShares(asset) ?: 0.0) +
                        (softPulse?.takeIf { it.running }?.heldShares(asset) ?: 0.0)
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
                exit = { autoSell?.settings ?: AutoSell.Settings() },
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
     * The same rule with its gates opened up, on its own money.
     *
     * One engine, two configurations. Which set of thresholds is right is not
     * a thing to argue about in the abstract: they read the same windows side
     * by side, each with its own paper bank and its own record, and in a few
     * days the records answer it.
     */
    fun pulseSoft(context: Context): PulseBot {
        softPulse?.let { return it }
        val host = get(context)
        return synchronized(this) {
            softPulse ?: PulseBot(
                engine = host,
                store = PulseStore(
                    context,
                    name = "polybot_pulse2",
                    fallback = PulsePlan.soft(),
                ),
                exit = { autoSell?.settings ?: AutoSell.Settings() },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                softPulse = it
                if (it.settings.enabled) it.start()
            }
        }
    }

    fun peekSoftPulse(): PulseBot? = softPulse

    /**
     * The experiment: one five-dollar entry a window, the way the chart's line
     * points, out by the desk's own ladder. Created on first ask so the report
     * can be read whether it is running or not.
     */
    fun probe(context: Context): ProbeBot {
        probeBot?.let { return it }
        val host = get(context)
        return synchronized(this) {
            probeBot ?: ProbeBot(
                engine = host,
                store = ProbeStore(context),
                // Its paper exits follow the desk's own sell rule as it is
                // set — rungs or margin, with the same late floors — so a demo
                // run answers a question about the ladder actually running.
                exit = { autoSell?.settings ?: AutoSell.Settings() },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                probeBot = it
                if (it.settings.enabled) it.start()
            }
        }
    }

    fun peekProbe(): ProbeBot? = probeBot

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine

    fun peekAutoSell(): AutoSell? = autoSell
}
