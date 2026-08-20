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
    private val statsStore: StatsStore,
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

    /** Today's figures, restored from disk so a stop does not erase them. */
    var stats: Stats = statsStore.load()
        private set

    var statsDay: String = statsStore.today()
        private set

    /** What the model has learned about its own confidence, across all days. */
    @Volatile
    var calibration: Calibration = statsStore.loadCalibration()
        private set

    /** Fee parameters for the current market, read once per window. */
    @Volatile
    private var feeRate: Double = 0.0

    @Volatile
    private var feeExponent: Double = 1.0

    val logs = CopyOnWriteArrayList<LogEntry>()

    /** Top of book for both outcomes, refreshed whether or not the bot trades. */
    @Volatile
    var quotes: Quotes? = null
        private set

    @Volatile
    var positions: List<Position> = emptyList()
        private set

    private val logId = AtomicLong(0)
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ambientJob: Job? = null
    private var cachedMarket: Market? = null

    private companion object {
        const val SETTLE_DELAY_MS = 20_000L
        const val SETTLE_RETRIES = 12
        const val SETTLE_RETRY_MS = 10_000L
        const val STRIKE_GRACE_MS = 15_000L
        const val STALE_TICK_MS = 5_000L
        const val MAX_HISTORY = 200
        const val MAX_LOGS = 400
    }

    /** Persist after every change, and start a clean bucket once the day turns. */
    private fun persistStats() {
        statsStore.save(stats)
    }

    private fun rollDayIfNeeded() {
        val today = statsStore.today()
        if (today == statsDay) return
        statsDay = today
        stats = statsStore.load()
        log("info", "Новый день — статистика начата заново")
        onStateChanged()
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

    fun isConfigured(): Boolean = account != null && creds != null && keyPair != null

    fun updateSettings(settings: Settings) {
        this.settings = settings
        onStateChanged()
    }

    fun startFeed() {
        feed.start()
        startAmbient()
    }

    /**
     * Quotes and positions are screen data, not trading data, so they keep
     * refreshing while the bot is stopped — just more slowly, to spare the
     * battery when nothing is happening.
     */
    private fun startAmbient() {
        if (ambientJob != null) return
        ambientJob = ambientScope.launch {
            var round = 0L
            while (isActive) {
                try {
                    refreshQuotes()
                } catch (e: Exception) {
                    // A missed quote is cosmetic; the next round retries.
                }
                if (round % 4 == 0L && isConfigured()) {
                    try {
                        refreshPositions()
                    } catch (e: Exception) {
                        // Same: the position list is a view, not a decision input.
                    }
                }
                round += 1
                onStateChanged()
                delay(if (running) 3_000 else 12_000)
            }
        }
    }

    private fun refreshQuotes() {
        val market = currentMarket() ?: return
        quotes = Quotes(
            up = ClobApi.quote(market.up.tokenId),
            down = ClobApi.quote(market.down.tokenId),
            atMs = System.currentTimeMillis(),
        )
    }

    private fun refreshPositions() {
        val acct = account ?: return
        positions = DataApi.positions(acct.funderAddress)
    }

    fun start() {
        if (running) return
        if (account == null || creds == null) {
            log("error", "Кошелёк не передан в сервис — запуск невозможен")
            return
        }
        running = true
        haltReason = null
        startFeed()

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

    /**
     * Stop trading but stay usable: the feed keeps the price on screen, and the
     * credentials stay loaded so resting orders can still be managed by hand.
     */
    fun shutdown() {
        stop()
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
        rollDayIfNeeded()
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

        maybeTakeProfit(cycle, now)
        maybeAverageDown(cycle, now)
        maybeSyncExit(cycle, now)

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
                    loadFeeParams(market.conditionId)
                }
            }
        } catch (e: Exception) {
            cycle.state = CycleState.SKIPPED
            cycle.note = "ошибка загрузки рынка: ${e.message}"
            log("error", "Не удалось загрузить рынок: ${e.message}")
        }
    }

    /**
     * Mean of the feed inside the settlement averaging window so far, or null
     * while the window has not opened. This is the part of the answer that is
     * already fixed.
     */
    private fun observedWindowMean(cycle: Cycle, now: Long): Double? {
        val windowOpensMs = cycle.windowEnd * 1000 - (TWAP_WINDOW_SECONDS * 1000).toLong()
        if (now <= windowOpensMs) return null
        val ticks = feed.ticksBetween(windowOpensMs, now)
        if (ticks.isEmpty()) return null
        return ticks.sumOf { it.value } / ticks.size
    }

    /** Fee parameters change rarely, but they are per market, so read them once. */
    private fun loadFeeParams(conditionId: String) {
        try {
            val fees = ClobApi.feeParams(conditionId)
            feeRate = fees.first
            feeExponent = fees.second
        } catch (e: Exception) {
            // Leaving the previous values is safer than assuming zero fees.
        }
    }

    private fun preTradeBlock(): String? {
        if (account == null || creds == null) return "кошелёк не подключён"
        if (settings.mode == StrategyMode.OFF) return "режим \"не торговать\""
        if (feed.status != ChainlinkFeed.Status.LIVE) {
            return "фид Chainlink: ${feed.status.name.lowercase()}"
        }
        // Acting on a price the book has already repriced against is not edge,
        // it is being picked off.
        val tickAge = feed.last?.let { System.currentTimeMillis() - it.timestamp }
            ?: Long.MAX_VALUE
        if (tickAge > STALE_TICK_MS) {
            return "цена Chainlink устарела на ${tickAge / 1000} с"
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
            observedWindowMean = observedWindowMean(cycle, now),
            calibration = calibration.shrinkage(),
        )

        try {
            val upBook = ClobApi.getBook(market.up.tokenId)
            val downBook = ClobApi.getBook(market.down.tokenId)
            val stake = settings.stakeUsd
            val upAsk = ClobApi.marketablePrice(upBook, "BUY", stake)
            val downAsk = ClobApi.marketablePrice(downBook, "BUY", stake)

            val decision = Strategy.decide(
                settings, cycle.fair, upAsk, downAsk, feeRate, feeExponent,
            )
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
                persistStats()
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
        persistStats()

        log(
            "trade",
            "${decision.side} · " + String.format("%.2f", shares) + " долей за " +
                String.format("%.2f", cost) + " $ (${result.status ?: "ok"}) — ${decision.reason}",
        )

        cycle.entryFilledAtMs = System.currentTimeMillis()
        cycle.baseCostUsd = cost
    }

    /**
     * Keep the resting sell on the rung the clock calls for.
     *
     * The sell cannot go out the moment the buy matches — the shares are not
     * sellable yet and the exchange rejects it — so it waits `exitDelaySec`
     * after the fill.
     */
    private suspend fun maybeSyncExit(cycle: Cycle, now: Long) {
        if (!settings.exitEnabled || cycle.exitFrozen) return
        val entry = cycle.entry ?: return
        if (entry.dryRun) return
        if (cycle.entryFilledAtMs == 0L) return
        if (now < cycle.entryFilledAtMs + settings.exitDelaySec * 1000L) return

        val elapsedSec = (now - cycle.windowStart * 1000) / 1000
        val target = Strategy.exitPriceFor(settings.exitLadder, elapsedSec)
        val active = cycle.exits.lastOrNull { !it.cancelled }
        if (active != null && active.price == target && !cycle.exitNeedsResync) return

        // Throttle retries so a rejection cannot turn the 250ms loop into a
        // hammer against the exchange.
        if (cycle.lastExitPriceTried == target && now - cycle.lastExitAttemptMs < 5_000) return
        cycle.lastExitPriceTried = target
        cycle.lastExitAttemptMs = now

        withContext(Dispatchers.IO) { syncExit(cycle, target) }
        onStateChanged()
    }

    private fun syncExit(cycle: Cycle, targetPrice: Double) {
        val acct = account ?: return
        val creds = this.creds ?: return
        val market = cycle.market ?: return
        val entry = cycle.entry ?: return

        cycle.exitNeedsResync = false
        val active = cycle.exits.lastOrNull { !it.cancelled }

        // Refresh fills before sizing anything, so a partially filled order is
        // not counted as still held.
        if (active != null) {
            try {
                ClobApi.order(creds, acct.signerAddress, active.orderId)?.let {
                    active.matched = it.sizeMatched
                }
            } catch (e: Exception) {
                // Fall back to what we already know about this order.
            }
        }

        // The truth is the position, not the old order: an average-down grows it
        // and a take-profit shrinks it, and either can happen between rungs.
        val sizeToRest =
            entry.shares - cycle.exits.sumOf { it.matched } - cycle.soldAtMarket

        if (sizeToRest < market.minimumOrderSize) {
            // Cancelling something we cannot re-place only throws away the
            // chance of a fill, so leave it where it is and stop trying.
            cycle.exitFrozen = true
            log(
                "info",
                "Продажу оставляем как есть: остаток " +
                    String.format("%.2f", sizeToRest) + " долей меньше минимума",
            )
            return
        }

        if (active != null) {
            val cancelled = try {
                ClobApi.cancelOrder(creds, acct.signerAddress, active.orderId)
            } catch (e: Exception) {
                log("error", "Не удалось снять продажу: ${e.message}")
                return
            }
            active.cancelled = true
            if (!cancelled) {
                cycle.exitFrozen = true
                log("warn", "Ордер на продажу уже неактивен")
                return
            }
        }

        val tokenId = if (entry.side == "Up") market.up.tokenId else market.down.tokenId
        placeExit(cycle, market, tokenId, sizeToRest, targetPrice)
    }

    /** Top of book for the side we are holding, ignored once it goes stale. */
    private fun liveQuote(side: String): Quote? {
        val q = quotes ?: return null
        if (System.currentTimeMillis() - q.atMs > 15_000) return null
        return if (side == "Up") q.up else q.down
    }

    /**
     * Bank part of the position at market once it has doubled.
     *
     * The resting sell is pulled first: leaving it up would let the ladder and
     * this order compete for the same shares.
     */
    private suspend fun maybeTakeProfit(cycle: Cycle, now: Long) {
        if (!settings.takeProfitEnabled || cycle.takeProfitDone) return
        val entry = cycle.entry ?: return
        if (entry.dryRun) return
        if (now < cycle.entryFilledAtMs + settings.exitDelaySec * 1000L) return

        val market = cycle.market ?: return
        // We would be selling into the bid, so that is the price that counts.
        val bid = liveQuote(entry.side)?.bestBid ?: return
        if (bid < entry.price * settings.takeProfitMultiple) return

        val held = entry.shares - cycle.exits.sumOf { it.matched } - cycle.soldAtMarket
        val wanted = minOf(entry.shares * settings.takeProfitFraction, held)
        if (wanted < market.minimumOrderSize) {
            cycle.takeProfitDone = true
            log(
                "info",
                "Тейк-профит пропущен: " + String.format("%.2f", wanted) +
                    " долей меньше минимума ${market.minimumOrderSize}",
            )
            return
        }

        cycle.takeProfitDone = true
        withContext(Dispatchers.IO) {
            val acct = account ?: return@withContext
            val creds = this@BotEngine.creds ?: return@withContext

            cancelActiveExit(cycle, acct, creds)

            val tokenId =
                if (entry.side == "Up") market.up.tokenId else market.down.tokenId
            val sold = marketSell(market, tokenId, wanted)
            if (sold != null) {
                cycle.soldAtMarket += sold.first
                cycle.marketProceedsUsd += sold.second
                log(
                    "trade",
                    "Тейк-профит: продано " + String.format("%.2f", sold.first) +
                        " долей за " + String.format("%.2f", sold.second) +
                        " $ по рынку (бид ${cents(bid)})",
                )
            }
            // Whatever is left goes back on the ladder at the current rung.
            cycle.exitNeedsResync = true
            cycle.exitFrozen = false
            cycle.lastExitPriceTried = null
        }
        onStateChanged()
    }

    /**
     * Buy the same amount again once the price has halved.
     *
     * Blocked near the close: late in a window a halved price usually means the
     * outcome is nearly decided rather than that the side got cheap.
     */
    private suspend fun maybeAverageDown(cycle: Cycle, now: Long) {
        if (!settings.averageDownEnabled) return
        if (cycle.averageDownCount >= settings.averageDownMaxTimes) return
        val entry = cycle.entry ?: return
        if (entry.dryRun) return
        if (now > cycle.windowEnd * 1000 - settings.averageDownDeadlineSec * 1000L) return

        val market = cycle.market ?: return
        val ask = liveQuote(entry.side)?.bestAsk ?: return
        if (ask > entry.price * settings.averageDownMultiple) return

        val stake = if (cycle.baseCostUsd > 0) cycle.baseCostUsd else entry.costUsd
        val sized = sizeOrder(ask, stake, market.minimumOrderSize)
        if (sized == null) {
            cycle.averageDownCount = settings.averageDownMaxTimes
            log("info", "Докупка пропущена: сумма даёт меньше минимума биржи")
            return
        }

        cycle.averageDownCount += 1
        withContext(Dispatchers.IO) {
            val acct = account ?: return@withContext
            val creds = this@BotEngine.creds ?: return@withContext
            val keys = keyPair ?: return@withContext

            val tokenId =
                if (entry.side == "Up") market.up.tokenId else market.down.tokenId
            val cfg = Orders.roundConfigFor(market.tickSize)
            val amounts = Orders.marketOrderAmounts("BUY", sized.second, ask, cfg)
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
                ClobApi.postOrder(order, creds, acct.signerAddress, "FOK")
            } catch (e: Exception) {
                log("error", "Докупка не прошла: ${e.message}")
                return@withContext
            }
            if (!result.success) {
                log("error", "Докупка отклонена: ${result.error ?: "отказ CLOB"}")
                return@withContext
            }

            val addedShares = result.takingAmount ?: sized.first
            val addedCost = result.makingAmount ?: sized.second
            val shares = entry.shares + addedShares
            val cost = entry.costUsd + addedCost
            cycle.entry = entry.copy(
                shares = shares,
                costUsd = cost,
                price = if (shares > 0) cost / shares else entry.price,
            )
            stats.trades += 1
            stats.stakedUsd += addedCost
            persistStats()

            log(
                "trade",
                "Докупка ${entry.side}: " + String.format("%.2f", addedShares) +
                    " долей за " + String.format("%.2f", addedCost) +
                    " $ по ${cents(ask)}. Средняя " +
                    cents(if (shares > 0) cost / shares else entry.price),
            )

            // A bigger position needs a bigger resting sell, and the doubling
            // target now sits on the new, lower average.
            cycle.exitNeedsResync = true
            cycle.exitFrozen = false
            cycle.lastExitPriceTried = null
            cycle.takeProfitDone = false
        }
        onStateChanged()
    }

    private fun cancelActiveExit(cycle: Cycle, acct: Account, creds: Credentials) {
        val active = cycle.exits.lastOrNull { !it.cancelled } ?: return
        try {
            ClobApi.order(creds, acct.signerAddress, active.orderId)?.let {
                active.matched = it.sizeMatched
            }
            ClobApi.cancelOrder(creds, acct.signerAddress, active.orderId)
        } catch (e: Exception) {
            log("warn", "Не удалось снять лимитку перед продажей: ${e.message}")
        }
        active.cancelled = true
    }

    /** Returns (shares sold, USDC received), or null when the book is too thin. */
    private fun marketSell(
        market: Market,
        tokenId: String,
        shares: Double,
    ): Pair<Double, Double>? {
        val acct = account ?: return null
        val creds = this.creds ?: return null
        val keys = keyPair ?: return null

        return try {
            val book = ClobApi.getBook(tokenId)
            val price = ClobApi.marketablePrice(book, "SELL", shares)
            if (price == null) {
                log("warn", "Продажа по рынку отменена: в стакане не хватает объёма")
                return null
            }
            val cfg = Orders.roundConfigFor(market.tickSize)
            val amounts = Orders.marketOrderAmounts("SELL", shares, price, cfg)
            val order = Orders.buildAndSign(
                keyPair = keys,
                signerAddress = acct.signerAddress,
                funder = acct.funderAddress,
                signatureType = acct.signatureType,
                tokenId = tokenId,
                side = "SELL",
                amounts = amounts,
                negRisk = market.negRisk,
            )
            val result = ClobApi.postOrder(order, creds, acct.signerAddress, "FOK")
            if (!result.success) {
                log("error", "Продажа по рынку отклонена: ${result.error ?: "отказ CLOB"}")
                return null
            }
            // A SELL makes shares and takes USDC.
            Pair(result.makingAmount ?: shares, result.takingAmount ?: shares * price)
        } catch (e: Exception) {
            log("error", "Продажа по рынку не прошла: ${e.message}")
            null
        }
    }

    private fun placeExit(
        cycle: Cycle,
        market: Market,
        tokenId: String,
        size: Double,
        price: Double,
    ) {
        val acct = account ?: return
        val creds = this.creds ?: return
        val keys = keyPair ?: return

        try {
            val cfg = Orders.roundConfigFor(market.tickSize)
            val amounts = Orders.limitOrderAmounts("SELL", size, price, cfg)
            val order = Orders.buildAndSign(
                keyPair = keys,
                signerAddress = acct.signerAddress,
                funder = acct.funderAddress,
                signatureType = acct.signatureType,
                tokenId = tokenId,
                side = "SELL",
                amounts = amounts,
                negRisk = market.negRisk,
            )
            val result = ClobApi.postOrder(order, creds, acct.signerAddress, "GTC")
            if (!result.success || result.orderId == null) {
                log("error", "Продажа по ${cents(price)} не принята: ${result.error ?: "отказ CLOB"}")
                return
            }
            cycle.exits.add(ExitOrder(result.orderId, price, size))
            log(
                "trade",
                "Продажа " + String.format("%.2f", size) + " долей по ${cents(price)}",
            )
        } catch (e: Exception) {
            log("error", "Не удалось выставить продажу: ${e.message}")
        }
    }

    private fun cents(price: Double): String = String.format("%.0f", price * 100) + "¢"

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
        // Grade every window that produced a forecast, not only the ones that
        // were traded: a skipped window is still evidence about the model, and
        // scoring only the traded ones would bias the calibration.
        if (cycle.entry != null || cycle.fair != null) {
            scope?.launch { settle(cycle) }
        }
        onStateChanged()
    }

    private suspend fun settle(cycle: Cycle) {
        val market = cycle.market ?: return

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

        cycle.winner = resolved

        // Grade the forecast before anything else, and grade it whether or not
        // the window was traded.
        cycle.fair?.let { fair ->
            calibration = calibration.plus(fair.rawPUp, resolved == "Up")
            statsStore.saveCalibration(calibration)
        }

        val entry = cycle.entry
        if (entry == null) {
            onStateChanged()
            return
        }

        settlePosition(cycle, entry, resolved)
    }

    /** Money side of a settlement: what the sells realised, what rode to the end. */
    private fun settlePosition(cycle: Cycle, entry: Entry, resolved: String) {
        var soldShares = 0.0
        var proceeds = 0.0
        val acct = account
        val creds = this.creds
        for (exit in cycle.exits) {
            if (acct != null && creds != null) {
                try {
                    ClobApi.order(creds, acct.signerAddress, exit.orderId)?.let {
                        exit.matched = it.sizeMatched
                    }
                } catch (e: Exception) {
                    // Keep the last known fill rather than dropping the order.
                }
            }
            soldShares += exit.matched
            proceeds += exit.matched * exit.price
        }
        soldShares += cycle.soldAtMarket
        proceeds += cycle.marketProceedsUsd

        val heldShares = (entry.shares - soldShares).coerceAtLeast(0.0)
        val resolvedWin = resolved == entry.side
        val pnl = proceeds + (if (resolvedWin) heldShares else 0.0) - entry.costUsd

        cycle.pnlUsd = pnl
        cycle.state = CycleState.SETTLED

        stats.realisedPnlUsd += pnl
        // Score by money, not by the outcome: a window can resolve against us
        // and still be green if the sell filled first.
        if (pnl >= 0) {
            stats.wins += 1
            stats.consecutiveLosses = 0
        } else {
            stats.losses += 1
            stats.consecutiveLosses += 1
        }
        persistStats()

        val soldNote = if (soldShares > 0) {
            " Продано " + String.format("%.2f", soldShares) + " долей на " +
                String.format("%.2f", proceeds) + " $."
        } else {
            ""
        }
        log(
            "trade",
            "${formatWindow(cycle.windowStart)} → $resolved.$soldNote " +
                (if (pnl >= 0) "Итог +" else "Итог ") + String.format("%.2f", pnl) + " $",
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

    /**
     * The market of the window in progress, loading it on demand so manual
     * trading works before the bot has been started.
     */
    fun currentMarket(): Market? {
        val windowStart = GammaApi.windowStartFor()
        current?.market?.let { if (it.windowStart == windowStart) return it }
        cachedMarket?.let { if (it.windowStart == windowStart) return it }
        return try {
            GammaApi.marketForWindow(windowStart)?.also { cachedMarket = it }
        } catch (e: Exception) {
            null
        }
    }

    fun openOrders(market: String? = null): List<ClobApi.OpenOrder> {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        return ClobApi.openOrders(creds, acct.signerAddress, market)
    }

    fun cancelOrder(orderId: String): Boolean {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        val ok = ClobApi.cancelOrder(creds, acct.signerAddress, orderId)
        // Keep the ladder honest if the user cancelled the bot's own sell.
        current?.exits?.firstOrNull { it.orderId == orderId }?.cancelled = true
        log("info", if (ok) "Ордер отменён" else "Ордер уже неактивен")
        onStateChanged()
        return ok
    }

    fun cancelMarketOrders(conditionId: String): Int {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        val n = ClobApi.cancelMarketOrders(creds, acct.signerAddress, conditionId)
        current?.exits?.forEach { it.cancelled = true }
        log("info", "Отменено ордеров: $n")
        onStateChanged()
        return n
    }

    /**
     * Place an order by hand. Limit orders size in shares; a market buy sizes
     * in USDC, matching how the venue reads the amount.
     */
    fun placeManualOrder(
        tokenId: String,
        conditionId: String,
        side: String,
        price: Double,
        size: Double,
        orderType: String,
    ): ClobApi.OrderResult {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        val keys = keyPair ?: error("ключ не загружен")

        val meta = ClobApi.marketMeta(conditionId)
        val cfg = Orders.roundConfigFor(meta.tickSize)
        val amounts = if (orderType == "FOK" || orderType == "FAK") {
            Orders.marketOrderAmounts(side, size, price, cfg)
        } else {
            Orders.limitOrderAmounts(side, size, price, cfg)
        }

        val order = Orders.buildAndSign(
            keyPair = keys,
            signerAddress = acct.signerAddress,
            funder = acct.funderAddress,
            signatureType = acct.signatureType,
            tokenId = tokenId,
            side = side,
            amounts = amounts,
            negRisk = meta.negRisk,
        )
        val result = ClobApi.postOrder(order, creds, acct.signerAddress, orderType)
        log(
            if (result.success) "trade" else "error",
            if (result.success) {
                "Ручной ордер $side " + String.format("%.2f", size) + " по " +
                    String.format("%.0f", price * 100) + "¢ (${result.status ?: "ok"})"
            } else {
                "Ручной ордер отклонён: ${result.error ?: "отказ CLOB"}"
            },
        )
        onStateChanged()
        return result
    }

    /** Reads the funder's USDC balance using the credentials held here. */
    fun usdcBalance(): Double {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        return ClobApi.usdcBalance(creds, acct.signerAddress, acct.signatureType)
    }

    fun resetStats() {
        stats = Stats()
        statsDay = statsStore.today()
        statsStore.clear()
        history.clear()
        log("info", "Статистика обнулена вручную")
        onStateChanged()
    }
}
