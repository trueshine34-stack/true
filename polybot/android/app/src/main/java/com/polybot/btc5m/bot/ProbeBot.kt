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
        /** How many times the same money has gone into the side again. */
        val adds: Int = 0,
        /**
         * Which buy of this window this is: the entry is nought, and a side
         * bought back after the ladder sold it is the next one along.
         *
         * A window can hold more than one position, so [windowStart] alone no
         * longer names a row. Every lookup pairs the two.
         */
        val leg: Int = 0,
        /** What the ladder let this side go at, which is what it is bought back under. */
        val soldAt: Double = 0.0,
        /** Whether the buy-back after that sale has already been taken. */
        val back: Boolean = false,
        /** Best bid seen while holding, which is what walks the ladder up. */
        val highWater: Double = 0.0,
        /**
         * And the worst bid seen, which is what arms the rescue.
         *
         * A side the book has written off down at a dime is not a position
         * any more; if the window hands it back at a third it is let go of
         * there rather than ridden to the close.
         */
        val lowWater: Double = 0.0,

        /** The rung reached, so a paper exit cannot slide back down. */
        val rung: Int = 0,
        /**
         * Everything the rule was looking at when it chose this side.
         *
         * One line per fact, in the order a person would check them. A
         * history that only says "Down, lost" cannot be argued with; one that
         * says which trend, which candle, which walls and how much room can.
         */
        val why: String = "",
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

    /**
     * Everything the last entry was decided on, one fact a line.
     *
     * Built in [enter] and copied onto whichever buy the branch below takes,
     * so a round in the history can say what it was looking at rather than
     * only what it did.
     */
    private var reading: String = ""

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

    /** Set while the window still running is already showing a loss. */
    @Volatile
    var losing: Boolean = false
        private set

    /** The run's addition as the last tick found it. */
    @Volatile
    private var riding: Double = streak

    /** What the next window will stake, the open window's state included. */
    val stakeLive: Double
        get() = ProbePlan.stakeFor(settings.stakeUsd, won, settings.bankUsd, riding)

    /**
     * Reads the run against the window that is still running.
     *
     * Staking the run on top of a window that is about to end it means the
     * largest bet of the sequence is placed exactly when the sequence is over.
     * So the position is marked to what the book would pay for it, and a run
     * sitting on a loss does not ride into the next window.
     */
    private fun readRun() {
        val open = working.firstOrNull { it.shares > it.sold + 1e-9 }
        if (open == null) {
            losing = false
            riding = streak
            return
        }
        val bid = try {
            ClobApi.bestBid(open.asset)
        } catch (e: Exception) {
            null
        }
        if (bid == null) {
            riding = streak
            return
        }

        val left = open.shares - open.sold
        val worth = open.proceeds + left * SellPercent.netSell(bid)
        losing = worth < open.cost - 1e-9
        riding = ProbePlan.riding(streak, worth, open.cost)
    }

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
            // And a bid the market never came back to is taken back, so the
            // money is free for the next window.
            dropStale(nowSec)
            readSales()
            // A side that went to nothing and came back is let go of the
            // moment it can be, before anything else looks at it.
            rescue()
            // A winner that has stopped at a level is taken there, before any
            // rung is consulted: the rung is above the level and the level is
            // where the move ends.
            stall(nowSec)
            // A side that has fallen far enough is bought again, once, while
            // the window still has time to turn.
            addUp(nowSec)
            // And a side the ladder has already let go of is taken back when
            // the market hands it over cheaper than it sold.
            buyBack(nowSec)
            workPaper(nowSec)
            // A sale booked this tick is filed this tick, so the run's next
            // stake is right before the next window opens.
            fileSold()
            // And what is still open is marked to the book, because the window
            // about to close decides whether the run survives it.
            readRun()
        }

        // Paper money needs no wallet: it reads the same public book, and
        // nothing it does is ever signed. Only real orders need a session, and
        // the demo is meant to keep running whether one is connected or not.
        val session = engine.session()
        if (session == null && !settings.demo) {
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
            why = reading,
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

        // The prices that stop things, either side of here, each with the
        // weight it carries. The bounce is read off these before any line is
        // consulted, and how much weight a level has decides whether it may be
        // traded against the candle that is closing.
        val shelf = walls(here).map { ProbePlan.Wall(it.price, it.touches, round = false) } +
            listOfNotNull(
                roundAbove(here)?.let { ProbePlan.Wall(it, 0, round = true) },
                roundBelow(here)?.let { ProbePlan.Wall(it, 0, round = true) },
            )
        val above = shelf.filter { it.price > here }.minByOrNull { it.price - here }
        val below = shelf.filter { it.price < here }.minByOrNull { here - it.price }

        val pick = ProbePlan.choose(
            way = TrendFit.lean(line),
            // The five-minute line's own call, not merely its slope: a fit too
            // weak to name a direction has no business vetoing one.
            wide = wide?.way.orEmpty(),
            candleBody = body,
            typical = typical,
            candleHigh = closing?.high ?: 0.0,
            candleLow = closing?.low ?: 0.0,
            candleClose = closing?.close ?: 0.0,
            minuteBody = body(BinanceCandles.oneMinute.list().lastOrNull()),
            minuteTypical = Levels.typicalRange(BinanceCandles.oneMinute.list()),
            above = above,
            below = below,
        )
        val way = pick.side
        if (way.isEmpty()) {
            note = pick.note
            return
        }
        chose = pick.note

        // And the wall this side is heading into, which for a bounce is the
        // one across the room rather than the one just left behind.
        val ahead = (if (way == "Up") above else below)?.price

        val token = if (way == "Up") market.up.tokenId else market.down.tokenId
        val ask = try {
            ClobApi.bestAsk(token)
        } catch (e: Exception) {
            null
        }

        // Where this trade is going: the next wall the chosen side runs into,
        // or the round five hundred that way, whichever comes first.
        val aim = aimFor(way, here)

        // What this window will actually stake, which is the run's addition
        // only while the run is still alive.
        val staking = stakeLive

        // Everything the decision rests on, written down before any gate has
        // had its say — so a window that is stood out of carries the same
        // reading as one that is traded, which is the half worth reading.
        reading = readingOf(
            pick = pick,
            line = line,
            here = here,
            aim = aim,
            above = above,
            below = below,
            body = body,
            typical = typical,
            ask = ask,
            staking = staking,
        )

        // The balance is a request, so it is only asked for once everything
        // free has already agreed.
        val cheap = ProbePlan.blockedBecause(
            way = way,
            ask = ask,
            cashUsd = staking,
            settings = settings,
            price = here,
            level = ahead,
            candleHigh = closing?.high ?: 0.0,
            candleLow = closing?.low ?: 0.0,
            candleClose = closing?.close ?: 0.0,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = typical,
            byLine = pick.byLine,
            stake = staking,
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
            level = ahead,
            candleHigh = closing?.high ?: 0.0,
            candleLow = closing?.low ?: 0.0,
            candleClose = closing?.close ?: 0.0,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = typical,
            byLine = pick.byLine,
            stake = staking,
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
        val size = ProbePlan.shares(staking, pay, market.minimumOrderSize)
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
                    why = reading,
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
            why = reading,
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
    /**
     * Everything the entry was decided on, one fact a line.
     *
     * Written in the order a person checks them — what chose the side, then
     * the two trends, then the two candles, then the walls either side and
     * the room to the one in front, then the round number, then what it cost.
     * The point is that a losing round can be argued with afterwards: a
     * history saying only "Down, lost" teaches nothing.
     */
    private fun readingOf(
        pick: ProbePlan.Choice,
        line: TrendFit.Trend?,
        here: Double,
        aim: Double,
        above: ProbePlan.Wall?,
        below: ProbePlan.Wall?,
        body: Double,
        typical: Double,
        ask: Double?,
        staking: Double,
    ): String {
        val cents = { p: Double -> "${(p * 100).toInt()}¢" }
        val dollars = { v: Double -> (if (v >= 0) "+" else "−") + Math.round(abs(v)) + "$" }
        val wall = { w: ProbePlan.Wall? ->
            w?.let {
                Math.round(it.price).toString() +
                    (if (it.round) " (круглый)" else " (×" + it.touches + ")")
            } ?: "нет"
        }
        val minute = body(BinanceCandles.oneMinute.list().lastOrNull())
        val room = if (aim > 0.0 && here > 0.0) abs(aim - here) else 0.0

        return listOf(
            "решение: " + (pick.note ?: "по тренду"),
            "тренд 1м: " + (line?.way.orEmpty().ifEmpty { "вбок" }) +
                " " + Math.round(line?.perHour ?: 0.0) + "$/ч" +
                " R² " + String.format("%.2f", line?.fit ?: 0.0),
            "тренд 5м: " + (wide?.way.orEmpty().ifEmpty { "вбок" }),
            "свеча 5м: " + dollars(body) + " · минутка: " + dollars(minute),
            "обычный ход 5м: " + Math.round(typical) + "$",
            "цена BTC: " + Math.round(here),
            "стена сверху: " + wall(above),
            "стена снизу: " + wall(below),
            "цель: " + (if (aim > 0.0) Math.round(aim).toString() +
                " (" + Math.round(room) + "$)" else "нет"),
            "круглый: " + (roundNear?.let {
                Math.round(it).toString() + " в " + Math.round(roomToRound ?: 0.0) + "$"
            } ?: "нет"),
            "аск: " + (
                ask?.let {
                    cents(it) + (
                        if (ProbePlan.waits(it)) {
                            " — дорого, жду по " + cents(ProbePlan.REST_PRICE)
                        } else {
                            " — беру по " + cents(ProbePlan.entryPrice(it))
                        }
                        )
                } ?: "нет цены"
                ),
            "ставка: $" + String.format("%.2f", staking) +
                (if (streak > 0.0) " (серия +$" + String.format("%.2f", streak) + ")" else ""),
        ).joinToString("\n")
    }

    /**
     * A sell offer the rule is showing right now.
     *
     * In demo nothing is sent anywhere, so the only place these exist is here
     * — which is exactly why they are worth publishing: without them the card
     * shows a position and no sign of what it is asking for it.
     */
    data class Offer(
        val windowStart: Long,
        val side: String,
        val price: Double,
        val size: Double,
        val rung: Int,
        val demo: Boolean,
        val leg: Int,
    )

    /** What is on offer for everything still held, paper or real. */
    val offers: List<Offer>
        get() {
            val nowSec = Clock.nowSec()
            val rule = exit()
            return working
                .filter { it.shares > it.sold + 1e-9 }
                .map { open ->
                    val elapsed = nowSec - open.windowStart
                    Offer(
                        windowStart = open.windowStart,
                        side = open.side,
                        price = ProbePlan.exitPrice(
                            cost = open.price,
                            elapsedSec = elapsed,
                            secondsLeft = open.windowStart + WINDOW_SEC - nowSec,
                            highWater = open.highWater,
                            rung = open.rung,
                            bestBid = null,
                            exit = rule,
                        ),
                        size = open.shares - open.sold,
                        rung = open.rung,
                        demo = open.demo,
                        leg = open.leg,
                    )
                }
        }

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
            why = reading,
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
     * Puts the same money into the same side again, cheaply.
     *
     * The entry was taken on a read that has not been withdrawn, and the same
     * read at forty cents is the same bet at better odds. It happens at two
     * prices — forty-two and then thirty-three — so a window holds three buys
     * and never a fourth, and only while there is still time for the move:
     * past two minutes a cheap side is not cheap, it is late.
     *
     * And not into a shock. A minute several times the size of the minutes
     * around it is the news that moved the price rather than noise on top of
     * it, and averaging into that pays twice for one wrong read.
     */
    private fun addUp(nowSec: Long) {
        val held = working.filter { it.shares > it.sold + 1e-9 }
        if (held.isEmpty()) return

        for (open in held) {
            val elapsed = nowSec - open.windowStart
            val ask = try {
                ClobApi.bestAsk(open.asset)
            } catch (e: Exception) {
                null
            }
            if (!ProbePlan.addsUp(elapsed, ask, open.adds)) continue
            if (ask == null) continue
            // A minute several times the usual size is the news that moved
            // the price, not a dip in it, and it is still moving.
            if (shockNow(open.side)) {
                note = "аномальная свеча — без докупа"
                continue
            }

            // The same amount as went in the first time — the position has
            // grown with every add, so what it cost is divided back down.
            val usd = open.shares * open.price / (open.adds + 1)
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
                if (it.windowStart == open.windowStart && it.leg == open.leg) {
                    it.copy(shares = shares, price = price, adds = it.adds + 1)
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
     * Whether the minute now running is too big a move to buy into.
     *
     * Measured against the minutes before it rather than a fixed number of
     * dollars, because what counts as a big candle at eighty thousand is not
     * what counted at thirty.
     */
    private fun shockNow(side: String): Boolean {
        val minutes = BinanceCandles.oneMinute.list()
        val now = minutes.lastOrNull() ?: return false
        val typical = Levels.typicalRange(minutes.dropLast(1), 12)
        return ProbePlan.shocked(ProbePlan.againstBy(side, now.open, now.close), typical)
    }

    /**
     * Buys back a side the ladder has just sold, when the price comes back.
     *
     * The rung filled because the market came to it, and a fifth off that
     * price afterwards is the same side handed back cheaper than it was let
     * go of — with the window still running and the read unchanged. It stops
     * at forty-four cents, under which the side is no longer the favourite
     * and this is a different trade rather than the same one repeated; it
     * happens once per sale; and, like every top-up, never into a shock.
     */
    private fun buyBack(nowSec: Long) {
        val elapsed = SellLadder.elapsedInWindow(nowSec)
        val windowStart = nowSec - elapsed
        // Only when the window is empty: a leg still open is the position,
        // and adding to it is [addUp]'s business, not this one's.
        if (working.any { it.windowStart == windowStart }) return

        val sold = rounds.lastOrNull {
            it.windowStart == windowStart && it.demo == settings.demo &&
                !it.back && it.soldAt > 0.0 && it.shares > 0.0
        } ?: return

        val ask = try {
            ClobApi.bestAsk(sold.asset)
        } catch (e: Exception) {
            null
        }
        if (!ProbePlan.buysBack(elapsed, ask, sold.soldAt, sold.back)) return
        if (ask == null) return
        if (shockNow(sold.side)) {
            note = "аномальная свеча — без откупа"
            return
        }

        // The same money as the entry, once more.
        val usd = sold.shares * sold.price / (sold.adds + 1)
        if (settings.demo && bank < usd) return

        val market = engine.marketForWindow(windowStart) ?: return
        val size = ProbePlan.shares(usd, ask, market.minimumOrderSize)
        val paid: Double
        val got: Double

        if (settings.demo) {
            paid = ProbePlan.takenPrice(ask)
            got = size
        } else {
            val limit = minOf(
                ProbePlan.crossPrice(ask, market.tickSize),
                BuyCap.ceiling(BuyCap.elapsedFor(windowStart)),
            )
            val result = try {
                engine.placeManualOrder(
                    tokenId = sold.asset,
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
                return
            }
            val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
            if (fill.shares <= 1e-6) {
                result.orderId?.let { cancel(it) }
                return
            }
            paid = if (fill.usd > 0.0) fill.usd / fill.shares else limit
            got = fill.shares
        }

        // The sale is marked so the same rung cannot be bought back twice.
        rounds = rounds.map {
            if (it.windowStart == sold.windowStart && it.leg == sold.leg) {
                it.copy(back = true)
            } else {
                it
            }
        }
        store.saveRounds(rounds)

        // A fresh leg: its own ladder, its own result, its own line in the
        // history beside the sale it followed.
        working = working + sold.copy(
            leg = sold.leg + 1,
            shares = got,
            price = paid,
            sold = 0.0,
            proceeds = 0.0,
            settled = 0.0,
            winner = "",
            note = null,
            resting = 0.0,
            adds = 0,
            soldAt = 0.0,
            back = false,
            highWater = 0.0,
            lowWater = 0.0,
            rung = 0,
        )
        engine.log(
            "trade",
            "Проба" + (if (settings.demo) " (демо)" else "") + ": откупила " +
                String.format("%.1f", got) + " ${sold.side} по " +
                "${(paid * 100).toInt()}¢ — продавала по " +
                "${(sold.soldAt * 100).toInt()}¢",
        )
        onStateChanged()
    }

    /**
     * Pulls a bid that the market never came back to.
     *
     * It was left at the rest price for a window five minutes long; a side
     * that has not reached it in the first minute is not going to fill as the
     * same trade, and what would fill later is a different bet at the same
     * number. Taking it back frees the money for the next window and files the
     * one it was for with that as the reason.
     */
    private fun dropStale(nowSec: Long) {
        val waiting = working.filter { it.resting > 0.0 && it.shares <= 0.0 }
        if (waiting.isEmpty()) return

        for (open in waiting) {
            if (!ProbePlan.restingDone(nowSec - open.windowStart)) continue
            if (!open.demo) cancelBuys(open.asset)

            working = working.filterNot { it.windowStart == open.windowStart && it.leg == open.leg }
            rounds = rounds + open.copy(
                winner = EventStats.winnerFor(open.windowStart, nowSec),
                note = "лимитка ${(open.resting * 100).toInt()}¢ снята",
            )
            store.saveRounds(rounds)
            engine.log(
                "info",
                "Проба: сняла заявку на ${open.side} по " +
                    "${(open.resting * 100).toInt()}¢ — за минуту не налили",
            )
            onStateChanged()
        }
    }

    /** Takes back whatever this rule has resting on a side. */
    private fun cancelBuys(asset: String) {
        val session = engine.session() ?: return
        try {
            ClobApi.openOrders(session.creds, session.account.signerAddress)
                .filter { it.assetId == asset && it.side == "BUY" }
                .forEach {
                    try {
                        ClobApi.cancelOrder(session.creds, session.account.signerAddress, it.id)
                    } catch (e: Exception) {
                        // It may have filled in between, which the log will show.
                    }
                }
        } catch (e: Exception) {
            // Nothing to be done; the next pass tries again.
        }
    }

    /**
     * Takes a winning position that has stopped dead at a level.
     *
     * The move that was bought has arrived — price is at one of the prices
     * that stop it, the last minute has gone nowhere, and the book is paying
     * enough that the position is plainly ahead. Waiting past that is not
     * holding a winner, it is holding a coin toss on whether the level lets it
     * through, and a level that has stopped the market before usually does
     * not. The window before this was written stood on its level for minutes
     * with the profit there for the taking, and gave it all back.
     */
    private fun stall(nowSec: Long) {
        val riding = working.filter { it.shares > it.sold + 1e-9 }
        if (riding.isEmpty()) return

        val here = here()
        if (here <= 0.0) return
        val typical = Levels.typicalRange(BinanceCandles.fiveMinute.list())
        if (typical <= 0.0) return

        // Where price was a minute ago, off the closed minute candle behind
        // the one in progress.
        val minutes = BinanceCandles.oneMinute.list()
        if (minutes.size < 2) return
        val was = minutes[minutes.size - 2].close
        if (was <= 0.0) return

        // At a level: any price the market has stopped at before, within a
        // wick's reach of here.
        val near = typical * ProbePlan.TOUCH
        val atLevel = walls(here).any { abs(it.price - here) <= near } ||
            ProbePlan.nearRound(here, near) != null

        for (open in riding) {
            val moved = here - was
            val progress = if (open.side == "Up") moved else -moved
            val bid = try {
                ClobApi.bestBid(open.asset)
            } catch (e: Exception) {
                null
            } ?: continue

            if (!ProbePlan.stalling(progress, nowSec - open.windowStart, bid, atLevel)) {
                continue
            }

            val left = open.shares - open.sold
            val sold = if (open.demo) true else sellOut(open, bid)
            if (!sold) continue

            working = working.map {
                if (it.windowStart == open.windowStart && it.leg == open.leg) {
                    it.copy(
                        sold = open.shares,
                        proceeds = open.proceeds + left * SellPercent.netSell(bid),
                        note = "встали на уровне",
                    )
                } else {
                    it
                }
            }
            engine.log(
                "trade",
                "Проба" + (if (open.demo) " (демо)" else "") + ": встали на уровне " +
                    Math.round(here) + " — забрала " + String.format("%.1f", left) +
                    " ${open.side} по ${(bid * 100).toInt()}¢",
            )
            onStateChanged()
        }
    }

    /**
     * Lets go of a side the book wrote off, the moment it is worth a third.
     *
     * Under a dime the market is saying one chance in ten and the position
     * has stopped being a position. Getting back to thirty-three cents means
     * the window turned round — and a turn that far is exactly the kind that
     * turns again, so this does not wait for a rung or for the close. It is
     * checked before the ladder, because the ladder's own price is up at
     * seventy-seven and would never fire here.
     */
    private fun rescue() {
        val riding = working.filter { it.shares > it.sold + 1e-9 }
        if (riding.isEmpty()) return

        for (open in riding) {
            val bid = try {
                ClobApi.bestBid(open.asset)
            } catch (e: Exception) {
                null
            } ?: continue

            // The worst the side has been worth, which is what arms this.
            val low = if (open.lowWater <= 0.0) bid else minOf(open.lowWater, bid)
            if (!ProbePlan.rescues(low, bid)) {
                if (low != open.lowWater) {
                    working = working.map {
                        if (it.windowStart == open.windowStart && it.leg == open.leg) {
                            it.copy(lowWater = low)
                        } else {
                            it
                        }
                    }
                }
                continue
            }

            val left = open.shares - open.sold
            val sold = if (open.demo) true else sellOut(open, bid)
            if (!sold) continue

            working = working.map {
                if (it.windowStart == open.windowStart && it.leg == open.leg) {
                    it.copy(
                        sold = open.shares,
                        proceeds = open.proceeds + left * SellPercent.netSell(bid),
                        lowWater = low,
                        note = "спасена с " + (low * 100).toInt() + "¢",
                    )
                } else {
                    it
                }
            }
            engine.log(
                "trade",
                "Проба" + (if (open.demo) " (демо)" else "") + ": падала до " +
                    (low * 100).toInt() + "¢ — забрала " + String.format("%.1f", left) +
                    " ${open.side} по ${(bid * 100).toInt()}¢",
            )
            onStateChanged()
        }
    }

    /**
     * Sells a real position into the book, pulling our own offers first.
     *
     * The shares under a resting sell are spoken for, and asking for them
     * again comes back as "not enough balance" — which is true and useless.
     */
    private fun sellOut(open: Round, bid: Double): Boolean {
        val session = engine.session() ?: return false
        val market = engine.marketForWindow(open.windowStart) ?: return false

        try {
            ClobApi.openOrders(session.creds, session.account.signerAddress)
                .filter { it.assetId == open.asset && it.side == "SELL" }
                .forEach {
                    try {
                        ClobApi.cancelOrder(session.creds, session.account.signerAddress, it.id)
                    } catch (e: Exception) {
                        // It may have filled in between, which the log will show.
                    }
                }
        } catch (e: Exception) {
            return false
        }

        val price = maxOf(market.tickSize, bid - market.tickSize)
        val result = try {
            engine.placeManualOrder(
                tokenId = open.asset,
                conditionId = market.conditionId,
                side = "SELL",
                price = price,
                size = open.shares - open.sold,
                orderType = "GTC",
                auto = true,
            )
        } catch (e: Exception) {
            note = e.message ?: "ошибка сети"
            return false
        }
        return result.success
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
                if (it.windowStart == open.windowStart && it.leg == open.leg) {
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
            why = reading,
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
                if (it.windowStart == open.windowStart && it.leg == open.leg) {
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
                next = next.map {
                if (it.windowStart == open.windowStart && it.leg == open.leg) moved else it
            }
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
    /** The round five hundreds either side of a price. */
    private fun roundAbove(here: Double): Double? {
        if (here <= 0.0) return null
        return Math.floor(here / ProbePlan.ROUND_STEP) * ProbePlan.ROUND_STEP +
            ProbePlan.ROUND_STEP
    }

    private fun roundBelow(here: Double): Double? {
        if (here <= 0.0) return null
        val at = Math.ceil(here / ProbePlan.ROUND_STEP) * ProbePlan.ROUND_STEP -
            ProbePlan.ROUND_STEP
        return at.takeIf { it > 0.0 }
    }

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

        working = working.filterNot { row ->
            done.any { it.windowStart == row.windowStart && it.leg == row.leg }
        }
        rounds = rounds + done.map {
            // What it went for, which is the price the buy-back is measured
            // under once the window carries on without it.
            val at = if (it.sold > 1e-9) it.proceeds / it.sold else 0.0
            it.copy(note = it.note ?: "продано лесенкой", soldAt = at)
        }
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
     * A window that made money adds half of it to what the next one
     * stakes, and the next win adds half of its own on top. A losing
     * window ends the run and the stake falls back to the base — so the run
     * only ever risks money the rule has already made.
     */
    private fun run(pnl: Double) {
        val next = ProbePlan.nextStreak(streak, pnl)
        if (next == streak) return
        streak = next
        riding = next
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

            stillOpen = stillOpen.filterNot { it.windowStart == open.windowStart && it.leg == open.leg }
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
