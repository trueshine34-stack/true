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
    /** The rungs the desk's own sell rule is using, which demo sells into. */
    private val ladder: () -> List<Double>,
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

    /** Bought and still riding, usually one and briefly two at a boundary. */
    @Volatile
    var working: List<Round> = emptyList()
        private set

    /** The line as the rule last read it, so the card shows what it sees. */
    @Volatile
    var trend: TrendFit.Trend? = null
        private set

    /** Where the reversal is expected, and how much room is left to it. */
    @Volatile
    var levelAhead: Double? = null
        private set

    @Volatile
    var roomToLevel: Double? = null
        private set

    /** Why nothing is being bought right now, in the person's words. */
    @Volatile
    var note: String? = null
        private set

    /**
     * What the paper account is worth: what it started with, plus everything
     * closed rounds came to, less what is currently in the market.
     *
     * Only demo rounds count. A run that traded for real and then switched to
     * paper should not have its paper balance moved by real money, and the
     * other way round.
     */
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

    private fun tick() {
        val nowSec = Clock.nowSec()

        // Scoring first: a round whose window has settled is finished business
        // and should be off the books before the next entry is considered.
        settleDue(nowSec)
        // And the paper positions walk their own ladder, since no rule on the
        // desk can see them.
        if (settings.enabled) workPaper(nowSec)

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
        val way = TrendFit.lean(line)

        val market = engine.marketForWindow(windowStart)
        if (market == null) {
            note = "рынок ещё не открыт"
            return
        }

        val token = if (way == "Up") market.up.tokenId else market.down.tokenId
        val ask = if (way.isEmpty()) {
            null
        } else {
            try {
                ClobApi.bestAsk(token)
            } catch (e: Exception) {
                null
            }
        }

        // The balance is a request, so it is only asked for once everything
        // free has already agreed.
        val here = BinanceCandles.oneMinute.list().lastOrNull()?.close ?: 0.0
        val cheap = ProbePlan.blockedBecause(
            way = way,
            ask = ask,
            cashUsd = settings.stakeUsd,
            settings = settings,
            price = here,
            level = levelAhead,
            // Levels come off the minute chart, with the line; how far a bet
            // can travel is a question about five minutes, so the scale is
            // still the five-minute candle's own range.
            typical = Levels.typicalRange(BinanceCandles.fiveMinute.list()),
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
            typical = Levels.typicalRange(BinanceCandles.fiveMinute.list()),
        )
        if (blocked != null) {
            note = blocked
            return
        }
        if (ask == null) return

        val size = ProbePlan.shares(settings.stakeUsd, ask, market.minimumOrderSize)
        // Crossing the spread can step over the window's own ceiling, and the
        // venue refuses such an order rather than shaving it.
        val limit = minOf(
            ProbePlan.crossPrice(ask, market.tickSize),
            BuyCap.ceiling(BuyCap.elapsedFor(market.windowStart)),
        )

        if (settings.demo) {
            paperBuy(windowStart, token, way, ask, size, line)
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
            // Nothing was taken. A resting buy is a bet the rule did not mean
            // to place — it wanted this window, at this price, now.
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

        var next = working
        var changed = false
        for (open in riding) {
            val elapsed = nowSec - open.windowStart
            if (elapsed < 0) continue

            val bid = try {
                ClobApi.bestBid(open.asset)
            } catch (e: Exception) {
                null
            } ?: continue

            val rungs = ladder().ifEmpty { SellLadder.DEFAULT }
            val high = maxOf(open.highWater, bid)
            val step = SellLadder.stepFor(elapsed, high, rungs, open.rung)
            val want = rungs[step.coerceIn(0, rungs.size - 1)]

            var moved = open.copy(highWater = high, rung = maxOf(open.rung, step))
            if (bid >= want - 1e-9) {
                // The offer would have been sitting there, so it is the rung
                // that gets paid, not the bid that reached up to it.
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
    private fun readLevel() {
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
        val walls = Levels.find(candles, here).filter { it.touches >= 2 }
        val level = Levels.ahead(walls, here, way)
        levelAhead = level
        roomToLevel = level?.let { abs(it - here) }
    }

    /** Scores every ridden round whose window has closed and settled. */
    private fun settleDue(nowSec: Long) {
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
