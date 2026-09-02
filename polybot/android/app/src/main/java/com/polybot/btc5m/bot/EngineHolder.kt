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
     * Move the desk to another coin.
     *
     * Everything that is about the coin follows: the market, the charts, the
     * oracle and the book. False when it was already there.
     *
     * A position on the coin being left is not abandoned: a five-minute binary
     * settles itself, and until then the sell rule works its ladder on the
     * token it holds, whatever the screen happens to be showing.
     */
    fun selectCoin(context: Context, id: String?): Boolean {
        if (!Coins.select(id)) return false
        CoinStore(context).save(Coins.current)

        peek()?.switchCoin()
        onState?.invoke()
        onServiceState?.invoke()
        return true
    }

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine

    fun peekAutoSell(): AutoSell? = autoSell
}
