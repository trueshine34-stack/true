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

    /*
      One engine and one rule per wallet.

      Not one of each with the account swapped underneath: a position bought on
      the second wallet has to keep being sold while the screen is showing the
      first, and a rule that followed the screen would stop working the moment
      you swiped away from it. So both run, side by side, each on its own slot.
    */
    private val engines = java.util.concurrent.ConcurrentHashMap<Int, BotEngine>()

    private val rules = java.util.concurrent.ConcurrentHashMap<Int, AutoSell>()

    @Volatile
    var onState: (() -> Unit)? = null

    @Volatile
    var onLogEntry: ((LogEntry) -> Unit)? = null

    /** Lets the running service refresh its notification on every change. */
    @Volatile
    var onServiceState: (() -> Unit)? = null

    fun get(context: Context, slot: Int = Wallets.current): BotEngine {
        engines[slot]?.let { return it }
        return synchronized(this) {
            // Before the engine exists, because its feeds read the coin as
            // they start: an engine built on bitcoin and switched a moment
            // later opens two sets of sockets to arrive where it was told.
            Coins.select(CoinStore(context).load().id)
            Wallets.restore(context)
            engines[slot] ?: BotEngine(
                journal = Journal(context).also { it.prune() },
                slot = slot,
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
                onLog = { entry -> onLogEntry?.invoke(entry) },
            ).also {
                engines[slot] = it
                // Before anything can spend: a reserve the app forgot on a
                // restart is money it would quietly go and trade with. Per
                // wallet, because holding money back is a decision about one
                // account's balance.
                val (lockedUsd, lockedPct) = LockStore(context, slot).load()
                it.lockedUsd = lockedUsd
                it.lockedPct = lockedPct
                // And the clock's own cue, which runs whether or not the
                // screen is ever opened.
                Countdown.set(CueStore(context).load())
                // Quotes, positions and the price feed are screen data: they
                // must flow from the moment the app opens.
                it.startFeed()
            }
        }
    }

    /**
     * Wake every wallet that has a key, whether or not it is the one on screen.
     *
     * This is what makes two accounts trade at once rather than one at a time:
     * each gets its engine and its rule built and its feed running, and the
     * screen only decides which of them is being looked at.
     */
    fun wakeAll(context: Context) {
        for (slot in Wallets.connected(context)) autoSell(context, slot)
    }

    /** Every engine that has been woken, for the sweeps that are not per screen. */
    fun engines(): List<BotEngine> = engines.values.toList()

    fun rules(): List<AutoSell> = rules.values.toList()

    /** Standing sell rule for hand trading, sharing the engine's session. */
    fun autoSell(context: Context, slot: Int = Wallets.current): AutoSell {
        rules[slot]?.let { return it }
        val host = get(context, slot)
        return synchronized(this) {
            rules[slot] ?: AutoSell(
                engine = host,
                onStateChanged = {
                    onState?.invoke()
                    onServiceState?.invoke()
                },
            ).also {
                rules[slot] = it
                // A sell is only ever needed just after a buy, so that is when
                // the rule starts looking.
                host.onBought = { asset -> it.watch(asset) }
            }
        }
    }

    /**
     * Move the desk to another wallet.
     *
     * Only the screen moves. Both engines go on running, both rules go on
     * selling what they hold — what changes is whose balance, whose orders and
     * whose log the panels are asking for.
     */
    fun selectWallet(context: Context, slot: Int): Boolean {
        if (!Wallets.select(context, slot)) return false
        get(context, slot)
        onState?.invoke()
        onServiceState?.invoke()
        return true
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

        // Every wallet follows: the coin is the market, not the account, and a
        // rule left working the old coin's token would be working a market the
        // desk has stopped watching.
        engines().forEach { it.switchCoin() }
        onState?.invoke()
        onServiceState?.invoke()
        return true
    }

    /** Null when nothing has touched this wallet's engine yet this process. */
    fun peek(slot: Int = Wallets.current): BotEngine? = engines[slot]

    fun peekAutoSell(slot: Int = Wallets.current): AutoSell? = rules[slot]
}
