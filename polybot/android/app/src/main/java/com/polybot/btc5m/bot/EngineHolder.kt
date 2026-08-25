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
    private var pair: PairEngine? = null

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

    /**
     * The pair strategy shares the price feed, the journal and the signing
     * session with the main engine; it runs its own loop and its own book.
     */
    fun pair(context: Context): PairEngine {
        pair?.let { return it }
        val host = get(context)
        return synchronized(this) {
            pair ?: PairEngine(
                feed = host.feed,
                journal = host.journal,
                store = PairStore(context),
                session = { host.session() },
                marketNow = { host.currentMarket() },
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
                onLog = { entry -> onLogEntry?.invoke(entry) },
            ).also { pair = it }
        }
    }

    /** Standing sell rule for hand trading, sharing the engine's session. */
    fun autoSell(context: Context): AutoSell {
        autoSell?.let { return it }
        val host = get(context)
        return synchronized(this) {
            autoSell ?: AutoSell(
                engine = host,
                botShares = { asset -> heldByBots(host, asset) },
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
     * How many shares of one outcome the running bots are holding.
     *
     * A dry run holds nothing real, so a paper cycle contributes zero — the
     * wallet's shares in that case are all the user's.
     */
    private fun heldByBots(host: BotEngine, asset: String): Double {
        var held = 0.0

        if (host.running) {
            val cycle = host.current
            val market = cycle?.market
            val entry = cycle?.entry
            if (market != null && entry != null && !entry.dryRun) {
                val token =
                    if (entry.side == "Up") market.up.tokenId else market.down.tokenId
                if (token == asset) {
                    val gone = cycle.exits.sumOf { it.matched } + cycle.soldAtMarket
                    held += (entry.shares - gone).coerceAtLeast(0.0)
                }
            }
        }

        pair?.takeIf { it.running }?.book?.let { book ->
            if (!book.dryRun) {
                when (asset) {
                    book.market?.up?.tokenId -> held += book.up.shares
                    book.market?.down?.tokenId -> held += book.down.shares
                }
            }
        }
        return held
    }

    /** Null when nothing has touched the engine yet this process. */
    fun peek(): BotEngine? = engine

    fun peekAutoSell(): AutoSell? = autoSell

    fun peekPair(): PairEngine? = pair
}
