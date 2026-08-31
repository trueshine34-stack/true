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
         * How many shares that bid was for.
         *
         * The wallet is the only thing that reliably knows a resting buy
         * filled, and it also holds whatever was bought by hand on the same
         * side — so what may be adopted is capped at what was actually
         * ordered.
         */
        val restingSize: Double = 0.0,
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
        /**
         * When the position went under water, and whether it stayed there
         * long enough to change what it is worth waiting for.
         */
        val redFrom: Long = 0L,
        val wasRed: Boolean = false,

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

    /**
     * And the real account's own dial settings.
     *
     * The two accounts trade the same rule, but not necessarily on the same
     * terms: the point of running paper alongside real money is to try
     * something on the paper one — a longer lead, more room, a bigger stake —
     * while the wallet keeps doing what already works. Only the tunables are
     * split. Whether the rule runs at all, which accounts are on, and what
     * the paper account started with are one answer for the desk, not two.
     */
    @Volatile
    var realRules: ProbePlan.Settings = store.loadSettings(real = true)
        private set

    /**
     * The settings one account trades by: its own dials, and the desk's
     * answer for everything that is not a dial.
     */
    fun rules(demo: Boolean): ProbePlan.Settings =
        if (demo) {
            settings
        } else {
            realRules.copy(
                enabled = settings.enabled,
                demo = settings.demo,
                live = settings.live,
                bankUsd = settings.bankUsd,
            )
        }

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastFault: String? = null
        private set

    /** Whether the wallet is out, which stops the real half and nothing else. */
    @Volatile
    var walletOut: Boolean = false
        private set

    /** Closed rounds, oldest first — the report reads this. */
    @Volatile
    var rounds: List<Round> = store.loadRounds()
        private set

    /**
     * What the current winning run has added to the stake. Zero after a loss,
     * which is the whole of the rule: the run stakes winnings, never the base.
     *
     * One run per account. The two trade the same windows and still come
     * apart — a bid the venue never filled is a window the wallet sat out and
     * the paper account had — so neither account's run may be decided by the
     * other's results.
     */
    @Volatile
    var streakPaper: Double = store.loadStreak(demo = true)
        private set

    @Volatile
    var streakReal: Double = store.loadStreak(demo = false)
        private set

    fun streakOf(demo: Boolean) = if (demo) streakPaper else streakReal

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
     * How much room the gate is actually demanding right now, in dollars.
     *
     * The setting is a share of what a five-minute candle usually travels, so
     * what it means in money changes with the market — and "twenty percent"
     * on a screen says nothing about whether an entry twenty-five dollars
     * from a level will be allowed. This is the number that decides it.
     */
    @Volatile
    var roomNeed: Double? = null
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
    /** Everything one account's closed rounds have made. */
    fun won(demo: Boolean): Double = rounds
        .filter { it.demo == demo && it.shares > 0.0 }
        .sumOf { it.pnl }

    /**
     * What the next window is worth staking: the base, grown by every time the
     * account has doubled, plus whatever the winning run has added.
     */
    fun stakeNow(demo: Boolean): Double =
        ProbePlan.stakeFor(rules(demo).stakeUsd, won(demo), settings.bankUsd, streakOf(demo), held(demo))

    /** Set while the window still running is already showing a loss. */
    @Volatile
    var losingPaper: Boolean = false
        private set

    @Volatile
    var losingReal: Boolean = false
        private set

    fun losingOf(demo: Boolean) = if (demo) losingPaper else losingReal

    /**
     * What the account was worth when the rule last looked.
     *
     * The paper purse in demo, the wallet in real money. It is what the run's
     * ceiling is a quarter of, and it is remembered rather than asked for on
     * every read so the card can show the same stake the entry will use.
     */
    @Volatile
    var wallet: Double = 0.0
        private set

    /** The run's addition as the last tick found it, per account. */
    @Volatile
    private var ridingPaper: Double = streakPaper

    @Volatile
    private var ridingReal: Double = streakReal

    private fun ridingOf(demo: Boolean) = if (demo) ridingPaper else ridingReal

    /** What the next window will stake, the open window's state included. */
    fun stakeLive(demo: Boolean): Double =
        ProbePlan.stakeFor(rules(demo).stakeUsd, won(demo), settings.bankUsd, ridingOf(demo), held(demo))

    /** The account the run is a quarter of: the paper purse, or the wallet. */
    private fun held(demo: Boolean): Double = if (demo) bank else wallet

    /** Which accounts are trading this window — either, both, or neither. */
    private fun modesOn(): List<Boolean> = buildList {
        if (settings.demo) add(true)
        if (settings.live) add(false)
    }

    /**
     * Reads the run against the window that is still running.
     *
     * Staking the run on top of a window that is about to end it means the
     * largest bet of the sequence is placed exactly when the sequence is over.
     * So the position is marked to what the book would pay for it, and a run
     * sitting on a loss does not ride into the next window.
     */
    private fun readRun() {
        for (demo in listOf(true, false)) {
            readRun(demo)
        }
    }

    private fun readRun(demo: Boolean) {
        val open = working.firstOrNull { it.demo == demo && it.shares > it.sold + 1e-9 }
        if (open == null) {
            setLosing(demo, false)
            setRiding(demo, streakOf(demo))
            return
        }
        val bid = try {
            ClobApi.bestBid(open.asset)
        } catch (e: Exception) {
            null
        }
        if (bid == null) {
            setRiding(demo, streakOf(demo))
            return
        }

        val left = open.shares - open.sold
        val worth = open.proceeds + left * SellPercent.netSell(bid)
        setLosing(demo, worth < open.cost - 1e-9)
        setRiding(demo, ProbePlan.riding(streakOf(demo), worth, open.cost))
    }

    private fun setLosing(demo: Boolean, value: Boolean) {
        if (demo) losingPaper = value else losingReal = value
    }

    private fun setRiding(demo: Boolean, value: Double) {
        if (demo) ridingPaper = value else ridingReal = value
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

    /**
     * And which side it was going to buy.
     *
     * The line's own lean is not that side: a bounce goes the other way, and
     * a flat line still has one when a level decided it. A skipped window
     * that says only "пропуск: у уровня 78700" leaves out the half that
     * makes the reason mean anything — which level, and in which direction
     * the money was about to go.
     */
    private var aimSide = ""

    private companion object {
        /** A ten-second lead needs a clock, not a poll. */
        const val TICK_MS = 1_000L

        const val WINDOW_SEC = 300L

        /**
         * How many minutes back "where the market has been living" reaches.
         *
         * An hour: long enough for a range to be a range, short enough that a
         * level from this morning does not out-vote the one price is at.
         */
        const val HOME_OVER = 60

        /**
         * How close two walls have to be to be the same wall, in dollars.
         *
         * A cluster of pivots averages a few dollars off the high that made
         * it, and two lines that close together are one line to anybody
         * looking at the chart.
         */
        const val MERGE_USD = 5.0

        /** How often the wallet may be asked whether a resting bid filled. */
        const val WALLET_EVERY_MS = 5_000L

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

    fun update(next: ProbePlan.Settings, real: Boolean = false) {
        if (real) {
            realRules = next
            store.saveSettings(next, real = true)
            onStateChanged()
            return
        }
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
        streakPaper = 0.0
        streakReal = 0.0
        ridingPaper = 0.0
        ridingReal = 0.0
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

    /**
     * The last hour of minute closes, which is what says where the market has
     * been living. An hour is long enough to be a range and short enough that
     * a level from this morning does not out-vote the one price is at.
     */
    private fun recentCloses(): List<Double> =
        BinanceCandles.oneMinute.list().takeLast(HOME_OVER).map { it.close }

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
            // And the real equivalent: a bid the venue filled while nothing
            // here was looking is a position, not a pending order.
            catchFills()
            // A bid left on the book while the ask walks down is a chance
            // being watched rather than taken.
            chase(nowSec)
            // And a bid the market never came back to is taken back, so the
            // money is free for the next window.
            dropStale(nowSec)
            readSales()
            // The read is taken again ten seconds before the open, and a bid
            // still waiting on a picture that is no longer there is pulled.
            recheck(nowSec)
            // Nothing else buys. The entry is placed before the window opens
            // and that is the only buy there is: the top-ups at forty-two and
            // thirty-three, and the buy-back after a rung sold, both put more
            // money into a window that was already running — which is how a
            // window with one entry at 19:09 got a second buy at 19:12.
            workPaper(nowSec)
            // A sale booked this tick is filed this tick, so the run's next
            // stake is right before the next window opens.
            fileSold()
            // And what is still open is marked to the book, because the window
            // about to close decides whether the run survives it.
            readRun()
        }

        // Paper money needs no wallet: it reads the same public book, and
        // nothing it does is ever signed. Only real orders need a session, so
        // an unconnected wallet stops the real half and leaves the paper one
        // running — which is the whole point of the two being separate.
        val session = engine.session()
        walletOut = session == null
        if (walletOut && !settings.demo) {
            lastFault = "кошелёк не подключён"
            // So the window this costs is filed with the reason rather than
            // as a silent gap.
            aimNote = lastFault
            onStateChanged()
            return
        }
        lastFault = if (walletOut && settings.live) "кошелёк не подключён" else null

        val elapsed = SellLadder.elapsedInWindow(nowSec)
        val secondsLeft = WINDOW_SEC - elapsed
        val windowStart = nowSec - elapsed

        trend = TrendFit.onScreen()
        wide = TrendFit.wide()
        // The day's levels are re-read at most once a minute and merged into
        // what is already known, so a line the rule refused at is still there
        // on the next tick and still on the screen.
        DayLevels.refresh(nowSec)
        readLevel()

        if (!settings.enabled) {
            note = "выключен"
            onStateChanged()
            return
        }

        // Which entry each account is running. They need not agree — trying
        // the price entry on paper while the wallet keeps to the line is most
        // of the reason the two have separate dials at all.
        val insiders = modesOn().filter { rules(it).inside }
        val liners = modesOn().filter { !rules(it).inside }

        // Watching from inside replaces the guess made before the open. The
        // side is not picked ahead of time at all: the window is left to show
        // its hand, and whichever side the book is then asking too little for
        // is the one that gets bought.
        if (insiders.isNotEmpty()) {
            if (windowStart != aiming) {
                giveUp(nowSec)
                aiming = windowStart
                aimSide = ""
            }
            inside(nowSec, insiders)
            if (liners.isEmpty()) {
                aimNote = note
                onStateChanged()
                return
            }
        }

        if (liners.isEmpty()) {
            note = if (modesOn().isEmpty()) "счета выключены" else note
            onStateChanged()
            return
        }

        // The window is aimed at from the longest lead any of them wants, and
        // each account then waits for its own before it buys.
        val aim = settings.copy(leadSec = liners.maxOf { rules(it).leadSec })
        val target = ProbePlan.targetWindow(windowStart, elapsed, aim, WINDOW_SEC)
        // A chance that has come and gone is filed with the reason it did, so
        // "why did it not trade that one" is a question the report answers.
        if (target != aiming) {
            giveUp(nowSec)
            aiming = target ?: 0L
            aimSide = ""
        }
        if (target == null) {
            note = "жду открытия: " + (secondsLeft - aim.leadSec) + " с"
            onStateChanged()
            return
        }
        if (liners.all { traded(target, it) }) {
            note = "окно уже отыграно"
            onStateChanged()
            return
        }

        enter(target, liners)
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
        if (missed <= 0L) return

        val winner = EventStats.winnerFor(missed, nowSec)
        var filed = rounds
        for (demo in modesOn()) {
            if (traded(missed, demo)) continue
            filed = filed + Round(
                windowStart = missed,
                asset = "",
                demo = demo,
                // The side it was going to buy, and nothing when there was not
            // one. Falling back to the line's tilt here printed "Up · хотел,
            // но нет линии" — a row claiming a side in the same breath as
            // the reason there was none.
            side = aimSide,
                perHour = trend?.perHour ?: 0.0,
                shares = 0.0,
                price = 0.0,
                winner = winner,
                note = aimNote ?: "не успел",
                why = reading,
            )
        }
        if (filed === rounds) return
        rounds = filed
        store.saveRounds(rounds)
        engine.log(
            "warn",
            "Проба: пропустила окно " + hhmm(missed) + " — " + (aimNote ?: "не успел"),
        )
    }

    /**
     * Whether this account has already had its go at this window.
     *
     * Per account, because both may be running: the paper one having traded
     * a window says nothing about whether the wallet did.
     */
    private fun traded(windowStart: Long, demo: Boolean): Boolean =
        working.any { it.windowStart == windowStart && it.demo == demo } ||
            rounds.any { it.windowStart == windowStart && it.demo == demo }

    /** And whether either of them did, which is what the card reports. */
    private fun traded(windowStart: Long): Boolean =
        working.any { it.windowStart == windowStart } ||
            rounds.any { it.windowStart == windowStart }

    /**
     * Everything a decision is read off, taken in one go.
     *
     * The entry reads it, and so does the second look ten seconds before the
     * open — which only means anything if both are reading the same things
     * the same way.
     */
    private data class Look(
        val here: Double,
        val typical: Double,
        val closing: BinanceCandles.Candle?,
        val body: Double,
        val lastMinute: BinanceCandles.Candle?,
        val minuteRange: Double,
        val minuteTypical: Double,
        val above: ProbePlan.Wall?,
        val below: ProbePlan.Wall?,
        val pick: ProbePlan.Choice,
    )

    private fun look(windowStart: Long): Look {
        val here = here()
        val typical = Levels.typicalRange(BinanceCandles.fiveMinute.list())

        // The minute that closes as the window opens, and what the minutes
        // before it were — the candle itself is left out of its own average,
        // or an outsized one raises the bar it is being measured against.
        //
        // "The candle closing with the window" is the last one that started
        // before the window did, which is not the same as the last one there
        // is. The entry may also be taken in the seconds just after an open,
        // and there the newest candle is the window's own, seconds old, with
        // its open and close still the same price — a body of nought and a
        // range of nought. Every candle rule read that and saw nothing: no
        // closing candle to disagree with, no minute big enough to have
        // fired. The record said "свеча 5м: +0$" on a window whose candle
        // was plainly not flat.
        val minutes = BinanceCandles.oneMinute.list().filter { it.time < windowStart }
        val lastMinute = minutes.lastOrNull()
        val minuteRange = lastMinute
            ?.let { if (it.high > 0.0 && it.low > 0.0) it.high - it.low else 0.0 }
            ?: 0.0
        val minuteTypical = Levels.typicalRange(minutes.dropLast(1))

        val closing = BinanceCandles.fiveMinute.list()
            .lastOrNull { it.time < windowStart }
        val body = closing
            ?.let { if (it.open > 0.0 && it.close > 0.0) it.close - it.open else 0.0 }
            ?: 0.0

        // The prices that stop things, either side of here, each with the
        // weight it carries. The bounce is read off these before any line is
        // consulted, and how much weight a level has decides whether it may be
        // traded against the candle that is closing.
        // The high and the low of everything on the screen. They need no pivot
        // to confirm them — they are the prices that stopped the market
        // hardest in all of it — and the pivot rule could not confirm the
        // retest anyway, since the candle price is testing a level on can
        // never be the middle of five. Leaving them out is how the rule bought
        // Up nineteen dollars under the high of four hours.
        val fives = BinanceCandles.fiveMinute.list()
        val top = fives.filter { it.high > 0.0 }.maxOfOrNull { it.high }
        val bottom = fives.filter { it.low > 0.0 }.minOfOrNull { it.low }

        val shelf = merge(
            walls(here).map {
                ProbePlan.Wall(it.price, it.touches, round = false, low = it.low, high = it.high)
            } +
                listOfNotNull(
                    roundAbove(here)?.let { ProbePlan.Wall(it, 0, round = true) },
                    roundBelow(here)?.let { ProbePlan.Wall(it, 0, round = true) },
                    top?.takeIf { it > here }
                        ?.let { ProbePlan.Wall(it, 0, round = false, edge = true) },
                    bottom?.takeIf { it < here }
                        ?.let { ProbePlan.Wall(it, 0, round = false, edge = true) },
                ),
        )
        val above = shelf.filter { it.price > here }.minByOrNull { it.price - here }
        val below = shelf.filter { it.price < here }.minByOrNull { here - it.price }

        val pick = ProbePlan.choose(
            // The line's own call, not the way it happens to be tilted.
            //
            // [TrendFit.lean] answers "which end is higher" and never says
            // "no direction", so a fit that has refused to name one still
            // produced a side and the rule bought it. The 11:00 entry is the
            // case: the card printed "тренд 1м: вбок 128$/ч R² 0,18" — the
            // honest answer — and the rule bought Up "по тренду" off it.
            way = trend?.way.orEmpty(),
            // The five-minute line's own call, not merely its slope: a fit too
            // weak to name a direction has no business vetoing one.
            wide = wide?.way.orEmpty(),
        )
        return Look(
            here = here,
            typical = typical,
            closing = closing,
            body = body,
            lastMinute = lastMinute,
            minuteRange = minuteRange,
            minuteTypical = minuteTypical,
            above = above,
            below = below,
            pick = pick,
        )
    }

    /**
     * Buys a side the book is asking less for than it is worth.
     *
     * The entry below guesses which way a window will go before it opens.
     * Over a month of tape that guess is right 49% of the time, and no
     * arrangement of chart rules moved it — there is no edge in the question.
     *
     * This asks a different one. A window already running has half answered
     * itself: a side well ahead with a minute left is near certain, and the
     * only question is what the book charges for it. So the chance is worked
     * out from how far price has come and how long is left, the ask is read,
     * and the side is bought only where the ask is under it by enough to
     * matter. Every such buy is worth more than it cost whichever way that
     * particular window happens to end, which is the whole difference between
     * an edge and a hunch.
     */
    private fun inside(nowSec: Long, accounts: List<Boolean>) {
        if (accounts.isEmpty()) return

        val elapsed = SellLadder.elapsedInWindow(nowSec)
        val windowStart = nowSec - elapsed
        val left = WINDOW_SEC - elapsed
        if (elapsed < ProbePlan.EDGE_FROM_SEC || left <= 0L) return

        val opened = WindowOpen.of(windowStart, engine.feed)
        if (opened == null || opened <= 0.0) {
            note = "нет цены открытия"
            return
        }
        val here = here()
        if (here <= 0.0) return
        val typical = Levels.typicalRange(BinanceCandles.fiveMinute.list())
        if (typical <= 0.0) return

        val market = engine.marketForWindow(windowStart)
        if (market == null) {
            note = "рынок ещё не открыт"
            return
        }

        // Both sides, priced and quoted, and whichever is the better buy.
        val moved = here - opened
        var best: Triple<String, Double, Double>? = null
        for (way in listOf("Up", "Down")) {
            val fair = FairValue.chance(way, moved, typical, left, WINDOW_SEC)
            val token = if (way == "Up") market.up.tokenId else market.down.tokenId
            val ask = try {
                ClobApi.bestAsk(token)
            } catch (e: Exception) {
                null
            } ?: continue
            val edge = ProbePlan.edgeOn(fair, ask)
            if (!ProbePlan.worthTaking(fair, ask, elapsed, left, accounts.minOf { rules(it).edgeUsd })) continue
            if (best == null || edge > best.third) best = Triple(way, ask, edge)
        }

        val (way, ask, edge) = best ?: run {
            note = "нет расхождения с ценой"
            return
        }

        val fair = FairValue.chance(way, moved, typical, left, WINDOW_SEC)
        for (demo in accounts) {
            if (traded(windowStart, demo)) continue
            if (!ProbePlan.worthTaking(fair, ask, elapsed, left, rules(demo).edgeUsd)) continue
            takeUnderpriced(windowStart, demo, market, way, ask, edge, fair, moved, typical, opened, here, elapsed, left)
        }
    }

    /** One account's buy of an underpriced side, once the side is settled. */
    private fun takeUnderpriced(
        windowStart: Long,
        demo: Boolean,
        market: Market,
        way: String,
        ask: Double,
        edge: Double,
        fair: Double,
        moved: Double,
        typical: Double,
        opened: Double,
        here: Double,
        elapsed: Long,
        left: Long,
    ) {
        val staking = stakeLive(demo)
        val cash = if (demo) bank else wallet
        if (cash > 0.0 && cash < staking) {
            note = if (demo) "тестовый счёт пуст" else "на счету пусто"
            return
        }

        reading = listOf(
            "хотел: " + (if (way == "Up") "вверх" else "вниз") + " — недооценена",
            "прошло: " + elapsed + " с, осталось " + left + " с",
            "ход от открытия: " + Math.round(moved) + "$ (" +
                String.format("%.2f", moved / typical) + "× обычного)",
            "обычный ход 5м: " + Math.round(typical) + "$",
            "цена открытия: " + Math.round(opened),
            "цена BTC: " + Math.round(here),
            "справедливо: " + Math.round(fair * 100) + "¢",
            "аск: " + (ask * 100).toInt() + "¢ — с комиссией " +
                Math.round(ProbePlan.takenPrice(ask) * 100) + "¢",
            "запас: " + Math.round(edge * 100) + "¢ на голос",
            "ставка: \$" + String.format("%.2f", staking),
        ).joinToString("\n")

        val size = ProbePlan.shares(staking, ask, market.minimumOrderSize)
        val token = if (way == "Up") market.up.tokenId else market.down.tokenId

        if (demo) {
            paperBuy(windowStart, token, way, ask, size, trend, 0.0)
            note = "взяла недооценённую (демо)"
            return
        }

        val limit = minOf(
            ProbePlan.crossPrice(ask, market.tickSize),
            BuyCap.ceiling(BuyCap.elapsedFor(windowStart)),
        )
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
            return
        }
        val fill = Orders.filled("BUY", result.makingAmount, result.takingAmount)
        if (fill.shares <= 1e-6) {
            result.orderId?.let { cancel(it) }
            note = "не налили по ${(ask * 100).toInt()}¢"
            return
        }
        working = working + Round(
            windowStart = windowStart,
            asset = token,
            demo = false,
            side = way,
            perHour = trend?.perHour ?: 0.0,
            shares = fill.shares,
            price = if (fill.usd > 0.0) fill.usd / fill.shares else limit,
            why = reading,
        )
        note = "взяла недооценённую"
        engine.log(
            "trade",
            "Проба: $way по ${(ask * 100).toInt()}¢ при справедливых " +
                "${Math.round(fair * 100)}¢ — запас ${Math.round(edge * 100)}¢",
        )
        onStateChanged()
    }

    /**
     * Buys into the window that is about to open.
     *
     * Gamma publishes the next market shortly before it opens, so the entry is
     * only possible once it is there — which is the same reason the lead is
     * ten seconds and not two minutes.
     */
    private fun enter(windowStart: Long) {
        // Each account that is switched on gets its own go at the window.
        // They read the same picture and buy the same side; what differs is
        // whose money it is, what that account can afford, and — for the real
        // one — whether the venue fills the order at all. That last one is
        // why the two histories are worth having side by side.
        enter(windowStart, modesOn())
    }

    private fun enter(windowStart: Long, accounts: List<Boolean>) {
        for (demo in accounts) {
            if (traded(windowStart, demo)) continue
            enter(windowStart, demo)
        }
    }

    private fun enter(windowStart: Long, demo: Boolean) {
        // Each account waits for its own lead. The window was aimed at from
        // the longest one any of them wanted, so the shorter lead simply has
        // not come round yet.
        val mine = rules(demo)
        if (windowStart - Clock.nowSec() > mine.leadSec) return
        val line = trend

        // Gamma publishes the next window's market shortly before it opens,
        // and "shortly" is not a promise. The note says how much of the lead
        // has already gone waiting for it, because an entry that lands two
        // seconds after the open rather than fifty before it is nearly always
        // this and not a rule refusing.
        val market = engine.marketForWindow(windowStart)
        if (market == null) {
            val out = windowStart - Clock.nowSec()
            note = if (out > 0) "рынок ещё не опубликован — $out с до открытия"
            else "рынок ещё не опубликован"
            return
        }

        val seen = look(windowStart)
        val here = seen.here
        val typical = seen.typical
        val closing = seen.closing
        val body = seen.body
        val lastMinute = seen.lastMinute
        val minuteRange = seen.minuteRange
        val minuteTypical = seen.minuteTypical
        val above = seen.above
        val below = seen.below
        val pick = seen.pick

        val way = pick.side
        aimSide = way
        if (way.isEmpty()) {
            note = pick.note
            return
        }
        chose = pick.note

        // And the wall this side is heading into, which for a bounce is the
        // one across the room rather than the one just left behind.
        //
        // Its near edge, not its middle. A level is a zone — the prices the
        // market actually turned at — and resistance begins at the first of
        // them. Measuring to the middle counted half the zone as clear air,
        // which is how a side got bought into a shelf it had been failing at
        // for two hours.
        val aheadWall = if (way == "Up") above else below
        val ahead = aheadWall?.facing(way)

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
        val staking = stakeLive(demo)

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
            closing = closing,
            lastMinute = lastMinute,
            minuteRange = minuteRange,
            minuteTypical = minuteTypical,
            ask = ask,
            staking = staking,
            demo = demo,
        )

        // The balance is a request, so it is only asked for once everything
        // free has already agreed.
        val cheap = ProbePlan.blockedBecause(
            way = way,
            ask = ask,
            cashUsd = staking,
            settings = mine,
            price = here,
            level = ahead,
            candleOpen = closing?.open ?: 0.0,
            candleHigh = closing?.high ?: 0.0,
            candleLow = closing?.low ?: 0.0,
            candleClose = closing?.close ?: 0.0,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = typical,
            minuteRange = minuteRange,
            minuteBody = body(lastMinute),
            minuteTypical = minuteTypical,
            levelEdge = aheadWall?.edge == true,
            byLine = pick.byLine,
            stake = staking,
        )
        if (cheap != null) {
            note = cheap
            return
        }

        // On paper the purse is the paper purse, and asking the venue what the
        // wallet holds would be asking the wrong question of the wrong money.
        val cash = if (demo) {
            bank
        } else {
            try {
                engine.usdcBalance()
            } catch (e: Exception) {
                note = e.message ?: "не прочитать баланс"
                return
            }
        }
        // Now the account is known, so the run's ceiling is too — and it is
        // this window's, not the last one's.
        if (!demo) wallet = cash
        val stake = ProbePlan.capped(staking, mine.stakeUsd, cash)
        val blocked = ProbePlan.blockedBecause(
            way = way,
            ask = ask,
            cashUsd = cash,
            settings = mine,
            price = here,
            level = ahead,
            candleOpen = closing?.open ?: 0.0,
            candleHigh = closing?.high ?: 0.0,
            candleLow = closing?.low ?: 0.0,
            candleClose = closing?.close ?: 0.0,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = typical,
            minuteRange = minuteRange,
            minuteBody = body(lastMinute),
            minuteTypical = minuteTypical,
            levelEdge = aheadWall?.edge == true,
            byLine = pick.byLine,
            stake = stake,
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
        val size = ProbePlan.shares(stake, pay, market.minimumOrderSize)
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

        if (demo) {
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
                    restingSize = size,
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
            // Nothing was taken at a price it meant to take, so the order is
            // pulled: a resting buy is a bet the rule did not mean to place.
            //
            // But the cancel and a late fill race each other, and losing that
            // race used to leave real shares in the wallet with no round over
            // them — no ladder, no top-up, nothing in this rule's history at
            // all, while the desk plainly showed the position. So it is
            // written down as an order being watched either way: if the
            // cancel won, the minute mark files it; if the fill won, the log
            // says so and it becomes the position it always was.
            result.orderId?.let { cancel(it) }
            working = working + Round(
                windowStart = windowStart,
                asset = token,
                demo = false,
                side = way,
                perHour = line?.perHour ?: 0.0,
                shares = 0.0,
                price = 0.0,
                resting = limit,
                restingSize = size,
                target = aim,
                why = reading,
            )
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
        /** The five-minute candle the gates read, wicks and all. */
        closing: BinanceCandles.Candle?,
        /** The same minute the gates read, not whichever is newest. */
        lastMinute: BinanceCandles.Candle?,
        minuteRange: Double,
        minuteTypical: Double,
        ask: Double?,
        staking: Double,
        /** Whose money this reading is about: the stake line differs. */
        demo: Boolean,
    ): String {
        val cents = { p: Double -> "${(p * 100).toInt()}¢" }
        val dollars = { v: Double -> (if (v >= 0) "+" else "−") + Math.round(abs(v)) + "$" }
        val closes = recentCloses()
        val wall = { w: ProbePlan.Wall? ->
            w?.let {
                Math.round(it.price).toString() +
                    // The band, when the turns that made it actually cover
                    // one: the room in front is measured to its near edge.
                    (
                        if (it.high - it.low >= 1.0) {
                            " (" + Math.round(it.low) + "–" + Math.round(it.high) + ")"
                        } else {
                            ""
                        }
                        ) +
                    (
                        when {
                            it.round -> " (круглый)"
                            it.edge -> " (край)"
                            else -> " (×" + it.touches + ")"
                        }
                        ) +
                    // Which side of it the hour has been spent on, when one
                    // side has clearly had it.
                    (
                        when (ProbePlan.homeSide(closes, it.price)) {
                            "Up" -> " · час выше"
                            "Down" -> " · час ниже"
                            else -> ""
                        }
                        )
            } ?: "нет"
        }
        val minute = body(lastMinute)
        val room = if (aim > 0.0 && here > 0.0) abs(aim - here) else 0.0
        // The side comes first, because every line under it is evidence for
        // or against that one word — and a window that was skipped keeps this
        // reading, where "у уровня 78700" without a direction says nothing
        // about what was nearly bought.
        val wanted = when (pick.side) {
            "Up" -> "вверх"
            "Down" -> "вниз"
            else -> "не вошёл"
        }

        return listOf(
            "хотел: " + wanted + " — " + (pick.note ?: "по тренду"),
            "тренд 1м: " + (line?.way.orEmpty().ifEmpty { "вбок" }) +
                " " + Math.round(line?.perHour ?: 0.0) + "$/ч" +
                " R² " + String.format("%.2f", line?.fit ?: 0.0),
            "тренд 5м: " + (wide?.way.orEmpty().ifEmpty { "вбок" }),
            "свеча 5м: " + dollars(body) + " · минутка: " + dollars(minute),
            "обычный ход 5м: " + Math.round(typical) + "$",
            // The wick on our side, and the third of the range that stops
            // an entry — the one candle rule the entry still has.
            "хвост свечи: " + (
                closing?.let {
                    val range = it.high - it.low
                    val wick = if (pick.side == "Up") {
                        it.high - maxOf(it.open, it.close)
                    } else {
                        minOf(it.open, it.close) - it.low
                    }
                    if (range > 0.0) {
                        Math.round(wick / range * 100).toString() + "% из " +
                            Math.round(ProbePlan.WICK_AT * 100) + "%"
                    } else {
                        "нет"
                    }
                } ?: "нет"
                ),
            "размер минутки: " + (
                if (minuteTypical > 0.0) {
                    String.format("%.1f", minuteRange / minuteTypical) + "× обычной"
                } else {
                    "нечем мерить"
                }
                ),
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
                (
                    if (streakOf(demo) > 0.0) {
                        " (серия +$" + String.format("%.2f", streakOf(demo)) + ")"
                    } else {
                        ""
                    }
                    ) +
                (
                    if (held(demo) > 0.0) {
                        " · потолок $" + String.format("%.2f", held(demo) * ProbePlan.MAX_SHARE)
                    } else {
                        ""
                    }
                    ),
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
        /**
         * Whether this price is on the book or only being watched for.
         *
         * Up to the last minute it is watched: nothing rests, and the shares
         * go into the bid the moment it reaches the price — so the number is a
         * floor. Inside the last minute it rests, and is a ceiling.
         */
        val resting: Boolean,
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
                        // The rung stands on the book all window: it is the
                        // price the shares will fetch, not a price to wait for.
                        resting = true,
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
     * Whether one more buy would put the window over its ceiling.
     *
     * Everything this window has spent already counts — the entry, its
     * top-ups, and any leg bought back after a sale — because the ceiling is
     * a limit on what rides on five minutes, not on any single order.
     */
    private fun overCap(windowStart: Long, demo: Boolean, more: Double): Boolean {
        val cap = ProbePlan.windowCap(rules(demo).stakeUsd, held(demo))
        val already =
            working.filter { it.windowStart == windowStart && it.demo == demo }.sumOf { it.cost } +
                rounds.filter { it.windowStart == windowStart && it.demo == demo }.sumOf { it.cost }
        return already + more > cap + 1e-9
    }

    /**
     * Takes the read again ten seconds before the window opens.
     *
     * The entry goes in three quarters of a minute early, which is where the
     * cheap side is — before the book starts pricing the open. The cost of
     * being that early is that the five-minute candle has not finished yet,
     * and thirty-five seconds is long enough for it to finish the other way.
     * So the same read is taken again with the candle all but closed. A bid
     * still waiting on a read that no longer holds is taken back — there is
     * nothing bought yet, and letting it fill would buy a side the rule has
     * stopped believing in.
     *
     * A position that is already bought is sold, at the market, there and
     * then. The ladder is the exit for a trade the rule still believes in;
     * this is a trade it has stopped believing in before the window has even
     * begun, and holding it to a rung would be riding a side chosen on a
     * picture that is no longer on the screen. If the read has not changed,
     * nothing happens and the position rides.
     */
    private fun recheck(nowSec: Long) {
        // Everything this window has going: a position that is held, or a bid
        // that is still waiting. Both are answers to the same read, and the
        // read is about to be taken again.
        val due = working.filter { open ->
            if (!ProbePlan.rechecks(open.windowStart - nowSec)) return@filter false
            val held = open.shares > open.sold + 1e-9
            val waiting = open.resting > 0.0 && open.shares <= 0.0
            held || waiting
        }
        if (due.isEmpty()) return

        // Read for the window the entry was made for, not for whatever window
        // the clock is in.
        val fresh = look(due.first().windowStart).pick.side

        // What is already bought and no longer believed in goes back at the
        // market. The five-minute candle the side was chosen on had not
        // finished when the entry went in; with ten seconds left it has, and
        // if it now points the other way the entry was simply wrong.
        for (open in due.filter { it.shares > it.sold + 1e-9 }) {
            if (ProbePlan.stillOn(open.side, fresh)) continue
            sellOut(open, "прогноз сменился")
        }

        val waiting = due.filter { it.resting > 0.0 && it.shares <= 0.0 }

        // A bid still waiting for a window whose read has changed is simply
        // taken back: there is nothing to sell, and letting it fill would buy
        // the side the rule has just stopped believing in.
        for (open in waiting) {
            if (ProbePlan.stillOn(open.side, fresh)) continue
            if (!open.demo) cancelBuys(open.asset)
            // It may have filled in the meantime, in which case there is a
            // position to sell rather than an order to pull — and the loop
            // above will sell it on the next tick.
            if (adopt(open)) continue
            working = working.filterNot {
                it.windowStart == open.windowStart && it.leg == open.leg
            }
            rounds = rounds + open.copy(
                winner = EventStats.winnerFor(open.windowStart, nowSec),
                note = "прогноз сменился — заявка снята",
            )
            store.saveRounds(rounds)
            engine.log(
                "info",
                "Проба: прогноз сменился до открытия — сняла заявку на " +
                    "${open.side} по ${(open.resting * 100).toInt()}¢",
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
     * Pulls a bid that the market never came back to.
     *
     * It was left at the rest price for a window five minutes long; a side
     * that has not reached it in the first minute is not going to fill as the
     * same trade, and what would fill later is a different bet at the same
     * number. Taking it back frees the money for the next window and files the
     * one it was for with that as the reason.
     */
    /**
     * Watches the price for the whole lead, not only at the first look.
     *
     * The entry reads the book once, fifty seconds out. A side asking more
     * than the rule will pay gets a bid at the take price — and that used to
     * be the end of it: the bid sat there for the rest of the lead while the
     * ask walked down through the take price and out the other side, and the
     * window was entered at whatever the bid happened to catch, or not at
     * all. Fifty seconds is a long time in a book that reprices every tick.
     *
     * So while the window has not opened and the bid has not filled, the ask
     * is read again every tick. The moment it is worth taking, the bid comes
     * off the book and the side is bought at the market — which is what the
     * rule wanted in the first place and only refused because of the price at
     * one particular second.
     */
    private fun chase(nowSec: Long) {
        val waiting = working.filter {
            it.resting > 0.0 && it.shares <= 0.0 && nowSec < it.windowStart
        }
        if (waiting.isEmpty()) return

        for (open in waiting) {
            val ask = try {
                ClobApi.bestAsk(open.asset)
            } catch (e: Exception) {
                null
            } ?: continue
            // Still dearer than the rule will pay: the bid keeps waiting.
            if (ProbePlan.waits(ask)) continue

            // Cancel first, then ask what happened: cancelling closes the book
            // to any further fill, so the answer afterwards is final rather
            // than a race — and a bid that filled on the way is a position,
            // not an order to replace.
            if (!open.demo) cancelBuys(open.asset)
            if (adopt(open)) continue

            working = working.filterNot {
                it.windowStart == open.windowStart && it.leg == open.leg
            }
            note = "подешевело до ${(ask * 100).toInt()}¢ — беру по рынку"
            engine.log(
                "info",
                "Проба: ${open.side} подешевела до ${(ask * 100).toInt()}¢ — " +
                    "сняла заявку по ${(open.resting * 100).toInt()}¢ и беру по рынку",
            )
            onStateChanged()
        }
    }

    private fun dropStale(nowSec: Long) {
        val waiting = working.filter { it.resting > 0.0 && it.shares <= 0.0 }
        if (waiting.isEmpty()) return

        for (open in waiting) {
            if (!ProbePlan.restingDone(nowSec - open.windowStart)) continue
            // Cancel first, then ask what happened: cancelling closes the
            // book to any further fill, so what the log says afterwards is
            // final rather than a race.
            if (!open.demo) cancelBuys(open.asset)
            if (adopt(open)) continue

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

    /**
     * What a bid left on the book actually bought, off the order log.
     *
     * This rule never hears about its own fills. The buy was left resting and
     * whatever became of it exists only in the log, which nothing here was
     * asking — so a bid that filled inside its first minute was cancelled
     * (nothing to cancel), filed as "лимитка снята", and dropped from the
     * working list, leaving real shares in the wallet with no ladder over
     * them and a history saying the opposite of what happened.
     *
     * The log is brought up to date first, because the sweep that normally
     * does that belongs to the sell rule and may not be running.
     *
     * Returns the round as a position, or null when nothing was bought.
     */
    private fun claim(open: Round, refresh: Boolean = true): Round? {
        if (open.demo || open.resting <= 0.0 || open.shares > 1e-6) return null

        if (refresh) engine.session()?.let { session ->
            try {
                val live = ClobApi.openOrders(session.creds, session.account.signerAddress)
                OrderLog.reconcile(live) { id ->
                    ClobApi.order(session.creds, session.account.signerAddress, id)
                }
            } catch (e: Exception) {
                // Then the log is whatever it already knew, which is still
                // better than assuming nothing filled.
            }
        }

        val buys = OrderLog.forWindow(open.windowStart)
            .filter { it.asset == open.asset && it.action == "BUY" }
        val got = buys.sumOf { it.matched }
        if (got > 1e-6) {
            val paid = buys.sumOf { it.matched * it.realPrice }
            return open.copy(shares = got, price = if (paid > 0.0) paid / got else open.resting)
        }

        // The log did not hear about it, and that is a thing that happens: an
        // order that filled has left the book, and an order the venue no
        // longer knows about looks exactly like one that was cancelled — so
        // the log deliberately leaves it alone rather than guess, and waits
        // for the trade feed, which belongs to another rule and may not be
        // running.
        //
        // The wallet does not guess. Shares of this outcome that are there
        // when a bid for them was out mean the bid filled, whatever anything
        // else believes. Only up to the size ordered, so a position the user
        // built by hand on the same side is not quietly adopted.
        // On the path that is about to write the round off, the wallet is
        // asked whatever the rate limit says: being told "ask later" there
        // would file a filled bid as a cancelled one, which is the whole
        // fault being fixed.
        return heldNow(open, force = refresh)?.let { held ->
            val size = minOf(held, open.restingSize)
            if (size <= 1e-6) null else open.copy(shares = size, price = open.resting)
        }
    }

    /**
     * When the wallet was last asked, so a bid out for a minute does not ask
     * it sixty times. Five seconds is soon enough to notice a fill and slow
     * enough not to earn a rate limit — which the desk's sell rule has
     * already been punished with once.
     */
    @Volatile
    private var askedWalletAt: Long = 0L

    /** What the wallet actually holds of this outcome, or null if unknown. */
    private fun heldNow(open: Round, force: Boolean): Double? {
        val session = engine.session() ?: return null
        val now = System.currentTimeMillis()
        if (!force && now - askedWalletAt < WALLET_EVERY_MS) return null
        askedWalletAt = now
        return try {
            DataApi.positions(session.account.funderAddress)
                .firstOrNull { it.asset == open.asset }
                ?.size
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Adopts a bid that turned out to have filled, and says so.
     *
     * Returns true when there was a fill, in which case the round belongs in
     * the working list rather than in the history.
     */
    private fun adopt(open: Round, refresh: Boolean = true): Boolean {
        val filled = claim(open, refresh) ?: return false
        working = working.map {
            if (it.windowStart == open.windowStart && it.leg == open.leg) filled else it
        }
        engine.log(
            "trade",
            "Проба: лимитка на ${open.side} по ${(open.resting * 100).toInt()}¢ " +
                "всё-таки налилась — " + String.format("%.1f", filled.shares) +
                " по ${(filled.price * 100).toInt()}¢",
        )
        onStateChanged()
        return true
    }

    /**
     * Notices a real bid that has filled, as soon as the log knows.
     *
     * Without this a limit entry filled at five seconds past the open sat
     * with shares of zero until the minute ran out and the round was filed —
     * so for that whole minute no rung, no top-up and no rescue could see the
     * position it was holding. Costs nothing: the log is read as it stands,
     * and the exchange is only asked on the paths that are about to write the
     * round off for good.
     */
    private fun catchFills() {
        val waiting = working.filter { !it.demo && it.resting > 0.0 && it.shares <= 0.0 }
        for (open in waiting) adopt(open, refresh = false)
    }

    /** Takes back whatever this rule has resting on a side. */
    /**
     * Sells everything still held of one round, now, at the market.
     *
     * The one exit that is not a rung. It fires before the window has opened,
     * on a side the rule has stopped believing in — so it is not a view about
     * price at all, it is undoing an entry, and the ladder has nothing to say
     * about a trade that should not have been made.
     *
     * On paper the sale is booked at the bid, less the fee. For real money the
     * ladder's own offer is pulled first, because an offer resting above the
     * market is in the way of a sale meant to happen at once.
     */
    private fun sellOut(open: Round, why: String) {
        val left = open.shares - open.sold
        if (left <= 1e-9) return

        val bid = try {
            ClobApi.bestBid(open.asset)
        } catch (e: Exception) {
            null
        } ?: return

        if (open.demo) {
            working = working.map {
                if (it.windowStart == open.windowStart && it.leg == open.leg) {
                    it.copy(
                        sold = open.shares,
                        proceeds = open.proceeds + left * SellPercent.netSell(bid),
                        note = why,
                    )
                } else {
                    it
                }
            }
            engine.log(
                "trade",
                "Проба (демо): $why — продала " + String.format("%.1f", left) +
                    " ${open.side} по ${(bid * 100).toInt()}¢",
            )
            onStateChanged()
            return
        }

        val market = engine.marketForWindow(open.windowStart) ?: return
        cancelSells(open.asset)
        val limit = maxOf(
            market.tickSize,
            ProbePlan.snapDown(bid - market.tickSize, market.tickSize),
        )
        val result = try {
            engine.placeManualOrder(
                tokenId = open.asset,
                conditionId = market.conditionId,
                side = "SELL",
                price = limit,
                size = left,
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
        engine.log(
            "trade",
            "Проба: $why — продала " + String.format("%.1f", left) +
                " ${open.side} по ${(limit * 100).toInt()}¢",
        )
        onStateChanged()
    }

    /** The ladder's own offer, out of the way of a sale meant to happen now. */
    private fun cancelSells(asset: String) {
        val session = engine.session() ?: return
        try {
            ClobApi.openOrders(session.creds, session.account.signerAddress)
                .filter { it.assetId == asset && it.side == "SELL" }
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
            restingSize = ProbePlan.shares(stakeLive(true), ProbePlan.REST_PRICE, 5.0),
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

            val size = ProbePlan.shares(stakeNow(true), open.resting, 5.0)
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

            val high = maxOf(open.highWater, bid)
            val step = ProbePlan.exitStep(elapsed, high, open.rung, rule)
            var moved = open.copy(
                highWater = high,
                rung = maxOf(open.rung, step),
            )
            if (SellLadder.reached(bid, want)) {
                // The rung is an offer standing on the book for the whole
                // window, so it fills at the price it asked and not at the bid
                // that reached it. A bid that jumps clean past the rung still
                // pays the rung — which is what resting costs, and what it
                // buys is a sale that happens without anything having to be
                // watching at that second.
                val left = open.shares - open.sold
                moved = moved.copy(
                    sold = open.shares,
                    proceeds = open.proceeds + left * SellPercent.netSell(want),
                )
                engine.log(
                    "trade",
                    "Проба (демо): лимитка сработала — " +
                        String.format("%.1f", left) +
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
     * Folds walls that name the same price into one.
     *
     * A pivot cluster and the range's own high land within a dollar or two of
     * each other, and picking whichever happens to be nearer would drop the
     * fact that it is the edge — which is the half that decides how much room
     * the entry needs.
     */
    private fun merge(shelf: List<ProbePlan.Wall>): List<ProbePlan.Wall> {
        val out = ArrayList<ProbePlan.Wall>()
        for (wall in shelf.sortedBy { it.price }) {
            val last = out.lastOrNull()
            if (last != null && abs(wall.price - last.price) <= MERGE_USD) {
                out[out.size - 1] = last.copy(
                    touches = maxOf(last.touches, wall.touches),
                    round = last.round || wall.round,
                    edge = last.edge || wall.edge,
                    // Two walls near enough to be one are one zone, and it
                    // spans both of them — otherwise merging quietly threw
                    // away the half of the band that was further out.
                    low = minOf(last.low, wall.low),
                    high = maxOf(last.high, wall.high),
                )
            } else {
                out.add(wall)
            }
        }
        return out
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
        // The day's levels, which do not move, plus whatever the minute
        // chart has turned at since — a shelf made in the last twenty minutes
        // is real and is too young to be in the day's reading yet.
        //
        // All of them, not a chart's pick of three. Those three seat the
        // nearest turn either side whether or not anything bounced there, so
        // the filter used to throw away two of the three and leave the rule
        // with one wall, which is how a support the five-minute chart had
        // plainly bounced off twice was not a support as far as the gate was
        // concerned.
        return DayLevels.all(here) +
            Levels.tested(BinanceCandles.oneMinute.list(), here)
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
        roomNeed = Levels.typicalRange(BinanceCandles.fiveMinute.list())
            .takeIf { it > 0.0 }
            // The setting or the floor under it, whichever asks for more —
            // showing the setting alone said "нужен запас 24$" on a window
            // that in fact needed seventy-eight. The tile does not know
            // whether the wall ahead is the edge of the range, so it quotes
            // the ordinary requirement; an edge asks for half again more.
            // One tile, two accounts: it quotes the strictest requirement in
            // play, so the number on the card never claims an entry has room
            // that one of them will refuse.
            ?.let { typical ->
                val need = modesOn()
                    .maxOfOrNull { ProbePlan.roomNeeded(rules(it).roomShare, levelEdge = false) }
                    ?: ProbePlan.roomNeeded(settings.roomShare, levelEdge = false)
                typical * need
            }

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
        done.forEach { carryRun(it.demo, it.pnl) }
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
    private fun carryRun(demo: Boolean, pnl: Double) {
        val was = streakOf(demo)
        val next = ProbePlan.nextStreak(was, pnl)
        if (next == was) return
        if (demo) streakPaper = next else streakReal = next
        setRiding(demo, next)
        store.saveStreak(demo, next)
        engine.log(
            "info",
            (if (demo) "Проба (демо): " else "Проба: ") +
                if (next > 0.0) {
                    "серия — следующая ставка $" + String.format("%.2f", stakeNow(demo))
                } else {
                    "серия прервана — ставка снова $" + String.format("%.2f", stakeNow(demo))
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
            carryRun(scored.demo, scored.pnl)
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
        val round = claim(open) ?: open

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
