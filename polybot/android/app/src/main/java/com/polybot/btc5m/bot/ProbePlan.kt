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

    /**
     * How long before the window opens the entry goes in.
     *
     * Fifty seconds. Early enough to be filled before the book starts pricing
     * the open, which is where the cheap side is; the risk of being that
     * early — that the picture changes in the meantime — is what
     * [RECHECK_SEC] answers, and it now answers it for a position as well as
     * for a bid.
     */
    const val DEFAULT_LEAD_SEC = 50L

    /**
     * And the lead the candle entry wants, which is shorter.
     *
     * Fifteen seconds. That entry reads the five-minute candle that closes as
     * the window opens, so the later it looks the more of that candle it has
     * actually seen — the opposite trade-off from the line, which is an
     * average over a quarter of an hour and settled long before.
     */
    const val FADE_LEAD_SEC = 15L

    /**
     * Leads that were once the default, and so are not a choice.
     *
     * A setting saved while one of these was the default carries it forever,
     * because saving any one setting saves all of them — so a lead sitting on
     * an old default is the old default rather than something the user typed,
     * and it moves when the default does.
     */
    val OLD_LEADS = setOf(10L, 20L, 45L)

    /**
     * How late after an open the entry may still be taken.
     *
     * The lead has a second use: when the venue publishes the next market too
     * late to be bought before it opens, the same window is bought just after
     * instead. That grace is its own number rather than the lead, because a
     * lead long enough to be worth taking is far too long to be late by — a
     * bet placed forty-five seconds into a five-minute window is missing a
     * sixth of what it is betting on.
     */
    const val LATE_SEC = 20L

    /**
     * How long before the open the read is taken again.
     *
     * Ten seconds. Between the entry and here the closing candle finishes, so
     * the picture the entry was taken on is the one thing that can still
     * change — and a position held on a read that no longer holds is a
     * position held for no reason. What is left is sold at the market: this
     * is a decision to be out, not a price to hope for.
     */
    const val RECHECK_SEC = 10L

    /** Whether the read is due to be taken again for a window not yet open. */
    fun rechecks(secondsToOpen: Long, within: Long = RECHECK_SEC): Boolean =
        secondsToOpen in 0..within

    /**
     * Whether the fresh read still supports a side already bought.
     *
     * Only the same side does. A read that has turned round is plainly a
     * reason to be out; a read that has gone quiet is one too, because the
     * entry was taken on a picture and that picture is no longer there.
     */
    fun stillOn(held: String, fresh: String): Boolean =
        held.isNotEmpty() && held == fresh

    /**
     * The dearest offer worth taking at the market.
     *
     * Above it the entry is not abandoned — the line still points somewhere —
     * but it stops paying whatever is asked and leaves a bid instead. A side
     * that opens dear is a side someone else has already paid for; if it is
     * coming back to us it will come back to [REST_PRICE], and if it is not,
     * the window was not ours.
     */
    const val MAX_TAKE = 0.58

    /**
     * Where the bid waits when the offer is dearer than that.
     *
     * The take price itself. Everything at or below [MAX_TAKE] is bought at
     * the market — crossed, not offered — because a window is five minutes
     * long and a bid that waits for a price already on the screen spends them
     * waiting. The limit is the exception, for the one case where there is
     * nothing worth taking: the side is asking more than the rule will pay,
     * so it leaves a bid at the most it would have paid and lets the lead run.
     *
     * At the take price and not under it. A bid two cents lower refused the
     * same side twice — the rule would have bought at the take price, so a
     * side that comes back to it is one it wanted, and asking it to come two
     * cents further only misses the ones that stop in between.
     */
    const val REST_PRICE = MAX_TAKE

    /**
     * How long that bid is left out before it is pulled.
     *
     * One minute. The entry was for a window that is five minutes long, and a
     * side that has not come back to the price in the first fifth of it is not
     * coming back to it as the same trade — what would fill later is a
     * different bet at the same number. Meanwhile the money is committed and
     * the next window cannot use it.
     */
    const val REST_UNTIL_SEC = 60L

    /** Whether a bid that has not been reached should be taken back. */
    fun restingDone(elapsedSec: Long, untilSec: Long = REST_UNTIL_SEC): Boolean =
        elapsedSec >= untilSec

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

    /**
     * How close to one of them the card calls "on it", in dollars.
     *
     * It was a gate: a window opening within this of a round five hundred was
     * refused. Nothing about the entry reads it now — the side is the line's
     * and the only thing that stops it is a wick — so what is left is the
     * tile on the card, which turns red when price is sitting on one. Zero
     * switches the highlight off.
     */
    const val DEFAULT_ROUND_BAND = 50.0

    /**
     * How close a wick has to come to a level to have touched it.
     *
     * Against what a five-minute candle usually travels, so it is a fraction
     * of the day's own movement rather than a number of dollars that means
     * something different every week.
     */
    const val TOUCH = 0.35
    /**
     * A price the market stops at, and how much weight it carries.
     *
     * The round five hundreds always carry it — the book is stacked there
     * whatever the chart has done — and a pivot earns it by having turned the
     * market more than twice.
     */
    data class Wall(
        val price: Double,
        val touches: Int,
        val round: Boolean,
        /**
         * The high or the low of the whole visible history.
         *
         * It needs no pivot to confirm it: it is by construction the price
         * that stopped the market hardest in everything on the screen. And
         * the pivot rule could not confirm the retest anyway — a pivot is the
         * extreme of two candles either side of it, so the candle price is
         * testing a level on can never be one.
         */
        val edge: Boolean = false,
        /**
         * The band the turns that made this wall actually cover.
         *
         * A level is a zone, not a line: the orders sit across the prices the
         * market turned at, and the wall starts at the first of them. Both
         * default to [price], so a round number or a range edge — neither of
         * which has a band — behaves exactly as a line.
         */
        val low: Double = price,
        val high: Double = price,
    ) {
        /** Enough to trade against the candle that is closing. */
        val important: Boolean get() = round || edge || touches >= 3

        /**
         * Where the zone starts for a trade going [way]: the edge it reaches
         * first, which is the bottom of the band for a rally and the top of
         * it for a fall.
         *
         * This is the price the room in front of an entry is measured to.
         * Measuring to the middle of the band let a trade begin inside the
         * zone and count the half of it still ahead as clear air.
         */
        fun facing(way: String): Double = if (way == "Up") low else high
    }

    /** Which side the rule ends up on, and why it is not simply the line. */
    data class Choice(val side: String, val note: String?) {
        /** True when the line was followed, which is the ordinary case. */
        val byLine: Boolean get() = side.isNotEmpty() && note == null
    }

    /**
     * How lopsided the recent record has to be before one side of a level
     * counts as where the market lives.
     */
    const val HOME_SHARE = 0.7

    /**
     * The side of a level the market has been living on, or "" when it is even.
     *
     * A level with almost the whole recent hour on one side of it is not a
     * wall to be thrown off in either direction: it is the edge of where price
     * lives. Being on the other side of it is the exception, and what follows
     * an exception is the return — so a bounce off such a level points back
     * into the range, never further out of it.
     */
    fun homeSide(closes: List<Double>, level: Double, share: Double = HOME_SHARE): String {
        if (level <= 0.0 || share <= 0.5) return ""
        val used = closes.filter { it > 0.0 }
        if (used.size < 12) return ""
        val above = used.count { it > level }.toDouble() / used.size
        return when {
            above >= share -> "Up"
            1.0 - above >= share -> "Down"
            else -> ""
        }
    }

    /**
     * The side to buy, once the closing candle has had its say.
     *
     * The line is an average over a quarter of an hour; the candle is the last five
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
        /** The minute chart's line, as the fit itself calls it. */
        way: String,
        /** And the five-minute chart's call, which objects only when it makes one. */
        wide: String,
    ): Choice {
        // The line, and that is the whole of it.
        //
        // The levels used to choose sides here — a wick into one, a close
        // back off it and a minute already leaving was a bounce, and the side
        // was taken away from the level whatever the line said. It made the
        // rule an argument between two readings of the same chart, and the
        // levels lost more entries than they saved. What is left of them is
        // the round five hundreds, which are a fact about where the book sits
        // rather than a reading of anything, and they only ever refuse.
        if (way.isEmpty()) return Choice("", "нет линии")

        // A flat five minutes is not an opposite direction, it is silence, and
        // silence should not veto a minute chart that is perfectly clear.
        if (wide.isNotEmpty() && wide != way) return Choice("", "тренды спорят")

        return Choice(way, null)
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
     * How much of the closing candle has to be wick on the trade's own side
     * before the move it tried to make counts as refused.
     */
    const val WICK_SHARE = 0.6

    /** And how little of the usual travel its body may hold to be spent. */
    const val SPENT_BODY = 0.25

    /**
     * Whether the move our way reached for something and was pushed back.
     *
     * A candle that went a little our way, spent most of its height on a wick
     * on that same side, and closed near where it opened, is a move that
     * tried and did not get there. The next five minutes is being asked to do
     * what the last five just failed at, from the same place.
     *
     * It wants both halves. A big wick over a big body is a move that took
     * ground and gave some back, which is what a trending candle looks like;
     * a small body with no wick is a market standing still, and standing
     * still is not a refusal. Measured over 794 windows of real tape, this
     * shape stops 9% of entries and those win 43% against 49% for the ones it
     * lets through — while requiring the candle to have gone backwards our
     * way instead inverts it, to 54%.
     */
    fun refused(
        way: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        typical: Double,
        wickShare: Double = WICK_SHARE,
        bodyShare: Double = SPENT_BODY,
    ): Boolean {
        if (way.isEmpty() || typical <= 0.0) return false
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return false
        val range = high - low
        if (range <= 0.0) return false

        // How far it actually finished our way, which is what "faded" means.
        val body = close - open
        val progress = if (way == "Up") body else -body
        if (progress > typical * bodyShare) return false

        // And how much of it was reaching and being pushed back.
        val wick = if (way == "Up") {
            high - maxOf(open, close)
        } else {
            minOf(open, close) - low
        }
        return wick >= range * wickShare
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
     * Half, and it compounds: each win adds half of itself to what the next
     * entry stakes, and the next win adds half of its own on top of that. A
     * losing window ends the run and the stake goes back to base. The stake
     * grows out of money the rule has already made, so a run that gives it all
     * back gives back winnings, never the base.
     */
    const val STREAK_SHARE = 0.5

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
     * What the run is worth staking while a window is still open.
     *
     * The next entry goes in twenty seconds before this window closes, so its
     * result is not booked yet — but by then it is usually plain to see. What
     * the book would pay for the position right now decides it: if closing
     * here is a loss, the run is already over and the next window goes in at
     * the base stake rather than at the top of a sequence that has ended.
     */
    fun riding(streak: Double, worth: Double, cost: Double): Double =
        if (worth < cost - 1e-9) 0.0 else streak

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

    /**
     * The most of the account one window may stake, however long the run.
     *
     * A quarter. A run that compounds without a ceiling ends with the whole
     * account riding on one five-minute window, and the whole point of a run
     * is that it stakes winnings — a bet that can take the account out in a
     * single window is not staking winnings, it is staking the account.
     */
    const val MAX_SHARE = 0.25

    /**
     * The run trimmed to that ceiling.
     *
     * The ceiling never cuts into the base stake the user set: an account too
     * small for a quarter of it to cover the base is an account with no room
     * for a run, and the answer there is the base alone, not less than it.
     */
    fun capped(
        want: Double,
        base: Double,
        bank: Double,
        share: Double = MAX_SHARE,
    ): Double = minOf(want, windowCap(base, bank, share))

    /**
     * The most one window may put in, every buy of it together.
     *
     * The ceiling is on the window, not on its first buy. Two top-ups of a
     * quarter each on top of a first quarter is three quarters of the account
     * riding on five minutes, which is the thing the ceiling exists to stop.
     */
    fun windowCap(
        base: Double,
        bank: Double,
        share: Double = MAX_SHARE,
    ): Double {
        if (bank <= 0.0 || share <= 0.0) return Double.MAX_VALUE
        return maxOf(base, bank * share)
    }

    /**
     * What the next window is worth staking, all of the above together.
     *
     * [bank] is what the account is worth right now, and caps the run; zero
     * when it is not known, which leaves the run uncapped until it is.
     */
    fun stakeFor(
        stake: Double,
        won: Double,
        start: Double,
        streak: Double,
        bank: Double = 0.0,
    ): Double {
        if (stake <= 0.0) return 0.0
        val base = stake * Math.pow(DOUBLE_STEP, doublings(won, start).toDouble())
        return capped(base + maxOf(0.0, streak), base, bank)
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
    val ADD_PRICES = listOf(0.42, 0.33)

    /**
     * And the last second of the window at which it is still early.
     *
     * The first minute. A side that is cheap because the window has already
     * spent four fifths of itself going the other way is not cheap, it is
     * finished, and there is no time left for the read to come good.
     */
    const val ADD_UNTIL_SEC = 60L

    /**
     * Whether to put the same money into the same side again.
     *
     * Twice at most, at forty-two cents and then at thirty-three, so a window
     * holds three buys and no more. A rule that keeps doubling into a falling
     * side is a rule that loses the whole account on the day the read is
     * simply wrong, and each rung has to be reached in its own turn.
     */
    fun addsUp(
        elapsedSec: Long,
        ask: Double?,
        adds: Int,
        prices: List<Double> = ADD_PRICES,
        untilSec: Long = ADD_UNTIL_SEC,
    ): Boolean {
        if (adds < 0 || adds >= prices.size) return false
        if (elapsedSec < 0 || elapsedSec > untilSec) return false
        if (ask == null || ask <= 0.0) return false
        return ask < prices[adds]
    }

    /**
     * How far a side has to fall under its own sale before it is bought back.
     *
     * The ladder sold because the price came to it, which is the move having
     * happened. A fifth off that price afterwards is the market handing the
     * same side back cheaper than it was let go of — the read has not changed,
     * only the quote.
     */
    const val BACK_DROP = 0.20

    /**
     * And the price under which it is not handed back but taken away.
     *
     * Below forty-four cents the side is no longer the favourite, and buying
     * back into a side the book has stopped believing in is not the same trade
     * at a better price, it is a different trade at a worse one.
     */
    const val BACK_FLOOR = 0.44

    /**
     * How long a sale stays worth buying back.
     *
     * Longer than a top-up, because the ladder sold into a move that already
     * happened: the window is halfway through by the time a rung fills, and
     * the price coming back afterwards is the whole point.
     */
    const val BACK_UNTIL_SEC = 240L

    /** Whether the side just sold is worth buying back at this ask. */
    fun buysBack(
        elapsedSec: Long,
        ask: Double?,
        soldAt: Double,
        alreadyBack: Boolean,
        untilSec: Long = BACK_UNTIL_SEC,
        drop: Double = BACK_DROP,
        floor: Double = BACK_FLOOR,
    ): Boolean {
        if (alreadyBack) return false
        if (soldAt <= 0.0) return false
        if (elapsedSec < 0 || elapsedSec > untilSec) return false
        if (ask == null || ask <= 0.0) return false
        if (ask < floor) return false
        return ask <= soldAt * (1.0 - drop) + 1e-9
    }

    /**
     * How many times the usual minute a candle has to be to stop a top-up.
     *
     * Buying a dip assumes the dip is noise. A minute several times the size
     * of the minutes around it is not noise: it is the thing that moved the
     * price, and it is still moving it. Averaging into that is paying twice
     * for the same wrong read.
     */
    const val SHOCK = 2.0

    /**
     * How many times the usual minute the last one has to be before the move
     * it made counts as already spent.
     *
     * Higher than [SHOCK], which guards a top-up. A top-up refused costs
     * nothing but a smaller position; an entry refused costs the whole window,
     * so the candle has to be plainly anomalous — the kind a person looking at
     * the chart would point at — rather than merely large.
     */
    const val SPENT = 2.5

    /**
     * Whether the minute that just closed is too big to be followed.
     *
     * Measured on the whole candle, wicks included, because that is what
     * "a big candle" means to the eye; the direction comes from the body. A
     * minute two and a half times the size of the minutes around it has made
     * its move, and the side it made it in is the dear side by the time the
     * window opens — so buying with it is paying up for something that has
     * already happened.
     */
    fun spent(
        way: String,
        minuteRange: Double,
        minuteBody: Double,
        minuteTypical: Double,
        limit: Double = SPENT,
    ): Boolean {
        if (way.isEmpty() || minuteTypical <= 0.0 || limit <= 0.0) return false
        if (minuteRange <= minuteTypical * limit) return false
        // A candle that closed where it opened points nowhere, whatever it
        // did in between.
        if (minuteBody == 0.0) return false
        return (way == "Up") == (minuteBody > 0.0)
    }

    /**
     * Whether the move that made this price is too big to buy into.
     *
     * [against] is how far the running minute has travelled the wrong way for
     * the side held; [typical] is what a minute usually covers.
     */
    fun shocked(against: Double, typical: Double, limit: Double = SHOCK): Boolean {
        if (typical <= 0.0 || limit <= 0.0) return false
        return against > typical * limit
    }

    /** How far the running minute has gone against a side, in dollars. */
    fun againstBy(side: String, open: Double, close: Double): Double {
        if (open <= 0.0 || close <= 0.0) return 0.0
        val move = close - open
        return if (side == "Up") maxOf(0.0, -move) else maxOf(0.0, move)
    }

    /**
     * The least a side has to be underpriced by before it is worth buying.
     *
     * Five cents a share. The chance is a model and the model is not exact,
     * so a side worth a cent more than it costs is not an opportunity, it is
     * rounding — and the fee is already inside the cost being compared.
     */
    const val DEFAULT_EDGE = 0.05

    /**
     * The dearest side worth buying however cheap it looks.
     *
     * Above ninety-five cents a share risks a dollar to make five cents, and
     * one window going the other way undoes twenty that did not. The model is
     * least trustworthy exactly there, in the tail where it has the fewest
     * observations to have been fitted on.
     */
    const val EDGE_CEILING = 0.95

    /** And the earliest second of a window at which the reading means much. */
    const val EDGE_FROM_SEC = 30L

    /**
     * What a share of this side is worth right now, less what it costs.
     *
     * [fair] is the chance the side wins, so a share of it is worth that many
     * cents; [ask] is what the book wants, and what it really costs is that
     * plus the taker fee. The difference is the edge, in cents a share, and
     * it is the whole of the reason to buy.
     */
    fun edgeOn(fair: Double, ask: Double?): Double {
        if (ask == null || ask <= 0.0 || ask >= 1.0) return 0.0
        if (fair <= 0.0 || fair > 1.0) return 0.0
        return fair - takenPrice(ask)
    }

    /** Whether that edge is worth acting on, at this price and this moment. */
    fun worthTaking(
        fair: Double,
        ask: Double?,
        elapsedSec: Long,
        leftSec: Long,
        least: Double = DEFAULT_EDGE,
        ceiling: Double = EDGE_CEILING,
        from: Long = EDGE_FROM_SEC,
    ): Boolean {
        if (ask == null || ask <= 0.0 || ask > ceiling) return false
        if (elapsedSec < from || leftSec <= 0L) return false
        return edgeOn(fair, ask) >= least
    }

    /**
     * How much of the closing candle's range may be a wick on our side.
     *
     * A third. Every candle has a hair at each end and calling that a wick
     * would refuse every window; a third of the range spent reaching one way
     * and coming back is the thing a person means by "there's a wick on it".
     */
    const val WICK_AT = 1.0 / 3.0

    /**
     * Whether the five minutes closing with the window left a wick our way.
     *
     * A wick our way is the move having gone there and been sent straight
     * back inside the same candle — the last five minutes asked the question
     * this window is about to ask and were answered no. A candle with none
     * closed where it reached, and there is nothing overhead that has already
     * turned it back.
     *
     * Only our side is looked at. A long wick the other way is the *other*
     * direction having been refused, which is a reason for this trade rather
     * than against it.
     */
    fun wicked(
        way: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        share: Double = WICK_AT,
    ): Boolean {
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return false
        val range = high - low
        if (range <= 0.0) return false
        val wick = when (way) {
            "Up" -> high - maxOf(open, close)
            "Down" -> minOf(open, close) - low
            else -> return false
        }
        return wick >= range * share
    }

    /**
     * How long before the close a lost window is given up on.
     *
     * Ten seconds. Polymarket settles a window against a sixty-second average
     * read at its close, so with ten seconds left five sixths of that average
     * is already history and the answer is all but written. What is left is
     * not a chance, it is the last of the money.
     */
    const val CUT_SEC = 10L

    /**
     * Whether the window is closing against this side.
     *
     * Measured the way the venue measures it — the settlement series against
     * the price the window opened on — and not against the chart, because it
     * is the venue that decides who is paid.
     */
    fun losingAt(side: String, opened: Double, here: Double): Boolean {
        if (opened <= 0.0 || here <= 0.0) return false
        return when (side) {
            "Up" -> here < opened
            "Down" -> here > opened
            else -> false
        }
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
        /**
         * And whether real money is trading, which is a separate question.
         *
         * The two used to be one switch, so watching the rule on paper meant
         * not running it — and running it meant losing the record that says
         * whether it is worth running. They are independent now: either, both
         * or neither. Both is the useful one, because then the paper account
         * and the wallet see the same windows and the same prices, and the
         * difference between the two histories is only what the venue did
         * with the orders.
         */
        val live: Boolean = false,
        /** What that imaginary money starts at. */
        val bankUsd: Double = DEFAULT_BANK,
        /**
         * Buy from inside the window on price rather than before it on a read.
         *
         * The old entry guesses which way five minutes will go and is right
         * 49% of the time over a month, which is no edge at all. This one
         * waits until the window has half answered itself, works out what a
         * side is worth, and buys only where the book is asking less.
         */
        val inside: Boolean = false,
        /**
         * Buy against a five-minute candle that has just run out of breath.
         *
         * A third entry, and the only one here whose numbers come from a
         * search that was checked on data it had not seen: a new twenty-candle
         * extreme closing in the far quarter of its own range, bought the
         * other way. See [FadePlan] for what it scores and what that is worth.
         */
        val fade: Boolean = false,
        /** How underpriced a side has to be, in cents a share. */
        val edgeUsd: Double = DEFAULT_EDGE,
    )

    /**
     * How much more room the edge of the range wants than an ordinary wall.
     *
     * Half again. It is the strongest line on the chart — everything the
     * screen holds turned back from it — so a window is not worth starting
     * within reach of it. Measured over 849 windows of real tape: entries
     * this stops win 42% against 48% for the ones it lets through, and it
     * stops a quarter of them; at one typical move it is 44% against 47%,
     * and at half a move it separates nothing at all.
     */
    const val EDGE_ROOM = 1.5

    /**
     * The least room in front of a trade, whatever the setting says.
     *
     * A whole typical five-minute move of clear air, measured to where the
     * zone ahead begins rather than to its middle. It was half a move, and
     * half was not enough: the 10:30 entry bought Up into a shelf the market
     * had been failing at for two hours, with the room to the middle of that
     * shelf passing the check and the near edge of it much closer.
     *
     * The setting is a preference and may ask for more; it may not ask for
     * less, and there is deliberately no way to switch it off. Every level
     * this rule has ever been wrong about was one it could see and had been
     * told to ignore.
     */
    const val LEAST_ROOM = 1.0

    /**
     * How much room this entry actually has to have, as a share of a typical
     * move: the setting or the floor, whichever asks for more, and half again
     * on top when the level ahead is the edge of the range.
     */
    fun roomNeeded(
        share: Double,
        levelEdge: Boolean,
        least: Double = LEAST_ROOM,
    ): Double = maxOf(share, least) * (if (levelEdge) EDGE_ROOM else 1.0)

    /**
     * Whether the level ahead is close enough to be this window's problem.
     *
     * Distances are meaningless bare: forty dollars from resistance is nothing
     * in a market moving two hundred an hour and everything in one moving
     * thirty. So the room in front of the trade is measured in windows, not in
     * dollars.
     *
     * This applies to every entry. A level outranks a line by a long way — the
     * trend can point wherever it likes, but a bet with a wall a few dollars
     * in front of it has nowhere to go in five minutes, and standing on the
     * wall is the worst place of all from which to buy through it.
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
            elapsedSec in 0..minOf(settings.leadSec, LATE_SEC) -> windowStart
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
        /** Whether that level is the high or low of the whole visible range. */
        levelEdge: Boolean = false,
        byLine: Boolean = true,
        /** What this window is actually staking, when it is not the base. */
        stake: Double? = null,
        /** The closing candle's reach, for a level it may have been refused at. */
        candleOpen: Double = 0.0,
        candleHigh: Double = 0.0,
        candleLow: Double = 0.0,
        candleClose: Double = 0.0,
        /** And the minute that just closed, against a minute's usual size. */
        minuteRange: Double = 0.0,
        minuteBody: Double = 0.0,
        minuteTypical: Double = 0.0,
    ): String? {
        if (!settings.enabled) return "выключен"
        // The line is read off the minute candles, so an empty answer means
        // the stream has not arrived rather than that the market is quiet.
        if (way.isEmpty()) return "нет свечей"

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
            return SellLadder.capped(
                SellPercent.priceFor(
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
                ),
                cost,
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
        // The rung, and nothing else. Every rule that used to move it — the
        // doubling, the level ahead, the minute that turned against us — sold
        // at its own price and on its own reasoning, and between them the
        // ladder was reached in a minority of windows. The ladder is the exit.
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

    /**
     * A price rounded down onto the tick grid.
     *
     * Downward for a sale: rounding up would ask a cent more than the book is
     * paying, and an offer a cent over the bid is an offer that waits.
     */
    fun snapDown(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        return Math.floor(price / tick + 1e-9) * tick
    }

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }
}
