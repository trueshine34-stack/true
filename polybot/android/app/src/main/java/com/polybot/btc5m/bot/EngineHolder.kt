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

    /**
     * The pulses, one pair per coin.
     *
     * Each coin's rule keeps its own money, its own record and its own
     * settings, because a record that mixes two markets answers no question
     * about either. Only the coin on the screen runs: the rules read the live
     * feeds, and there is one set of those.
     */
    private val pulses = HashMap<String, PulseBot>()

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
            // Before the engine exists, because its feeds read the coin as
            // they start: an engine built on bitcoin and switched a moment
            // later opens two sets of sockets to arrive where it was told.
            Coins.select(CoinStore(context).load().id)
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
                    synchronized(this) { pulses.values.toList() }
                        .filter { it.running }
                        .sumOf { it.heldShares(asset) }
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
    fun pulse(context: Context): PulseBot = pulseFor(context, soft = false)

    /**
     * The same rule with its gates opened up, on its own money.
     *
     * One engine, two configurations. Which set of thresholds is right is not
     * a thing to argue about in the abstract: they read the same windows side
     * by side, each with its own paper bank and its own record, and in a few
     * days the records answer it.
     */
    fun pulseSoft(context: Context): PulseBot = pulseFor(context, soft = true)

    private fun pulseFor(context: Context, soft: Boolean): PulseBot {
        val coin = Coins.current.id
        val key = key(soft, coin)
        pulses[key]?.let { return it }
        val host = get(context)
        return synchronized(this) {
            pulses[key] ?: PulseBot(
                engine = host,
                store = PulseStore(
                    context,
                    name = storeName(soft, coin),
                    fallback = if (soft) PulsePlan.soft() else PulsePlan.Settings(),
                ),
                exit = { autoSell?.settings ?: AutoSell.Settings() },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                pulses[key] = it
                if (it.settings.enabled && coin == Coins.current.id) it.start()
            }
        }
    }

    private fun key(soft: Boolean, coin: String) = (if (soft) "soft." else "hard.") + coin

    /**
     * Where a rule's record is kept.
     *
     * Bitcoin keeps the file it has always had, so nothing that was traded
     * before there was a choice of coin is lost to a rename; the coins added
     * afterwards get a file each.
     */
    private fun storeName(soft: Boolean, coin: String): String {
        val base = if (soft) "polybot_pulse2" else "polybot_pulse"
        return if (coin == Coins.BTC.id) base else "$base.$coin"
    }

    fun peekPulse(): PulseBot? = pulses[key(soft = false, coin = Coins.current.id)]

    fun peekSoftPulse(): PulseBot? = pulses[key(soft = true, coin = Coins.current.id)]

    /**
     * Move the desk to another coin.
     *
     * Everything that is about the coin follows: the market, the charts, the
     * oracle, the book — and the rules, which are per coin and of which only
     * the ones on the screen run. False when it was already there.
     *
     * A rule stopped holding shares is not abandoned: the position is a
     * five-minute binary that settles itself at the close, and until then the
     * desk's own sell rule can see it like any other holding.
     */
    fun selectCoin(context: Context, id: String?): Boolean {
        if (!Coins.select(id)) return false
        CoinStore(context).save(Coins.current)

        // Whatever was running belonged to the coin that just left.
        val leaving = synchronized(this) { pulses.entries.toList() }
        for ((key, bot) in leaving) {
            if (!key.endsWith(".${Coins.current.id}") && bot.running) bot.stop()
        }

        peek()?.switchCoin()

        // And the new coin's rules pick up where their own record left off.
        pulse(context)
        pulseSoft(context)
        onState?.invoke()
        onServiceState?.invoke()
        return true
    }

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine

    fun peekAutoSell(): AutoSell? = autoSell
}
