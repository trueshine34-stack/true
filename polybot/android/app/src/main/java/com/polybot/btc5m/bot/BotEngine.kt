package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 5-minute trading cycle.
 *
 * One coroutine drives the whole loop: resolve the window's market, record the
 * strike from the first Chainlink tick at the boundary, decide after a short
 * delay, and settle once the window has closed. Settlement runs on its own
 * coroutine because it outlives the window that opened it.
 */
class BotEngine(
    private val onStateChanged: () -> Unit,
    private val onLog: (LogEntry) -> Unit,
) {
    val feed = ChainlinkFeed()

    @Volatile
    var settings: Settings = Settings()

    @Volatile
    private var account: Account? = null

    @Volatile
    private var creds: Credentials? = null

    @Volatile
    private var keyPair: Secp256k1.KeyPair? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var haltReason: String? = null
        private set

    @Volatile
    var current: Cycle? = null
        private set

    val history = CopyOnWriteArrayList<Cycle>()
    val stats = Stats()
    val logs = CopyOnWriteArrayList<LogEntry>()

    private val logId = AtomicLong(0)
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    private companion object {
        const val SETTLE_DELAY_MS = 20_000L
        const val SETTLE_RETRIES = 12
        const val SETTLE_RETRY_MS = 10_000L
        const val STRIKE_GRACE_MS = 15_000L
        const val MAX_HISTORY = 200
        const val MAX_LOGS = 400
    }

    fun log(level: String, message: String) {
        val entry = LogEntry(logId.incrementAndGet(), System.currentTimeMillis(), level, message)
        logs.add(0, entry)
        while (logs.size > MAX_LOGS) logs.removeAt(logs.size - 1)
        onLog(entry)
    }

    fun configure(account: Account, creds: Credentials, settings: Settings) {
        this.account = account
        this.creds = creds
        this.settings = settings
        this.keyPair = Secp256k1.keyPairFromPrivateKey(account.privateKey)
    }

    fun updateSettings(settings: Settings) {
        this.settings = settings
        onStateChanged()
    }

    fun startFeed() = feed.start()

    fun start() {
        if (running) return
        if (account == null || creds == null) {
            log("error", "Кошелёк не передан в сервис — запуск невозможен")
            return
        }
        running = true
        haltReason = null
        feed.start()

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        loop = newScope.launch { runLoop() }
        log("info", "Бот запущен в фоновом режиме")
        onStateChanged()
    }

    fun stop(reason: String? = null) {
        if (!running) return
        running = false
        haltReason = reason
        loop?.cancel()
        scope?.cancel()
        scope = null
        loop = null
        log("info", if (reason != null) "Бот остановлен: $reason" else "Бот остановлен")
        onStateChanged()
    }

    fun shutdown() {
        stop()
        feed.stop()
    }

    private suspend fun runLoop() {
        try {
            Clock.sync()
        } catch (e: Exception) {
            log("warn", "Не удалось сверить время с биржей: ${e.message}")
        }

        while (currentCoroutineActive()) {
            try {
                tick()
            } catch (e: Exception) {
                log("error", "Сбой цикла: ${e.message}")
            }
            delay(250)
        }
    }

    private fun currentCoroutineActive(): Boolean = scope?.isActive == true && running

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val windowStart = GammaApi.windowStartFor(now)

        var cycle = current
        if (cycle == null || cycle.windowStart != windowStart) {
            cycle?.let { rollOver(it) }
            cycle = Cycle(windowStart, windowStart + WINDOW_SECONDS)
            current = cycle
            onStateChanged()
        }

        if (cycle.state == CycleState.WAITING) {
            loadMarket(cycle)
            onStateChanged()
        }

        if (cycle.strike == null) {
            val tick = feed.firstTickAtOrAfter(cycle.windowStart * 1000)
            if (tick != null) {
                cycle.strike = tick.value
                log(
                    "info",
                    "Окно ${formatWindow(cycle.windowStart)} — страйк " +
                        String.format("%.2f", tick.value) + " $",
                )
                onStateChanged()
            } else if (now > cycle.windowStart * 1000 + STRIKE_GRACE_MS) {
                cycle.state = CycleState.SKIPPED
                cycle.note = "нет тика Chainlink на открытии окна"
                log("warn", "Окно ${formatWindow(cycle.windowStart)} пропущено: ${cycle.note}")
                onStateChanged()
            }
        }

        val entryAt = cycle.windowStart * 1000 + settings.entryDelaySec * 1000L
        val tooLate = now > cycle.windowEnd * 1000 - 30_000L

        if (cycle.state == CycleState.ARMED && cycle.strike != null && now >= entryAt) {
            if (tooLate) {
                cycle.state = CycleState.SKIPPED
                cycle.note = "слишком поздно для входа в этом окне"
                onStateChanged()
            } else {
                attemptEntry(cycle)
                onStateChanged()
            }
        }
    }

    private suspend fun loadMarket(cycle: Cycle) = withContext(Dispatchers.IO) {
        try {
            val market = GammaApi.marketForWindow(cycle.windowStart)
            when {
                market == null -> {
                    cycle.state = CycleState.SKIPPED
                    cycle.note = "рынок этого окна не найден на Gamma"
                    log("warn", "Окно ${formatWindow(cycle.windowStart)}: рынок не найден")
                }

                !market.acceptingOrders -> {
                    cycle.market = market
                    cycle.state = CycleState.SKIPPED
                    cycle.note = "рынок не принимает ордера"
                    log("warn", "Окно ${formatWindow(cycle.windowStart)}: ордера не принимаются")
                }

                else -> {
                    cycle.market = market
                    cycle.state = CycleState.ARMED
                }
            }
        } catch (e: Exception) {
            cycle.state = CycleState.SKIPPED
            cycle.note = "ошибка загрузки рынка: ${e.message}"
            log("error", "Не удалось загрузить рынок: ${e.message}")
        }
    }

    private fun preTradeBlock(): String? {
        if (account == null || creds == null) return "кошелёк не подключён"
        if (settings.mode == StrategyMode.OFF) return "режим \"не торговать\""
        if (feed.status != ChainlinkFeed.Status.LIVE) {
            return "фид Chainlink: ${feed.status.name.lowercase()}"
        }
        if (stats.realisedPnlUsd <= -kotlin.math.abs(settings.dailyLossLimitUsd)) {
            return "достигнут лимит убытка ${settings.dailyLossLimitUsd} $"
        }
        if (stats.consecutiveLosses >= settings.maxConsecutiveLosses) {
            return "${stats.consecutiveLosses} убытков подряд"
        }
        return null
    }

    private suspend fun attemptEntry(cycle: Cycle) = withContext(Dispatchers.IO) {
        val market = cycle.market ?: return@withContext
        val strike = cycle.strike ?: return@withContext

        val block = preTradeBlock()
        if (block != null) {
            cycle.state = CycleState.SKIPPED
            cycle.note = block
            log("warn", "Окно ${formatWindow(cycle.windowStart)} пропущено: $block")
            if (block.startsWith("достигнут лимит") || block.contains("убытков подряд")) {
                stop(block)
            }
            return@withContext
        }

        val spot = feed.last?.value
        if (spot == null) {
            cycle.state = CycleState.SKIPPED
            cycle.note = "нет текущей цены"
            return@withContext
        }
        cycle.spotAtEntry = spot

        val now = System.currentTimeMillis()
        cycle.fair = Strategy.fairValue(
            strike = strike,
            spot = spot,
            msToClose = cycle.windowEnd * 1000 - now,
            ticks = feed.ticksBetween(now - 600_000, now),
        )

        try {
            val upBook = ClobApi.getBook(market.up.tokenId)
            val downBook = ClobApi.getBook(market.down.tokenId)
            val stake = settings.stakeUsd
            val upAsk = ClobApi.marketablePrice(upBook, "BUY", stake)
            val downAsk = ClobApi.marketablePrice(downBook, "BUY", stake)

            val decision = Strategy.decide(settings, cycle.fair, upAsk, downAsk)
            if (!decision.act || decision.side == null) {
                cycle.state = CycleState.SKIPPED
                cycle.note = decision.reason
                log(
                    "info",
                    "Окно ${formatWindow(cycle.windowStart)}: не входим — ${decision.reason}",
                )
                return@withContext
            }

            val sized = sizeOrder(decision.price, stake, market.minimumOrderSize)
            if (sized == null) {
                cycle.state = CycleState.SKIPPED
                cycle.note = "$stake $ по цене ${decision.price} даёт меньше минимума " +
                    "в ${market.minimumOrderSize} долей"
                log("warn", "Окно ${formatWindow(cycle.windowStart)}: ${cycle.note}")
                return@withContext
            }
            if (sized.second != stake) {
                log(
                    "info",
                    "Ставка поднята с " + String.format("%.2f", stake) + " $ до " +
                        String.format("%.2f", sized.second) + " $ — минимум " +
                        "${market.minimumOrderSize} долей",
                )
            }

            val tokenId =
                if (decision.side == "Up") market.up.tokenId else market.down.tokenId

            if (settings.dryRun) {
                cycle.entry = Entry(
                    decision.side, decision.price, sized.first, sized.second, null, true,
                )
                cycle.state = CycleState.ENTERED
                stats.trades += 1
                stats.stakedUsd += sized.second
                log(
                    "trade",
                    "[ТЕСТ] ${decision.side} · " + String.format("%.2f", sized.first) +
                        " долей по " + String.format("%.0f", decision.price * 100) + "¢ = " +
                        String.format("%.2f", sized.second) + " $ — ${decision.reason}",
                )
                return@withContext
            }

            postOrder(cycle, market, decision, sized, tokenId)
        } catch (e: Exception) {
            cycle.state = CycleState.FAILED
            cycle.note = e.message
            log("error", "Сбой при входе в позицию: ${e.message}")
        }
    }

    private fun postOrder(
        cycle: Cycle,
        market: Market,
        decision: Decision,
        sized: Pair<Double, Double>,
        tokenId: String,
    ) {
        val acct = account ?: return
        val creds = this.creds ?: return
        val keys = keyPair ?: return

        val cfg = Orders.roundConfigFor(market.tickSize)
        val amounts = Orders.marketOrderAmounts("BUY", sized.second, decision.price, cfg)
        val order = Orders.buildAndSign(
            keyPair = keys,
            signerAddress = acct.signerAddress,
            funder = acct.funderAddress,
            signatureType = acct.signatureType,
            tokenId = tokenId,
            side = "BUY",
            amounts = amounts,
            negRisk = market.negRisk,
        )

        val result = try {
            ClobApi.postOrder(order, creds, acct.signerAddress)
        } catch (e: Exception) {
            cycle.state = CycleState.FAILED
            cycle.note = "ордер отклонён: ${e.message}"
            log("error", "Ордер не прошёл (${formatWindow(cycle.windowStart)}): ${e.message}")
            return
        }

        if (!result.success || result.error != null) {
            cycle.state = CycleState.FAILED
            cycle.note = result.error ?: "CLOB отклонил ордер"
            log("error", "Ордер отклонён: ${cycle.note}")
            return
        }

        val shares = result.takingAmount ?: sized.first
        val cost = result.makingAmount ?: sized.second
        cycle.entry = Entry(
            side = decision.side!!,
            price = if (shares > 0) cost / shares else decision.price,
            shares = shares,
            costUsd = cost,
            orderId = result.orderId,
            dryRun = false,
        )
        cycle.state = CycleState.ENTERED
        stats.trades += 1
        stats.stakedUsd += cost

        log(
            "trade",
            "${decision.side} · " + String.format("%.2f", shares) + " долей за " +
                String.format("%.2f", cost) + " $ (${result.status ?: "ok"}) — ${decision.reason}",
        )
    }

    /**
     * The venue enforces a minimum order size in shares, which a $2 stake misses
     * whenever a share costs more than $0.40. Raising the stake to exactly the
     * minimum keeps the bot trading; refusing keeps the stake honest.
     */
    private fun sizeOrder(
        price: Double,
        stakeUsd: Double,
        minShares: Double,
    ): Pair<Double, Double>? {
        val shares = stakeUsd / price
        if (shares >= minShares) return Pair(shares, stakeUsd)
        if (!settings.autoBumpToMinimum) return null
        // Round up to a whole cent so the venue's own rounding cannot drop us
        // back under the minimum.
        val amountUsd = ceil(minShares * price * 100.0) / 100.0
        return Pair(amountUsd / price, amountUsd)
    }

    private fun rollOver(cycle: Cycle) {
        history.add(0, cycle)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        if (cycle.entry != null) {
            scope?.launch { settle(cycle) }
        }
        onStateChanged()
    }

    private suspend fun settle(cycle: Cycle) {
        val market = cycle.market ?: return
        val entry = cycle.entry ?: return

        val waitMs = cycle.windowEnd * 1000 + SETTLE_DELAY_MS - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)

        var winner: String? = null
        repeat(SETTLE_RETRIES) {
            if (winner != null) return@repeat
            winner = try {
                ClobApi.resolvedWinner(market.conditionId)
            } catch (e: Exception) {
                null
            }
            if (winner == null) delay(SETTLE_RETRY_MS)
        }

        if (winner == null) {
            // Fall back to the same feed the resolver uses: the average of the
            // last 30 seconds of the window against the strike.
            winner = settleFromFeed(cycle)
            if (winner != null) {
                cycle.note = listOfNotNull(cycle.note, "исход посчитан по фиду, а не по CLOB")
                    .joinToString("; ")
            }
        }

        val resolved = winner
        if (resolved == null) {
            cycle.note = listOfNotNull(cycle.note, "исход не определён").joinToString("; ")
            onStateChanged()
            return
        }

        val won = resolved == entry.side
        val pnl = (if (won) entry.shares else 0.0) - entry.costUsd

        cycle.winner = resolved
        cycle.pnlUsd = pnl
        cycle.state = CycleState.SETTLED

        stats.realisedPnlUsd += pnl
        if (won) {
            stats.wins += 1
            stats.consecutiveLosses = 0
        } else {
            stats.losses += 1
            stats.consecutiveLosses += 1
        }

        log(
            "trade",
            "${formatWindow(cycle.windowStart)} → $resolved. " +
                (if (won) "Выигрыш " else "Проигрыш ") +
                (if (pnl >= 0) "+" else "") + String.format("%.2f", pnl) + " $",
        )
        onStateChanged()
    }

    private fun settleFromFeed(cycle: Cycle): String? {
        val strike = cycle.strike ?: return null
        val endMs = cycle.windowEnd * 1000
        val ticks = feed.ticksBetween(endMs - 30_000, endMs)
        if (ticks.size < 5) return null
        val twap = ticks.sumOf { it.value } / ticks.size
        return if (twap >= strike) "Up" else "Down"
    }

    private fun formatWindow(windowStart: Long): String {
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return format.format(java.util.Date(windowStart * 1000))
    }

    /** Reads the funder's USDC balance using the credentials held here. */
    fun usdcBalance(): Double {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        return ClobApi.usdcBalance(creds, acct.signerAddress, acct.signatureType)
    }

    fun resetStats() {
        stats.trades = 0
        stats.wins = 0
        stats.losses = 0
        stats.consecutiveLosses = 0
        stats.realisedPnlUsd = 0.0
        stats.stakedUsd = 0.0
        history.clear()
        onStateChanged()
    }
}
