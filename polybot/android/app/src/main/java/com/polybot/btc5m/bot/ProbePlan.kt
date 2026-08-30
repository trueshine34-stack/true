package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * The experiment: buy the way the chart's line points, just before the window
 * opens, and leave by the ladder.
 *
 * It is deliberately the simplest thing that can be measured. One entry per
 * window, always the same money, always the direction the five-minute chart is
 * already drawing — so what the record afterwards says is whether following
 * that line pays, and not whether some cleverness on top of it did.
 *
 * Ten seconds before the open, because that is late enough for the line to
 * include everything the last window did and early enough that the price has
 * not yet moved on the new one.
 */
object ProbePlan {

    /** What each window is worth risking, in dollars. */
    const val DEFAULT_STAKE = 5.0

    /** What the paper account starts with. */
    const val DEFAULT_BANK = 100.0

    /** How long before the window opens the entry goes in. */
    const val DEFAULT_LEAD_SEC = 20L

    /**
     * The dearest offer worth taking at the market.
     *
     * Above it the entry is not abandoned — the line still points somewhere —
     * but it stops paying whatever is asked and leaves a bid instead. A side
     * that opens dear is a side someone else has already paid for; if it is
     * coming back to us it will come back to [REST_PRICE], and if it is not,
     * the window was not ours.
     */
    const val MAX_TAKE = 0.57

    /** Where the bid waits when the offer is dearer than that. */
    const val REST_PRICE = 0.54

    /**
     * The grid of prices everybody else is watching too.
     *
     * Eighty thousand, eighty and a half, eighty-one. Nobody decided these are
     * levels; they are levels because they are the numbers people write orders
     * at, and the book is thick at every one of them whatever the chart says.
     * A five-minute bet opening within reach of one is a bet on a price that
     * has somewhere obvious to stall and turn.
     */
    const val ROUND_STEP = 500.0

    /** How close to one of them is too close, in dollars. Zero switches off. */
    const val DEFAULT_ROUND_BAND = 50.0

    /**
     * How close a wick has to come to a level to have touched it.
     *
     * Against what a five-minute candle usually travels, so it is a fraction
     * of the day's own movement rather than a number of dollars that means
     * something different every week.
     */
    const val TOUCH = 0.35

    /** Which side the rule ends up on, and why it is not simply the line. */
    data class Choice(val side: String, val note: String?) {
        /** True when the line was followed, which is the ordinary case. */
        val byLine: Boolean get() = side.isNotEmpty() && note == null
    }

    /**
     * The side to buy, once the closing candle has had its say.
     *
     * The line is an average over half an hour; the candle is the last five
     * minutes. When they disagree the question is which one the next five
     * minutes will resemble, and there are three answers:
     *
     *  - a candle bigger than an ordinary one, going the other way, is the
     *    turn itself. Take the candle's side — the line catches up later;
     *  - a candle going the other way while the line was running into a price
     *    that stops things is the bounce off that price, and the bounce is the
     *    move actually available. Take the candle's side;
     *  - anything else against the line is noise inside a trend that has not
     *    ended, and there is no edge either way. Take neither.
     */
    fun choose(
        /** The minute chart's line. */
        way: String,
        /** And the five-minute chart's call, which objects only when it makes one. */
        wide: String,
        /** The five-minute candle closing with the window, and its scale. */
        candleBody: Double,
        typical: Double,
        /** Its reach, which is how a level is known to have been touched. */
        candleHigh: Double = 0.0,
        candleLow: Double = 0.0,
        candleClose: Double = 0.0,
        /** And the minute candle closing with it, against a minute's scale. */
        minuteBody: Double = 0.0,
        minuteTypical: Double = 0.0,
        /** The nearest price the market has stopped at, either side of here. */
        above: Double? = null,
        below: Double? = null,
        touch: Double = TOUCH,
    ): Choice {
        // The bounce comes first, and it comes before the lines.
        //
        // Price spends most of its time walking between the prices that stop
        // it rather than along a trend, and when it is thrown back from one of
        // them the direction has already changed while the fitted line — an
        // average over the last half hour — still points the old way, and will
        // go on pointing it for another twenty minutes. What actually happened
        // is on the screen: a wick into the level, a close back off it, and
        // the minute that ends the window already leaving.
        val near = typical * touch
        val reach = typical > 0.0 && candleHigh > 0.0 && candleLow > 0.0 && candleClose > 0.0
        val hitTop = reach && above != null && candleHigh >= above - near && candleClose < above
        val hitFloor = reach && below != null && candleLow <= below + near && candleClose > below

        // Both at once is a candle that spanned the whole shelf: it touched
        // everything and settled nothing.
        if (hitTop && hitFloor) return Choice("", "зажато между уровнями")

        // The wick says the level was reached and refused; the minute that
        // ends the window says whether price is actually leaving. Without that
        // second half it is a level being tested, not turned — the lines carry
        // the window instead, and buying into the tested level is refused
        // separately by `rejectedAt`.
        if (hitTop && minuteBody < 0.0) {
            return Choice("Down", "отбой от " + Math.round(above ?: 0.0))
        }
        if (hitFloor && minuteBody > 0.0) {
            return Choice("Up", "отбой от " + Math.round(below ?: 0.0))
        }

        // Nothing was touched, so the question is the ordinary one: is there a
        // direction, and is the closing candle going that way.
        if (way.isEmpty()) return Choice("", "нет линии")

        // A flat five minutes is not an opposite direction, it is silence, and
        // silence should not veto a minute chart that is perfectly clear.
        if (wide.isNotEmpty() && wide != way) return Choice("", "тренды спорят")

        // Either candle can disagree, and each is judged against its own kind:
        // a twelve-dollar minute is a big minute and a small five minutes.
        val against = listOf(candleBody to typical, minuteBody to minuteTypical)
            .filter { (body, _) -> body != 0.0 && (way == "Up") != (body > 0.0) }
        if (against.isEmpty()) return Choice(way, null)

        val loudest = against.maxByOrNull { (body, scale) ->
            if (scale > 0.0) abs(body) / scale else 0.0
        }!!
        return Choice("", if (loudest.first > 0.0) "свеча зелёная" else "свеча красная")
    }

    /**
     * Whether the closing candle was thrown back from the level the entry
     * would be buying into.
     *
     * The bounce above turns that into a trade the other way when the minute
     * confirms it. When it does not — the wick is there but the last minute is
     * still pushing — the level has at least been shown to hold, and buying
     * into it anyway is buying from the people who were just refused.
     */
    fun rejectedAt(
        way: String,
        high: Double,
        low: Double,
        close: Double,
        level: Double?,
        typical: Double,
        touch: Double = TOUCH,
    ): Boolean {
        if (way.isEmpty() || level == null || typical <= 0.0) return false
        if (high <= 0.0 || low <= 0.0 || close <= 0.0) return false
        val near = typical * touch
        return if (way == "Up") {
            high >= level - near && close < level
        } else {
            low <= level + near && close > level
        }
    }

    /**
     * Whether the candle about to close is closing the other way.
     *
     * The entry lands twenty seconds before a window opens, which is also
     * twenty seconds before the five-minute candle closes — so the shape of
     * that candle is already on the screen. A line that says "down" over a
     * candle that is finishing green is a line describing the half-hour and a
     * market doing something else right now, and the bet is five minutes long.
     *
     * A candle that closes exactly where it opened says nothing and is left
     * alone.
     */
    fun closingAgainst(way: String, open: Double, close: Double): Boolean {
        if (way.isEmpty() || open <= 0.0 || close <= 0.0) return false
        if (close == open) return false
        val green = close > open
        return if (way == "Up") !green else green
    }

    /**
     * The round number this price is sitting on, or null if it is in open
     * ground between two of them.
     */
    fun nearRound(
        price: Double,
        band: Double,
        step: Double = ROUND_STEP,
    ): Double? {
        if (band <= 0.0 || step <= 0.0 || price <= 0.0) return null
        val nearest = Math.round(price / step) * step
        return if (abs(price - nearest) <= band) nearest else null
    }

    /**
     * How much of a win rides on the next window.
     *
     * A quarter, and it compounds: each win adds a quarter of itself to what
     * the next entry stakes, and the next win adds a quarter of its own on top
     * of that. A losing window ends the run and the stake goes back to base.
     * The stake grows out of money the rule has already made, so a run that
     * gives it all back gives back winnings, never the base.
     */
    const val STREAK_SHARE = 0.25

    /** And what a doubled account is worth to the base stake. */
    const val DOUBLE_STEP = 1.5

    /**
     * What the run has added to the stake, after this window's result.
     *
     * Only a booked result counts. A window still running has made nothing
     * yet, and staking on a profit that has not been taken is staking twice on
     * the same guess.
     */
    fun nextStreak(streak: Double, pnl: Double, share: Double = STREAK_SHARE): Double =
        if (pnl > 1e-9) maxOf(0.0, streak) + pnl * share else 0.0

    /**
     * How many times the account has doubled from where it started.
     *
     * A hundred becoming two hundred is one; two hundred becoming four is the
     * second, and it takes three hundred of winnings to get there — which is
     * why this counts the winnings against the start rather than dividing one
     * balance by another.
     */
    fun doublings(won: Double, start: Double): Int {
        if (start <= 0.0 || won <= 0.0) return 0
        return Math.floor(Math.log(1.0 + won / start) / Math.log(2.0)).toInt()
    }

    /** What the next window is worth staking, all of the above together. */
    fun stakeFor(stake: Double, won: Double, start: Double, streak: Double): Double {
        if (stake <= 0.0) return 0.0
        val base = stake * Math.pow(DOUBLE_STEP, doublings(won, start).toDouble())
        return base + maxOf(0.0, streak)
    }

    /**
     * The price at which a side worth buying is worth buying twice.
     *
     * Thirty-four cents is the market saying the side has two chances in six,
     * and the window is only five minutes long — but the entry was taken on a
     * read that has not been withdrawn, and the same read at half the price is
     * the same bet at better odds. Averaging down is only sane while there is
     * still time for the move to happen, which is why it stops after two
     * minutes: past that the price is not cheap, it is late.
     */
    const val ADD_PRICE = 0.34

    /** And the last second of the window at which it is still early. */
    const val ADD_UNTIL_SEC = 120L

    /**
     * Whether to put the same money into the same side a second time.
     *
     * Once per window. A rule that keeps doubling into a falling side is a
     * rule that loses the whole account on the day the read is simply wrong.
     */
    fun addsUp(
        elapsedSec: Long,
        ask: Double?,
        alreadyAdded: Boolean,
        price: Double = ADD_PRICE,
        untilSec: Long = ADD_UNTIL_SEC,
    ): Boolean {
        if (alreadyAdded) return false
        if (elapsedSec < 0 || elapsedSec > untilSec) return false
        if (ask == null || ask <= 0.0) return false
        return ask < price
    }

    /** Whether this quote is taken now or waited for. */
    fun waits(ask: Double): Boolean = ask > MAX_TAKE + 1e-9

    /** What the entry will actually pay: the offer, or the resting bid. */
    fun entryPrice(ask: Double): Double = if (waits(ask)) REST_PRICE else ask

    /**
     * How much room to the level ahead a window needs, as a share of what a
     * window usually travels.
     *
     * A trend that is about to arrive at a price the market has already turned
     * at twice is a trend with one candle left in it. The bet is five minutes
     * long, so the question is not "is there a level somewhere above" but "can
     * this window reach it" — and that is what a typical window's travel
     * measures. At six tenths, the line has to have more room in front of it
     * than a normal five minutes covers before its direction is worth paying
     * for.
     *
     * Zero switches the check off.
     *
     * A whole window's travel. Buying a direction with less room than that in
     * front of it is buying the last stretch before the wall: what is left is
     * smaller than the move a five-minute candle makes by accident, and the
     * wall is where everybody else's orders are.
     */
    const val DEFAULT_ROOM = 1.0

    data class Settings(
        val enabled: Boolean = false,
        val stakeUsd: Double = DEFAULT_STAKE,
        val leadSec: Long = DEFAULT_LEAD_SEC,
        /** Room to the level ahead, against a typical window's travel. */
        val roomShare: Double = DEFAULT_ROOM,
        /** How close to a round five hundred is too close, in dollars. */
        val roundBand: Double = DEFAULT_ROUND_BAND,
        /**
         * Paper money. On by default, because the point of this rule is to
         * find out whether the line pays before any real money is asked to
         * find out.
         *
         * Nothing is sent to the venue: it reads the same live book, takes
         * the same offers at the same prices, pays the same fee, and leaves
         * by the same ladder — only the money is imaginary.
         */
        val demo: Boolean = true,
        /** What that imaginary money starts at. */
        val bankUsd: Double = DEFAULT_BANK,
    )

    /**
     * Whether the reversal is close enough to be this window's problem.
     *
     * Distances are meaningless bare: forty dollars from resistance is nothing
     * in a market moving two hundred an hour and everything in one moving
     * thirty. So the room in front of the trend is measured in windows, not in
     * dollars.
     */
    fun tooClose(
        price: Double,
        level: Double?,
        typical: Double,
        share: Double = DEFAULT_ROOM,
    ): Boolean {
        if (level == null || share <= 0.0) return false
        if (price <= 0.0 || typical <= 0.0) return false
        return abs(level - price) < typical * share
    }

    /**
     * The window this moment is trying to buy, or null when it is neither.
     *
     * Two moments, not one. The lead before a window opens is where the entry
     * belongs — late enough to have seen the last window, early enough to be
     * in from the first tick. But the venue does not always publish the next
     * market in time, and a chance missed for that reason used to cost the
     * whole window; so the same lead again just after the open buys the window
     * that is already running. Two seconds late is still that window's bet.
     */
    fun targetWindow(
        windowStart: Long,
        elapsedSec: Long,
        settings: Settings,
        windowSec: Long = 300L,
    ): Long? {
        val left = windowSec - elapsedSec
        return when {
            left in 1..settings.leadSec -> windowStart + windowSec
            elapsedSec in 0..settings.leadSec -> windowStart
            else -> null
        }
    }

    /**
     * Why the entry is not going in, or null when it is.
     *
     * The order is the order a person would check them in, so what shows up on
     * the card is the first thing actually wrong.
     */
    fun blockedBecause(
        way: String,
        ask: Double?,
        cashUsd: Double,
        settings: Settings,
        /** Where BTC is, and the level the line is heading into. */
        price: Double = 0.0,
        level: Double? = null,
        typical: Double = 0.0,
        /**
         * Whether this side came from the line.
         *
         * A side taken *against* the line is the correction off a level or the
         * turn itself — so the structure that would have stopped the line is
         * the reason for the trade and cannot also be the reason against it.
         */
        byLine: Boolean = true,
        /** What this window is actually staking, when it is not the base. */
        stake: Double? = null,
        /** The closing candle's reach, for a level it may have been refused at. */
        candleHigh: Double = 0.0,
        candleLow: Double = 0.0,
        candleClose: Double = 0.0,
    ): String? {
        if (!settings.enabled) return "выключен"
        // The line is read off the minute candles, so an empty answer means
        // the stream has not arrived rather than that the market is quiet.
        if (way.isEmpty()) return "нет свечей"
        if (rejectedAt(way, candleHigh, candleLow, candleClose, level, typical)) {
            return "отбой от " + Math.round(level ?: 0.0)
        }
        if (byLine) {
            nearRound(price, settings.roundBand)?.let {
                return "круглый " + Math.round(it)
            }
            if (tooClose(price, level, typical, settings.roomShare)) {
                return "у разворота " + Math.round(level ?: 0.0)
            }
        }
        if (ask == null || ask <= 0.0) return "нет цены"
        if (cashUsd < (stake ?: settings.stakeUsd)) {
            return if (settings.demo) "тестовый счёт пуст" else "на счету пусто"
        }
        return null
    }

    /** Five dollars' worth at that price, never under the venue's floor. */
    fun shares(stakeUsd: Double, ask: Double, minimumOrderSize: Double): Double {
        if (ask <= 0.0) return 0.0
        val floor = Orders.minShares(ask, minimumOrderSize)
        val wanted = stakeUsd / ask
        return maxOf(floor, Math.round(wanted * 10.0) / 10.0)
    }

    /** The venue's taker fee, which paper money pays too. */
    const val FEE_RATE = 0.07

    /**
     * What one share actually costs when an offer is taken.
     *
     * The quote is what the seller asks; the fee is what the venue takes on
     * top, and on a buy it comes out in shares. Paper money pays it because a
     * demo that ignored the fee would report a profit the same trade would not
     * have made — which is the one thing a demo must not do.
     */
    fun takenPrice(ask: Double): Double {
        if (ask <= 0.0 || ask >= 1.0) return ask
        return ask + FEE_RATE * ask * (1 - ask)
    }

    /**
     * What the desk's sell rule is asking for a position right now.
     *
     * The demo places no orders, so nothing on the desk can see its position
     * and arrange an exit for it — but "exits by the ladder" has to mean the
     * ladder that is actually running, or the report is about a rule nobody
     * uses. So the choice is made here, out of the same two pieces the real
     * rule is made of: the rung for the clock and the high-water mark, or the
     * margin over what the lot cost, with the late floors that stop a winning
     * side riding into the close for nothing.
     *
     * Which of the two is the desk's own setting, not this rule's.
     */
    fun exitPrice(
        cost: Double,
        elapsedSec: Long,
        secondsLeft: Long,
        highWater: Double,
        rung: Int,
        bestBid: Double?,
        exit: AutoSell.Settings,
        tick: Double = 0.01,
    ): Double {
        if (exit.percentMode) {
            return SellPercent.priceFor(
                avgPrice = cost,
                gain = exit.profitPct,
                tick = tick,
                // One lot per window, so there is never a slice to step over.
                resting = null,
                secondsLeft = secondsLeft,
                panicSec = exit.panicSec,
                bestBid = bestBid,
                closeFloor = exit.closeFloor,
                lateFloor = exit.lateFloor,
                lateBandSec = exit.lateBandSec,
            )
        }
        val rungs = exit.ladder.ifEmpty { SellLadder.DEFAULT }
        val step = SellLadder.stepFor(
            elapsedSec = elapsedSec.coerceAtLeast(0L),
            highWater = highWater.takeIf { it > 0.0 },
            ladder = rungs,
            floor = rung,
            leadSec = exit.ladderLeadSec,
            stepSec = exit.ladderStepSec,
        )
        return rungs[step.coerceIn(0, rungs.size - 1)]
    }

    /**
     * The rung this position has reached, which it may not slip back down.
     */
    fun exitStep(
        elapsedSec: Long,
        highWater: Double,
        rung: Int,
        exit: AutoSell.Settings,
    ): Int = SellLadder.stepFor(
        elapsedSec = elapsedSec.coerceAtLeast(0L),
        highWater = highWater.takeIf { it > 0.0 },
        ladder = exit.ladder.ifEmpty { SellLadder.DEFAULT },
        floor = rung,
        leadSec = exit.ladderLeadSec,
        stepSec = exit.ladderStepSec,
    )

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }
}
