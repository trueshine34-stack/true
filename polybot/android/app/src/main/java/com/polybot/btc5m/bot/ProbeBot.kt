package com.polybot.btc5m.bot

import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The experiment, run once per window and written down.
 *
 * Ten seconds before a window opens it buys five dollars of the side the
 * minute chart's line is pointing at, and then does nothing else: the exit is
 * the sell ladder. That is the whole design, and it is deliberate — the
 * question being asked is whether following that line pays, so the only thing
 * this rule is allowed to be clever about is the direction.
 *
 * On paper, by default. In demo it reads the same live book, takes the same
 * offers at the same prices, pays the same fee and leaves by the same rungs —
 * only the money is imaginary and nothing is sent to the venue. That is the
 * point: a question about whether a signal pays should be answered before the
 * money is asked to answer it, and a bot that cannot trade because the wallet
 * is empty has answered nothing.
 *
 * What it does carefully either way is keep the books. Every round is recorded
 * when its window settles: the side, how strong the line was, what the shares
 * cost, what the ladder got for them, and what the market paid on anything
 * still held. That record is the report, and it is the only reason the rule
 * exists.
 */
class ProbeBot(
    private val engine: BotEngine,
    private val store: ProbeStore,
    /** The desk's own sell rule as it is set, which demo exits follow. */
    private val exit: () -> AutoSell.Settings,
    private val onStateChanged: () -> Unit,
) {

    /** One window, from the entry to the settlement. */
    data class Round(
        val windowStart: Long,
        val asset: String,
        /** Paper money: nothing about this round reached the venue. */
        val demo: Boolean,
        val side: String,
        /** How fast the line was climbing or falling when it was followed. */
        val perHour: Double,
        val shares: Double,
        val price: Double,
        val sold: Double = 0.0,
        val proceeds: Double = 0.0,
        val settled: Double = 0.0,
        val winner: String = "",
        val note: String? = null,
        /**
         * A bid left waiting at this price, with nothing bought yet.
         *
         * The side opened dearer than the rule will pay at the market, so
         * instead of standing the window out it left an order where it is
         * willing to buy. Until something fills, [shares] is zero.
         */
        val resting: Double = 0.0,
        /**
         * The price this trade is aiming at: the next level in the side's own
         * direction, taken at entry.
         *
         * Trading is level to level. What is available in five minutes is the
         * distance to the next price the market stops at, and once it is there
         * the move is finished whatever the outcome's own quote is doing —
         * so reaching it closes the position rather than waiting for a rung.
         */
        val target: Double = 0.0,
        /** Whether the same money has already gone in a second time. */
        val added: Boolean = false,
        /** Best bid seen while holding, which is what walks the ladder up. */
        val highWater: Double = 0.0,

        /** The rung reached, so a paper exit cannot slide back down. */
        val rung: Int = 0,
    ) {
        val cost: Double get() = shares * price
        val pnl: Double get() = proceeds + settled - cost
        /** Shares the ladder never sold, which the window settled instead. */
        val left: Double get() = (shares - sold).coerceAtLeast(0.0)
        val right: Boolean get() = winner.isNotEmpty() && winner == side
    }

    @Volatile
    var settings: ProbePlan.Settings = store.loadSettings()
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastFault: String? = null
        private set

    /** Closed rounds, oldest first — the report reads this. */
    @Volatile
    var rounds: List<Round> = store.loadRounds()
        private set

    /**
     * What the current winning run has added to the stake. Zero after a loss,
     * which is the whole of the rule: the run stakes winnings, never the base.
     */
    @Volatile
    var streak: Double = store.loadStreak()
        private set

    /** Bought and still riding, usually one and briefly two at a boundary. */
    @Volatile
    var working: List<Round> = emptyList()
        private set

    /** The line as the rule last read it, so the card shows what it sees. */
    @Volatile
    var trend: TrendFit.Trend? = null
        private set

    /** And the five-minute one it has to agree with. */
    @Volatile
    var wide: TrendFit.Trend? = null
        private set

    /** Where the reversal is expected, and how much room is left to it. */
    @Volatile
    var levelAhead: Double? = null
        private set

    @Volatile
    var roomToLevel: Double? = null
        private set

    /**
     * The candles in progress, which close as the window opens. Positive is
     * green; zero when there is nothing to say.
     */
    @Volatile
    var candleBody: Double = 0.0
        private set

    @Volatile
    var minuteBody: Double = 0.0
        private set

    /** The round five hundred nearest the settlement price, and how far off. */
    @Volatile
    var roundNear: Double? = null
        private set

    @Volatile
    var roomToRound: Double? = null
        private set

    /** Why nothing is being bought right now, in the person's words. */
    @Volatile
    var note: String? = null
        private set

    /**
     * Why the last side was not simply the line's — "разворот" or "коррекция
     * от уровня" — and null when it was.
     */
    @Volatile
    var chose: String? = null
        private set

    /**
     * What the paper account is worth: what it started with, plus everything
     * closed rounds came to, less what is currently in the market.
     *
     * Only demo rounds count. A run that traded for real and then switched to
     * paper should not have its paper balance moved by real money, and the
     * other way round.
     */
    /** Everything closed rounds of the current mode have made. */
    val won: Double
        get() = rounds
            .filter { it.demo == settings.demo && it.shares > 0.0 }
            .sumOf { it.pnl }

    /**
     * What the next window is worth staking: the base, grown by every time the
     * account has doubled, plus whatever the winning run has added.
     */
    val stakeNow: Double
        get() = ProbePlan.stakeFor(settings.stakeUsd, won, settings.bankUsd, streak)

    val bank: Double
        get() = settings.bankUsd +
            rounds.filter { it.demo && it.shares > 0.0 }.sumOf { it.pnl } -
            working.filter { it.demo }.sumOf { it.cost }

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** The window the current chance is for, and why it has not gone in yet. */
    private var aiming = 0L
    private var aimNote: String? = null

    private companion object {
        /** A ten-second lead needs a clock, not a poll. */
        const val TICK_MS = 1_000L

        const val WINDOW_SEC = 300L

        /**
         * How long after the close to wait before scoring the round.
         *
         * The price series needs a moment to carry the window's last point,
         * and a round scored against a series that has not caught up is a
         * round scored against the wrong close.
         */
        const val SETTLE_SEC = 20L

        /** After this, an unscoreable round is filed as it stands. */
        const val GIVE_UP_SEC = 180L

    }

    fun update(next: ProbePlan.Settings) {
        settings = next
        store.saveSettings(next)
        when {
            next.enabled && !running -> start()
            !next.enabled && running -> stop()
            else -> onStateChanged()
        }
    }

    /** Wipes the record. The settings, and anything riding, are left alone. */
    fun reset() {
        rounds = emptyList()
        streak = 0.0
        store.clearRounds()
        onStateChanged()
    }

    fun start() {
        if (running) return
        running = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            var backoffMs = 0L
            while (isActive && running) {
                try {
                    tick()
                    backoffMs = 0L
                } catch (e: Exception) {
                    lastFault = e.message ?: "сбой пробы"
                    backoffMs = if (backoffMs == 0L) 10_000L else minOf(backoffMs * 2, 60_000L)
                }
                delay(if (backoffMs > 0L) backoffMs else TICK_MS)
            }
        }
        engine.log(
            "info",
            "Проба включена: $" + String.format("%.0f", settings.stakeUsd) +
                " по тренду за " + settings.leadSec + " с до открытия",
        )
        onStateChanged()
    }

    fun stop() {
        if (!running) return
        running = false
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        note = null
        engine.log("info", "Проба выключена")
        onStateChanged()
    }

    /**
     * The price the window will open on.
     *
     * Not the chart's price: the market settles against Polymarket's own
     * sixty-second average, and "is the open sitting on a round number" is a
     * question about that series and no other. The candles stand in only while
     * the socket has nothing to say.
     */
    private fun body(candle: BinanceCandles.Candle?): Double =
        candle?.let { if (it.open > 0.0 && it.close > 0.0) it.close - it.open else 0.0 } ?: 0.0

    private fun here(): Double =
        engine.feed.twap60?.value
            ?: engine.feed.twap?.value
            ?: BinanceCandles.oneMinute.list().lastOrNull()?.close
            ?: 0.0

    private fun tick() {
        val nowSec = Clock.nowSec()

        // Scoring first: a round whose window has settled is finished business
        // and should be off the books before the next entry is considered.
        settleDue(nowSec)
        // And the paper orders are worked, since no rule on the desk can see
        // them: first the bids that are waiting, then the ladder over anything
        // that has been bought.
        if (settings.enabled) {
            fillResting(nowSec)
            readSales()
            // A side that has fallen far enough is bought again, once, while
            // the window still has time to turn.
            addUp(nowSec)
            workPaper(nowSec)
        }

        val session = engine.session()
        if (session == null) {
            lastFault = "кошелёк не подключён"
            // So the window this costs is filed with the reason rather than
            // as a silent gap.
            aimNote = lastFault
            onStateChanged()
            return
        }
        lastFault = null

        val elapsed = SellLadder.elapsedInWindow(nowSec)
        val secondsLeft = WINDOW_SEC - elapsed
        val windowStart = nowSec - elapsed

        trend = TrendFit.onScreen()
        wide = TrendFit.wide()
        readLevel()

        if (!settings.enabled) {
            note = "выключен"
            onStateChanged()
            return
        }

        val target = ProbePlan.targetWindow(windowStart, elapsed, settings, WINDOW_SEC)
        // A chance that has come and gone is filed with the reason it did, so
        // "why did it not trade that one" is a question the report answers.
        if (target != aiming) {
            giveUp(nowSec)
            aiming = target ?: 0L
        }
        if (target == null) {
            note = "жду открытия: " + (secondsLeft - settings.leadSec) + " с"
            onStateChanged()
            return
        }
        if (traded(target)) {
            note = "окно уже отыграно"
            onStateChanged()
            return
        }

        enter(target)
        aimNote = note
        onStateChanged()
    }

    /**
     * Files the window whose chance has just run out.
     *
     * Every window gets a line in the record — a trade, or the reason there
     * was not one. A rule that silently does nothing is a rule nobody can
     * tell from a broken one, which is exactly what happened the first time
     * this ran.
     */
    private fun giveUp(nowSec: Long) {
        val missed = aiming
        aiming = 0L
        if (missed <= 0L || traded(missed)) return

        val skipped = Round(
            windowStart = missed,
            asset = "",
            demo = settings.demo,
            side = TrendFit.lean(trend),
            perHour = trend?.perHour ?: 0.0,
            shares = 0.0,
            price = 0.0,
            winner = EventStats.winnerFor(missed, nowSec),
            note = aimNote ?: "не успел",
        )
        rounds = rounds + skipped
        store.saveRounds(rounds)
        engine.log("warn", "Проба: пропустила окно " + hhmm(missed) + " — " + skipped.note)
    }

    private fun traded(windowStart: Long): Boolean =
        working.any { it.windowStart == windowStart } ||
            rounds.any { it.windowStart == windowStart }

    /**
     * Buys into the window that is about to open.
     *
     * Gamma publishes the next market shortly before it opens, so the entry is
     * only possible once it is there — which is the same reason the lead is
     * ten seconds and not two minutes.
     */
    private fun enter(windowStart: Long) {
        val line = trend

        val market = engine.marketForWindow(windowStart)
        if (market == null) {
            note = "рынок ещё не открыт"
            return
        }

        val here = here()
        val typical = Levels.typicalRange(BinanceCandles.fiveMinute.list())
        // The five-minute candle that closes as this window opens: at twenty
        // seconds out its shape is already decided enough to read.
        val closing = BinanceCandles.fiveMinute.list().lastOrNull()
        val body = closing
            ?.let { if (it.open > 0.0 && it.close > 0.0) it.close - it.open else 0.0 }
            ?: 0.0

        // Whether the line was walking into something. Either kind of wall
        // counts: a price the market has turned at before, or one of the round
        // five hundreds where the book always is.
        val intoWall = ProbePlan.tooClose(here, levelAhead, typical, settings.roomShare) ||
            ProbePlan.nearRound(here, settings.roundBand) != null

        val pick = ProbePlan.choose(
            way = TrendFit.lean(line),
            wide = TrendFit.lean(wide),
            candleBody = body,
            typical = typical,
            minuteBody = body(BinanceCandles.oneMinute.list().lastOrNull()),
            minuteTypical = Levels.typicalRange(BinanceCandles.oneMinute.list()),
            atWall = intoWall,
        )
        val way = pick.side
        if (way.isEmpty()) {
            note = pick.note
            return
        }
        chose = pick.note

        val token = if (way == "Up") market.up.tokenId else market.down.tokenId
        val ask = try {
            ClobApi.bestAsk(token)
        } catch (e: Exception) {
            null
        }

        // Where this trade is going: the next wall the chosen side runs into,
        // or the round five hundred that way, whichever comes first.
        val aim = aimFor(way, here)

        // The balance is a request, so it is only asked for once everything
        // free has already agreed.
        val cheap = ProbePlan.blockedBecause(
            way = way,
            ask = ask,
            cashUsd = stakeNow,
            settings = settings,
            price = here,
            level = levelAhead,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = typical,
            byLine = pick.byLine,
            stake = stakeNow,
        )
        if (cheap != null) {
            note = cheap
            return
        }

        // On paper the purse is the paper purse, and asking the venue what the
        // wallet holds would be asking the wrong question of the wrong money.
        val cash = if (settings.demo) {
            bank
        } else {
            try {
                engine.usdcBalance()
            } catch (e: Exception) {
                note = e.message ?: "не прочитать баланс"
                return
            }
        }
        val blocked = ProbePlan.blockedBecause(
            way = way,
            ask = ask,
            cashUsd = cash,
            settings = settings,
            price = here,
            level = levelAhead,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = typical,
            byLine = pick.byLine,
            stake = stakeNow,
        )
        if (blocked != null) {
            note = blocked
            return
        }
        if (ask == null) return

        // Dear sides are not chased. Above the take price the rule leaves a
        // bid where it is willing to buy and lets the window come to it.
        val waits = ProbePlan.waits(ask)
        val pay = ProbePlan.entryPrice(ask)
        val size = ProbePlan.shares(stakeNow, pay, market.minimumOrderSize)
        // Crossing the spread can step over the window's own ceiling, and the
        // venue refuses such an order rather than shaving it.
        val limit = if (waits) {
            ProbePlan.REST_PRICE
        } else {
            minOf(
                ProbePlan.crossPrice(ask, market.tickSize),
                BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart)),
            )
        }

        if (settings.demo) {
            if (waits) paperRest(windowStart, token, way, line, aim)
            else paperBuy(windowStart, token, way, ask, size, line, aim)
            return
        }

        val result = try {
            engine.placeManualOrder(
                tokenId = token,
                conditionId = market.conditionId,
                side = "BUY",
                price = limit,
                size = size,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            note = e.message ?: "ошибка сети"
            return
        }

        if (!result.success) {
            note = result.error ?: "отказ CLOB"
            engine.log("error", "Проба: $note")
            return
        }

        val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
        if (fill.shares <= 1e-6) {
            if (waits) {
                // This one is meant to wait. It stays on the book, and what it
                // came to is read off the order log when the window settles.
                working = working + Round(
                    windowStart = windowStart,
                    asset = token,
                    demo = false,
                    side = way,
                    perHour = line?.perHour ?: 0.0,
                    shares = 0.0,
                    price = 0.0,
                    resting = ProbePlan.REST_PRICE,
                    target = aim,
                )
                note = "жду по ${(ProbePlan.REST_PRICE * 100).toInt()}¢"
                engine.log(
                    "info",
                    "Проба: дорого ${(ask * 100).toInt()}¢ — оставила заявку " +
                        "на $way по ${(ProbePlan.REST_PRICE * 100).toInt()}¢",
                )
                return
            }
            // Nothing was taken at a price it meant to take. A resting buy is
            // a bet the rule did not mean to place.
            result.orderId?.let { cancel(it) }
            note = "не налили по ${(ask * 100).toInt()}¢"
            return
        }

        val price = if (fill.usd > 0.0) fill.usd / fill.shares else limit
        val round = Round(
            windowStart = windowStart,
            asset = token,
            demo = false,
            side = way,
            perHour = line?.perHour ?: 0.0,
            shares = fill.shares,
            price = price,
            target = aim,
        )
        working = working + round
        note = "в позиции"
        engine.log(
            "trade",
            "Проба: взял " + String.format("%.1f", fill.shares) + " $way по " +
                "${(price * 100).toInt()}¢ — линия " +
                (if ((line?.perHour ?: 0.0) >= 0) "+" else "−") + "$" +
                String.format("%.0f", abs(line?.perHour ?: 0.0)) + "/ч",
        )
    }

    /**
     * The same buy, on paper.
     *
     * Taking an offer costs what the offer asks plus the venue's fee, and the
     * fee on a buy is real money whether or not the money is. Nothing is sent
     * anywhere; the round goes straight into the books as filled, because a
     * marketable order into a resting offer is what actually happens when this
     * rule fires for real.
     */
    private fun paperBuy(
        windowStart: Long,
        token: String,
        way: String,
        ask: Double,
        size: Double,
        line: TrendFit.Trend?,
        aim: Double,
    ) {
        val round = Round(
            windowStart = windowStart,
            asset = token,
            demo = true,
            side = way,
            perHour = line?.perHour ?: 0.0,
            shares = size,
            // What a share costs to take, fee included, so the exit is priced
            // off what it actually cost rather than off the quote.
            price = ProbePlan.takenPrice(ask),
            target = aim,
        )
        working = working + round
        note = "в позиции (демо)"
        engine.log(
            "trade",
            "Проба (демо): взяла " + String.format("%.1f", size) + " $way по " +
                "${(ask * 100).toInt()}¢ — счёт $" + String.format("%.2f", bank),
        )
    }

    /**
     * The next price the market stops at, that way.
     *
     * Trading is level to level: what a five-minute bet can actually collect
     * is the distance to the next place price pauses, and there are two kinds
     * — the ones the market has turned at before, and the round five hundreds
     * where the book always sits. Whichever is nearer in the trade's own
     * direction is the one it is aiming at.
     */
    private fun aimFor(way: String, here: Double): Double {
        if (here <= 0.0 || way.isEmpty()) return 0.0

        val pivot = Levels.ahead(walls(here), here, way)

        val step = ProbePlan.ROUND_STEP
        val round = if (way == "Up") {
            Math.floor(here / step) * step + step
        } else {
            Math.ceil(here / step) * step - step
        }

        val candidates = listOfNotNull(pivot, round.takeIf { it > 0.0 })
            .filter { if (way == "Up") it > here else it < here }
        return candidates.minByOrNull { abs(it - here) } ?: 0.0
    }

    /**
     * Puts the same money into the same side a second time, cheaply.
     *
     * The entry was taken on a read that has not been withdrawn, and the same
     * read at a third of a dollar is the same bet at better odds. It only
     * happens while there is still time for the move — past two minutes a
     * cheap side is not cheap, it is late — and it happens once, because a
     * rule that keeps doubling into a falling side loses the account on the
     * day the read is simply wrong.
     */
    private fun addUp(nowSec: Long) {
        val held = working.filter { !it.added && it.shares > it.sold + 1e-9 }
        if (held.isEmpty()) return

        for (open in held) {
            val elapsed = nowSec - open.windowStart
            val ask = try {
                ClobApi.bestAsk(open.asset)
            } catch (e: Exception) {
                null
            }
            if (!ProbePlan.addsUp(elapsed, ask, open.added)) continue
            if (ask == null) continue

            // The same amount as went in the first time, which is what the
            // position cost rather than whatever the stake happens to be now.
            val usd = open.shares * open.price
            if (settings.demo && bank < usd) continue

            val market = engine.marketForWindow(open.windowStart) ?: continue
            val size = ProbePlan.shares(usd, ask, market.minimumOrderSize)
            val paid: Double
            val more: Double

            if (settings.demo) {
                paid = ProbePlan.takenPrice(ask)
                more = size
            } else {
                val limit = minOf(
                    ProbePlan.crossPrice(ask, market.tickSize),
                    BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart)),
                )
                val result = try {
                    engine.placeManualOrder(
                        tokenId = open.asset,
                        conditionId = market.conditionId,
                        side = "BUY",
                        price = limit,
                        size = size,
                        orderType = "GTC",
                        auto = true,
                    )
                } catch (e: Exception) {
                    note = e.message ?: "ошибка сети"
                    continue
                }
                if (!result.success) {
                    note = result.error ?: "отказ CLOB"
                    continue
                }
                val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
                if (fill.shares <= 1e-6) {
                    result.orderId?.let { cancel(it) }
                    continue
                }
                paid = if (fill.usd > 0.0) fill.usd / fill.shares else limit
                // What the venue actually gave, which is rarely all of it.
                more = fill.shares
            }

            val shares = open.shares + more
            val price = (open.shares * open.price + more * paid) / shares
            working = working.map {
                if (it.windowStart == open.windowStart) {
                    it.copy(shares = shares, price = price, added = true)
                } else {
                    it
                }
            }
            engine.log(
                "trade",
                "Проба" + (if (settings.demo) " (демо)" else "") + ": докупила " +
                    String.format("%.1f", more) + " ${open.side} по " +
                    "${(paid * 100).toInt()}¢ — средняя ${(price * 100).toInt()}¢",
            )
            onStateChanged()
        }
    }

    /**
     * Reads what the desk's own sell rule has managed to sell.
     *
     * A real position is exited by that rule, and the order log is where the
     * sale shows up — reading it every tick is what lets a window be filed the
     * moment the ladder's price is touched rather than five minutes later.
     * Paper positions keep their own books and are not looked at here.
     */
    private fun readSales() {
        val held = working.filter { !it.demo && it.shares > it.sold + 1e-9 }
        if (held.isEmpty()) return

        var next = working
        var changed = false
        for (open in held) {
            val sells = OrderLog.forWindow(open.windowStart)
                .filter { it.asset == open.asset && it.action == "SELL" }
            val sold = sells.sumOf { it.matched }.coerceAtMost(open.shares)
            if (sold <= open.sold + 1e-9) continue

            next = next.map {
                if (it.windowStart == open.windowStart) {
                    it.copy(
                        sold = sold,
                        proceeds = sells.sumOf { row -> row.matched * row.realPrice },
                    )
                } else {
                    it
                }
            }
            changed = true
        }
        if (changed) {
            working = next
            onStateChanged()
        }
    }

    /**
     * The bid that waits, on paper.
     *
     * Nothing is bought yet: the round is a standing order at the rest price,
     * and it becomes a position the moment the offers come down to it. If they
     * never do, the window closes having cost nothing, which is the point of
     * bidding rather than chasing.
     */
    private fun paperRest(
        windowStart: Long,
        token: String,
        way: String,
        line: TrendFit.Trend?,
        aim: Double,
    ) {
        working = working + Round(
            windowStart = windowStart,
            asset = token,
            demo = true,
            side = way,
            perHour = line?.perHour ?: 0.0,
            shares = 0.0,
            price = 0.0,
            resting = ProbePlan.REST_PRICE,
            target = aim,
        )
        note = "жду по ${(ProbePlan.REST_PRICE * 100).toInt()}¢ (демо)"
        engine.log(
            "info",
            "Проба (демо): дорого — оставила заявку на $way по " +
                "${(ProbePlan.REST_PRICE * 100).toInt()}¢",
        )
    }

    /**
     * Whether a waiting paper bid has been reached.
     *
     * A resting buy is filled when a seller comes down to it, which is exactly
     * when the best offer reaches its price — and being the maker, it pays no
     * taker fee, so the price it gets is the price it asked.
     */
    private fun fillResting(nowSec: Long) {
        val waiting = working.filter { it.demo && it.resting > 0.0 && it.shares <= 0.0 }
        if (waiting.isEmpty()) return

        var next = working
        var changed = false
        for (open in waiting) {
            if (nowSec >= open.windowStart + WINDOW_SEC) continue
            val ask = try {
                ClobApi.bestAsk(open.asset)
            } catch (e: Exception) {
                null
            } ?: continue
            if (ask > open.resting + 1e-9) continue

            val size = ProbePlan.shares(stakeNow, open.resting, 5.0)
            next = next.map {
                if (it.windowStart == open.windowStart) {
                    it.copy(shares = size, price = open.resting)
                } else {
                    it
                }
            }
            changed = true
            engine.log(
                "trade",
                "Проба (демо): налили " + String.format("%.1f", size) + " ${open.side}" +
                    " по ${(open.resting * 100).toInt()}¢",
            )
        }
        if (changed) {
            working = next
            onStateChanged()
        }
    }

    /**
     * Walks the paper position up the same ladder the desk's own rule uses.
     *
     * The real probe places no sells: it buys, and the standing sell rule
     * arranges the exit. On paper there is nothing for that rule to see, so the
     * ladder is followed here — the rung for the time and the high-water mark,
     * and the moment the book is bidding it, the shares are gone at that price.
     * Which is what the resting offer would have done.
     */
    private fun workPaper(nowSec: Long) {
        val riding = working.filter { it.demo && it.shares > it.sold + 1e-9 }
        if (riding.isEmpty()) return

        val rule = exit()
        var next = working
        var changed = false
        for (open in riding) {
            val elapsed = nowSec - open.windowStart
            if (elapsed < 0) continue
            val secondsLeft = open.windowStart + WINDOW_SEC - nowSec

            val bid = try {
                ClobApi.bestBid(open.asset)
            } catch (e: Exception) {
                null
            } ?: continue

            // The offer is priced from what was known before this tick, and
            // only then does the mark walk forward. Doing it the other way
            // round — pricing the rung off the very bid being tested — meant
            // that a price which jumped straight past a rung dragged the rung
            // up with it, and the paper position could never fill. In the
            // market the offer was already resting there and simply got hit.
            val want = ProbePlan.exitPrice(
                cost = open.price,
                elapsedSec = elapsed,
                secondsLeft = secondsLeft,
                highWater = open.highWater,
                rung = open.rung,
                bestBid = bid,
                exit = rule,
            )

            // Level to level: the trade was taken for the distance to the
            // next price the market stops at, and once price is there the move
            // is finished whatever the outcome's own quote is doing. Take what
            // the book pays rather than wait for a rung that may never come.
            val settling = here()
            val arrived = open.target > 0.0 && settling > 0.0 && (
                if (open.side == "Up") settling >= open.target else settling <= open.target
            )

            val high = maxOf(open.highWater, bid)
            val step = ProbePlan.exitStep(elapsed, high, open.rung, rule)
            var moved = open.copy(highWater = high, rung = maxOf(open.rung, step))
            if (arrived && bid > 0.0 && bid < want) {
                val left = open.shares - open.sold
                moved = moved.copy(
                    sold = open.shares,
                    proceeds = open.proceeds + left * SellPercent.netSell(bid),
                )
                engine.log(
                    "trade",
                    "Проба (демо): дошли до " + Math.round(open.target) +
                        " — продала " + String.format("%.1f", left) +
                        " ${open.side} по ${(bid * 100).toInt()}¢",
                )
            } else if (bid >= want - 1e-9) {
                // The offer would have been resting there, so it is the price
                // asked that gets paid, not the bid that reached up to it.
                val left = open.shares - open.sold
                moved = moved.copy(
                    sold = open.shares,
                    proceeds = open.proceeds + left * SellPercent.netSell(want),
                )
                engine.log(
                    "trade",
                    "Проба (демо): продала " + String.format("%.1f", left) +
                        " ${open.side} по ${(want * 100).toInt()}¢",
                )
            }
            if (moved != open) {
                next = next.map { if (it.windowStart == open.windowStart) moved else it }
                changed = true
            }
        }
        if (changed) {
            working = next
            onStateChanged()
        }
    }

    /**
     * The level the line is heading into, off the same five-minute candles the
     * chart draws.
     *
     * Read every tick rather than only at the entry, so the card can say what
     * the rule will do before it does it — and so a window that is standing
     * aside says which price it is standing aside from.
     */
    /**
     * Every price the market stops at, off both charts.
     *
     * The five-minute panel draws walls the minute panel cannot see — an hour
     * of minutes only reaches back an hour — and those are the stronger ones:
     * a price that turned the market twice over four hours stops it harder
     * than one that turned it twice in the last twenty minutes. Reading only
     * the minute chart is how the rule bought into resistance that was drawn
     * on the screen above it.
     */
    private fun walls(here: Double): List<Levels.Level> {
        if (here <= 0.0) return emptyList()
        return (
            Levels.find(BinanceCandles.oneMinute.list(), here) +
                Levels.find(BinanceCandles.fiveMinute.list(), here)
            ).filter { it.touches >= 2 }
    }

    private fun readLevel() {
        // The round number first, and whatever the trend is doing: it is about
        // where the price is, not about which way it is going. Off the
        // settlement series rather than the chart's — they sit a few dollars
        // apart, and it is the settlement price that opens the window.
        val settling = here()
        if (settling > 0.0) {
            val nearest = Math.round(settling / ProbePlan.ROUND_STEP) * ProbePlan.ROUND_STEP
            roundNear = nearest
            roomToRound = abs(settling - nearest)
        } else {
            roundNear = null
            roomToRound = null
        }

        // Both candles that close with the window, so the card shows what the
        // rule is about to see.
        candleBody = body(BinanceCandles.fiveMinute.list().lastOrNull())
        minuteBody = body(BinanceCandles.oneMinute.list().lastOrNull())

        val candles = BinanceCandles.oneMinute.list()
        val here = candles.lastOrNull()?.close ?: 0.0
        val way = TrendFit.lean(trend)
        if (here <= 0.0 || way.isEmpty()) {
            levelAhead = null
            roomToLevel = null
            return
        }
        // Only prices the market has actually turned at more than once. The
        // panel draws the nearest cluster either side whatever its history,
        // which is right for a chart and far too eager for a gate: a single
        // pivot on the minute chart is one wiggle, and standing aside for
        // every wiggle is standing aside for good.
        val level = Levels.ahead(walls(here), here, way)
        levelAhead = level
        roomToLevel = level?.let { abs(it - here) }

    }

    /**
     * Files a round the moment there is nothing left to learn about it.
     *
     * A position that has sold out is finished: the money is counted and no
     * settlement can change it, so it belongs in the record now rather than
     * five minutes from now. Whether the line called the window right is not
     * known yet — that waits for the close — and [catchUp] fills it in.
     */
    private fun fileSold() {
        val done = working.filter { it.shares > 0.0 && it.sold >= it.shares - 1e-9 }
        if (done.isEmpty()) return

        working = working.filterNot { row -> done.any { it.windowStart == row.windowStart } }
        rounds = rounds + done.map { it.copy(note = it.note ?: "продано лесенкой") }
        store.saveRounds(rounds)
        done.forEach { run(it.pnl) }
        done.forEach {
            engine.log(
                if (it.pnl >= 0) "trade" else "warn",
                "Проба: окно " + hhmm(it.windowStart) + " закрыто — " +
                    (if (it.pnl >= 0) "+" else "−") + "$" +
                    String.format("%.2f", abs(it.pnl)),
            )
        }
        onStateChanged()
    }

    /**
     * Carries the winning run forward, or ends it.
     *
     * A window that made money adds a quarter of it to what the next one
     * stakes, and the next win adds a quarter of its own on top. A losing
     * window ends the run and the stake falls back to the base — so the run
     * only ever risks money the rule has already made.
     */
    private fun run(pnl: Double) {
        val next = ProbePlan.nextStreak(streak, pnl)
        if (next == streak) return
        streak = next
        store.saveStreak(next)
        engine.log(
            "info",
            if (next > 0.0) {
                "Проба: серия — следующая ставка $" + String.format("%.2f", stakeNow)
            } else {
                "Проба: серия прервана — ставка снова $" +
                    String.format("%.2f", stakeNow)
            },
        )
    }

    /**
     * Fills in the result of rounds that were filed before their window shut.
     *
     * The money was known the moment the position sold; whether the line was
     * right was not, and a report that never learned it would say the rule
     * guessed wrong every time it exited early.
     */
    private fun catchUp(nowSec: Long) {
        val pending = rounds.takeLast(24).filter {
            it.winner.isEmpty() && nowSec >= it.windowStart + WINDOW_SEC + SETTLE_SEC
        }
        if (pending.isEmpty()) return

        var changed = false
        var next = rounds
        for (row in pending) {
            val winner = EventStats.winnerFor(row.windowStart, nowSec)
            if (winner.isEmpty()) continue
            next = next.map { if (it.windowStart == row.windowStart) it.copy(winner = winner) else it }
            changed = true
        }
        if (changed) {
            rounds = next
            store.saveRounds(rounds)
            onStateChanged()
        }
    }

    /** Scores every ridden round whose window has closed and settled. */
    private fun settleDue(nowSec: Long) {
        // Anything already sold out is finished business and is filed at once.
        fileSold()
        catchUp(nowSec)

        val ripe = working.filter { nowSec >= it.windowStart + WINDOW_SEC + SETTLE_SEC }
        if (ripe.isEmpty()) return

        var stillOpen = working
        var closed = rounds
        for (open in ripe) {
            val scored = score(open, nowSec)
            val late = nowSec >= open.windowStart + WINDOW_SEC + GIVE_UP_SEC
            if (scored.winner.isEmpty() && !late) continue

            stillOpen = stillOpen.filterNot { it.windowStart == open.windowStart }
            closed = closed + scored
            run(scored.pnl)
            engine.log(
                if (scored.pnl >= 0) "trade" else "warn",
                "Проба: окно " + hhmm(open.windowStart) + " — " +
                    (if (scored.pnl >= 0) "+" else "−") + "$" +
                    String.format("%.2f", abs(scored.pnl)) +
                    ", " + (if (scored.right) "тренд был прав" else "тренд ошибся"),
            )
        }

        if (closed !== rounds || stillOpen !== working) {
            working = stillOpen
            rounds = closed
            store.saveRounds(closed)
            onStateChanged()
        }
    }

    /**
     * What the round came to.
     *
     * The ladder's sales are read off the order log rather than tracked here —
     * this rule places no sells, so the log is the only place they exist — and
     * whatever the ladder never sold is paid by the settlement, a dollar a
     * share on the winning side and nothing on the other.
     */
    private fun score(open: Round, nowSec: Long): Round {
        // A real bid that was left waiting may have been filled while nobody
        // was looking; the order log is the only place that fill exists.
        val round = if (!open.demo && open.resting > 0.0 && open.shares <= 0.0) {
            val buys = OrderLog.forWindow(open.windowStart)
                .filter { it.asset == open.asset && it.action == "BUY" }
            val got = buys.sumOf { it.matched }
            val paid = buys.sumOf { it.matched * it.realPrice }
            if (got > 1e-6) {
                open.copy(shares = got, price = paid / got)
            } else {
                open
            }
        } else {
            open
        }

        // A bid nobody came down to cost nothing and bought nothing. It is a
        // window with a reason, not a trade.
        if (round.shares <= 1e-6) {
            return round.copy(
                winner = EventStats.winnerFor(round.windowStart, nowSec),
                note = "лимитка ${(round.resting * 100).toInt()}¢ не налилась",
            )
        }

        return scoreFilled(round, nowSec)
    }

    private fun scoreFilled(open: Round, nowSec: Long): Round {
        // A paper round has been keeping its own books all along; a real one's
        // sales exist only in the order log, because this rule never placed
        // them.
        val sold: Double
        val proceeds: Double
        if (open.demo) {
            sold = open.sold
            proceeds = open.proceeds
        } else {
            val sells = OrderLog.forWindow(open.windowStart)
                .filter { it.asset == open.asset && it.action == "SELL" }
            sold = sells.sumOf { it.matched }.coerceAtMost(open.shares)
            proceeds = sells.sumOf { it.matched * it.realPrice }
        }

        val winner = EventStats.winnerFor(open.windowStart, nowSec)
        val left = (open.shares - sold).coerceAtLeast(0.0)
        val settled = if (winner.isNotEmpty() && winner == open.side) left else 0.0

        return open.copy(
            sold = sold,
            proceeds = proceeds,
            settled = settled,
            winner = winner,
            note = when {
                winner.isEmpty() -> "итог неизвестен"
                left <= 1e-6 -> "продано лесенкой"
                settled > 0.0 -> "дошло до расчёта"
                else -> "сгорело"
            },
        )
    }

    /** The window's own clock time, which is how the report names it. */
    private fun hhmm(windowStart: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(windowStart * 1000))
    }

    private fun cancel(orderId: String) {
        val session = engine.session() ?: return
        try {
            ClobApi.cancelOrder(session.creds, session.account.signerAddress, orderId)
        } catch (e: Exception) {
            // It may have filled in between, which the next pass will see.
        }
    }
}
