package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * The rule the pulse bot trades by.
 *
 * A five-minute window settles on one question: is the price above where the
 * window opened. Everything else on the desk is a way of guessing whether the
 * answer it has right now will still be the answer at the end — so this asks
 * four independent sources whether the same side is winning, and only trades
 * when all four agree and the market has not already charged for the agreement.
 *
 *  - **The lead.** How far price has moved from the window's own open, in
 *    dollars, on the series the market settles against. Under a few dollars
 *    the window is a coin flip wearing a direction.
 *  - **Momentum.** Where the last few one-minute closes have gone. A lead that
 *    is being handed back is not a lead.
 *  - **Volume.** Whether the last completed minute traded more than the recent
 *    average. A move on thin volume is a move nobody is behind, and those are
 *    the ones that come back.
 *  - **The book.** Which way the resting size leans within a hair of the mid,
 *    which is what the next few dollars have to get through.
 *
 * And one thing that is not a signal but a price: the odds have to be in a
 * band. Above the ceiling the arithmetic stops working — most of a dollar
 * risked for a few cents — and far below it the market is saying the opposite
 * of the four signals, which is a disagreement to sit out rather than to take
 * the cheap end of.
 *
 * Exits are their own arithmetic: a fixed profit taken at rest, a cut when the
 * lead flips the other way, and — inside the last stretch, with the side still
 * ahead — no sale at all, because settlement pays a dollar and charges no fee.
 */
object PulsePlan {

    const val DEFAULT_BANK_USD = 100.0

    /** Every window is five minutes long. */
    const val WINDOW_SEC = 300L

    /**
     * What one entry puts in, in dollars.
     *
     * A count of shares is the wrong unit for this: five shares of a side at
     * eight cents is forty cents at risk and five of a side at eighty is four
     * dollars, and the rule takes both — so the same setting meant ten times
     * the money depending on what the market happened to be charging. Money
     * is what is being risked, so money is what is set, and the shares come
     * out of it at whatever the price is.
     */
    const val DEFAULT_STAKE_USD = 3.0

    /**
     * Or that much of what is free to trade, where a share is set instead.
     *
     * A sum stays the sum while the account doubles or halves; a share grows
     * and shrinks with it. Which is wanted depends on whether the number is a
     * budget or a policy, and both are reasonable — so both are here, and a
     * share, where one is set, is the answer.
     *
     * "Free" is the balance the desk can actually reach: the wallet already
     * has the locked reserve taken out of it before this sees it, so a share
     * of it can never be a share of money set aside.
     */
    const val DEFAULT_STAKE_PCT = 0.0

    /**
     * The window has to have said something before it is worth trading.
     *
     * A minute and a half. Under that the lead is the first swing of a fresh
     * five minutes rather than a direction: the readings all have values, and
     * every one of them is being taken off a sample too short to mean what it
     * says — the momentum is one minute candle, the volume is that same
     * candle against an average, and the book has not been tested by anything
     * yet. The four agreeing there is four ways of describing the same noise.
     */
    const val DEFAULT_FROM_SEC = 90L

    /**
     * How late a window may still be entered — the whole of it, by default.
     *
     * A late entry has no room left to reach its own take price, which was the
     * reason this used to stop a minute before the close. But that is not the
     * only way a lot pays: from [DEFAULT_RIDE_SEC] a side that is still ahead
     * is carried into settlement, which pays a whole dollar and charges no
     * fee, and a lot bought at four and a half minutes on a side that is
     * winning is a lot bought for exactly that. The refusal cost those windows
     * and bought nothing.
     */
    const val DEFAULT_UNTIL_SEC = 300L

    /** From here a winning side is carried into settlement rather than sold. */
    const val DEFAULT_RIDE_SEC = 265L

    /** Dollars of lead that count as a direction rather than noise. */
    const val DEFAULT_MIN_EDGE = 6.0

    /** Share of the book's size, within the span, the winning side needs. */
    const val DEFAULT_MIN_LEAN = 0.55

    /** Last completed minute's volume against the ten before it. */
    const val DEFAULT_MIN_VOLUME = 0.7

    /** The odds band worth trading in. */
    const val DEFAULT_MIN_PRICE = 0.30
    const val DEFAULT_MAX_PRICE = 0.80

    /**
     * And how much dearer a side may be bought as the window runs out.
     *
     * The band's top is set for a window with time left in it: a side at 80c
     * with four minutes to run is charging most of a dollar for the part of
     * the move that has not happened yet, and four minutes is long enough for
     * it to un-happen. With two minutes left the same price is charging for a
     * move that is nearly finished, and with one it is charging for one that
     * is all but over — the odds and the time left have moved together, and
     * a ceiling that does not move with them refuses the windows this rule is
     * most often right about.
     *
     * It only ever lifts. A rule already allowed to pay more late keeps what
     * it had; this cannot narrow a band, only widen it.
     */
    /**
     * A quarter of a minute after the boundary, not on it.
     *
     * These are minute marks, and a minute mark is where a new candle opens.
     * The first seconds of one are the least settled part of a window — the
     * price the rule would be reading at 3:00 exactly has had no time to mean
     * anything yet — so the allowance waits for the candle to say something
     * first. Fifteen seconds is long enough for the opening jump to be over
     * and short enough to leave most of the minute.
     */
    const val SETTLE_SEC = 15L
    const val LATE_SEC = 180L + SETTLE_SEC
    const val LAST_SEC = 240L + SETTLE_SEC
    const val LATE_MAX = 0.83
    const val LAST_MAX = 0.86

    /** The dearest this rule may pay, this many seconds into the window. */
    fun topPrice(elapsedSec: Long, settings: Settings): Double {
        val late = when {
            elapsedSec >= LAST_SEC -> LAST_MAX
            elapsedSec >= LATE_SEC -> LATE_MAX
            else -> 0.0
        }
        return maxOf(settings.maxPrice, late)
    }

    /**
     * The same rule, asking less of the market before it acts.
     *
     * Four independent readings agreeing is a high bar on a five-minute
     * window: most of them never clear it, and the ones that do are the ones
     * the book has already repriced. These are the same four questions with
     * the answers taken earlier — half the lead, a book that merely is not
     * against the side rather than behind it, volume that is merely not dead,
     * and a wider band of odds — so it trades several times as often on
     * evidence that is several times thinner.
     *
     * Which of the two is right is not something to argue about: they run on
     * their own money, side by side, on the same windows, and the records say
     * so in a few days.
     */
    const val SOFT_MIN_EDGE = 3.0
    const val SOFT_MIN_LEAN = 0.50
    const val SOFT_MIN_VOLUME = 0.45
    const val SOFT_MIN_PRICE = 0.20
    const val SOFT_MAX_PRICE = 0.88
    /**
     * And it still waits out the first stretch of a window.
     *
     * Softer gates are about how much evidence is enough, not about reading a
     * window that has not happened yet: in the first minute and a quarter the
     * lead is a few seconds of noise and the book has not been tested. This
     * one is the last thing that should be relaxed.
     */
    const val SOFT_FROM_SEC = 75L

    /** The settings that make one, over whatever the strict rule holds. */
    fun soft(): Settings = Settings(
        ladder = true,
        fromSec = SOFT_FROM_SEC,
        minEdge = SOFT_MIN_EDGE,
        minLean = SOFT_MIN_LEAN,
        minVolume = SOFT_MIN_VOLUME,
        minPrice = SOFT_MIN_PRICE,
        maxPrice = SOFT_MAX_PRICE,
    )

    /**
     * Inside this much of the close, nothing is offered under ninety cents.
     *
     * The last minute is not a normal part of the window. A side that is
     * winning with a minute to run is nearly settled, and settlement pays a
     * whole dollar and charges no fee — so an offer at eighty is giving away
     * twenty cents of money that was very likely already ours, and a ladder
     * that has walked down to seventy-seven asks for that giveaway by design.
     * A side that is losing does not reach ninety and rides to a settlement
     * that pays nothing, which is what it was going to do anyway.
     *
     * Sixty-five seconds rather than sixty: the boundary should sit clear of
     * the minute mark rather than on it, for the same reason the buying
     * allowances do.
     */
    const val LAST_ASK_SEC = 65L
    const val LAST_ASK = 0.90

    /**
     * How long a bid has to stand still before a reached price is taken.
     *
     * These exits watch rather than rest: the price is a number to wait for,
     * and when the book reaches it the rule crosses. Crossing on the first
     * tick that touches it sells into the middle of a move — the bid that has
     * just arrived at the target is usually on its way past it, and taking it
     * there hands the rest of the run to whoever was on the other side.
     *
     * So a reached price is not taken while the bid is still making new highs.
     * It is taken once the bid has gone two and a half seconds without making
     * one, which is what a move looks like when it is over. A bid that falls
     * back is not making highs either, so the same wait caps how much of the
     * run can be given back.
     */
    const val RIDE_MS = 2_500L

    /**
     * And where riding stops being worth anything.
     *
     * Above this there is almost nothing left to run to: a side at ninety-six
     * has four cents of room to a dollar, and it only reaches the dollar by
     * settling, which is minutes away. Waiting for the move to finish there
     * risks the whole gain to chase a rounding error — so a bid this high is
     * taken on the tick that finds it, however fast it is still climbing.
     */
    const val RIDE_TOP = 0.96

    /** No offer under [LAST_ASK] once the close is that near. */
    fun lateFloor(price: Double, secondsLeft: Long): Double =
        if (secondsLeft in 0..LAST_ASK_SEC) maxOf(price, LAST_ASK) else price

    /** What one round is trying to make, on the price paid. */
    const val DEFAULT_TAKE_PCT = 0.15

    /**
     * And the least it may ever be, whatever the setting says.
     *
     * A rule that takes a side the moment four readings agree is paying for
     * that agreement in the ask, so the margin has to be worth the crossing:
     * under this the round is a coin toss with a fee on it. It is a floor
     * rather than a default because a setting stored before the floor existed
     * would otherwise keep selling under it, quietly, forever.
     */
    const val MIN_TAKE_PCT = 0.15

    data class Settings(
        val enabled: Boolean = false,
        val bankUsd: Double = DEFAULT_BANK_USD,
        val stakeUsd: Double = DEFAULT_STAKE_USD,
        val stakePct: Double = DEFAULT_STAKE_PCT,
        val fromSec: Long = DEFAULT_FROM_SEC,
        val untilSec: Long = DEFAULT_UNTIL_SEC,
        val rideSec: Long = DEFAULT_RIDE_SEC,
        val minEdge: Double = DEFAULT_MIN_EDGE,
        val minLean: Double = DEFAULT_MIN_LEAN,
        val minVolume: Double = DEFAULT_MIN_VOLUME,
        val minPrice: Double = DEFAULT_MIN_PRICE,
        val maxPrice: Double = DEFAULT_MAX_PRICE,
        val takePct: Double = DEFAULT_TAKE_PCT,
        /**
         * Whether the wallet trades this rule as well.
         *
         * Paper is not a mode any more, it is the floor: the rule always
         * reads the same live book, takes the same offers at the same prices
         * and pays the same fee on a hundred imaginary dollars, and that
         * record is the only thing that can answer whether it deserves real
         * money. This says whether it has been given some — a second account
         * beside the paper one, on the same reads and the same windows, with
         * its own lot and its own totals.
         */
        val live: Boolean = false,
        /**
         * Exit by the desk's own sell ladder instead of one fixed margin.
         *
         * The two are different bets on the same position. A fixed margin
         * asks one price and waits: it wins whole when the book comes to it
         * and nothing at all when the book stops a cent short. The ladder
         * starts higher and walks down with the clock, so it takes what the
         * window is actually offering rather than what the entry hoped for —
         * which suits a rule that enters often on thin evidence, where most
         * positions are small moves rather than the one big one.
         *
         * The strict rule keeps the fixed margin; the soft one takes this.
         */
        val ladder: Boolean = false,
    )

    /** Everything the rule looks at, read once per check. */
    data class Read(
        val elapsedSec: Long,
        /** Price now less the window's open, in dollars. */
        val lead: Double,
        /** The last few one-minute closes, in dollars: where momentum points. */
        val momentum: Double,
        /** Last completed minute's volume over the average of the ten before. */
        val volume: Double,
        /** Share of the book's size sitting on the bid, 0..1. */
        val lean: Double,
        val upAsk: Double?,
        val downAsk: Double?,
        /** The desk's own early ceiling, which this bot is bound by too. */
        val ceiling: Double,
        val cashUsd: Double,
    )

    /** Which side the window is currently winning, if either. */
    fun leader(lead: Double, minEdge: Double): String? = when {
        lead >= minEdge -> "Up"
        lead <= -minEdge -> "Down"
        else -> null
    }

    /** The price this side is offered at. */
    fun askFor(side: String?, read: Read): Double? =
        when (side) {
            "Up" -> read.upAsk
            "Down" -> read.downAsk
            else -> null
        }

    /**
     * Why the rule is not buying, or null when it is.
     *
     * The order is the order a person would check them in, so the note on the
     * screen is the first thing that is actually wrong rather than the last
     * thing tested.
     */
    fun blockedBecause(read: Read, settings: Settings, holding: Boolean): String? {
        if (!settings.enabled) return "выключен"
        if (holding) return "в позиции"
        if (read.elapsedSec < settings.fromSec) return "рано"
        // A limit at or past the window's own length is no limit at all.
        if (settings.untilSec in 1 until WINDOW_SEC && read.elapsedSec > settings.untilSec) {
            return "поздно"
        }

        val side = leader(read.lead, settings.minEdge)
            ?: return "нет перевеса " + money(abs(read.lead)) + " из " + money(settings.minEdge)

        val up = side == "Up"
        if (up && read.momentum < 0.0 || !up && read.momentum > 0.0) return "импульс против"
        if (read.volume < settings.minVolume) {
            return "нет объёма ×" + String.format("%.2f", read.volume)
        }

        val lean = if (up) read.lean else 1.0 - read.lean
        if (lean < settings.minLean) return "стакан против " + pct(lean)

        val ask = askFor(side, read) ?: return "нет цены"
        // The desk's own early-window ceiling still applies over the top of
        // this: a limit that only covers some of the ways to spend money is
        // not a limit, and that one covers all of them.
        val top = minOf(topPrice(read.elapsedSec, settings), read.ceiling)
        if (ask > top + 1e-9) return "дорого " + cents(ask) + " из " + cents(top)
        if (ask < settings.minPrice - 1e-9) return "рынок против " + cents(ask)
        if (read.cashUsd < stakeOf(read.cashUsd, settings)) return "нет денег"

        return null
    }

    enum class Exit {
        /** Leave the offer where it is. */
        HOLD,

        /** Ahead and nearly done: settlement pays a dollar and charges nothing. */
        RIDE,
    }

    /**
     * What to do with an open lot.
     *
     * Taking profit is not in here: that offer is resting on the book from the
     * moment the lot is opened, and the book fills it or does not.
     *
     * Nor is cutting one. A lot used to be sold into the book at whatever it
     * was bidding once the lead had flipped by a few dollars, and what that
     * bought was the worst price of the window every time: the book marks a
     * side down hardest exactly when the move is against it, so the rule sold
     * ten shares that cost seventy cents at twenty-seven. There is one way out
     * of a position now and it is the price the exit asks for — a rung, or the
     * margin — and a side that never reaches it rides to settlement, which
     * pays a dollar or nothing and charges no fee either way. Losing the whole
     * stake on the windows that go wrong is the shape of this bet; paying the
     * book to leave early was losing most of it and a spread as well.
     */
    fun exitFor(side: String, read: Read, settings: Settings): Exit {
        val ahead = if (side == "Up") read.lead else -read.lead
        if (read.elapsedSec >= settings.rideSec && ahead >= settings.minEdge) return Exit.RIDE
        return Exit.HOLD
    }

    /**
     * Where the profit is offered, snapped up to the venue's step.
     *
     * Never under [MIN_TAKE_PCT] over what the shares cost: that is the whole
     * of "sell dearer", and it holds against a lower setting rather than
     * trusting one.
     */
    fun takeOf(settings: Settings): Double =
        maxOf(MIN_TAKE_PCT, settings.takePct)

    fun takePrice(paid: Double, settings: Settings, tick: Double): Double {
        val wanted = paid * (1.0 + takeOf(settings))
        val step = if (tick > 0) tick else 0.01
        val snapped = kotlin.math.ceil(wanted / step - 1e-9) * step
        return (Math.round(snapped * 10_000.0) / 10_000.0).coerceIn(step, 1.0 - step)
    }

    /**
     * What this entry is worth putting in, against what is free to trade.
     *
     * A share of the free balance where one is set, the flat sum otherwise.
     * Not clamped to the balance: an account that cannot afford its own stake
     * sits the window out rather than taking a smaller position, because half
     * a stake is a different bet and a record made of them answers nothing.
     * The cash gate is what turns that into a refusal.
     */
    fun stakeOf(cashUsd: Double, settings: Settings): Double {
        val free = if (cashUsd.isFinite() && cashUsd > 0.0) cashUsd else 0.0
        return if (settings.stakePct > 0.0) {
            free * minOf(1.0, settings.stakePct)
        } else {
            settings.stakeUsd
        }
    }

    /**
     * How many shares that buys, never under the venue's own floor.
     *
     * The floor is the venue's, not this rule's: an order under it is refused
     * outright, so a stake too small to clear it buys the smallest order that
     * exists rather than nothing — and the cash gate above has already agreed
     * there is money for it.
     */
    fun sharesFor(stakeUsd: Double, price: Double, minimumOrderSize: Double): Double {
        if (price <= 0.0) return 0.0
        val wanted = stakeUsd / price
        val floor = Orders.minShares(price, minimumOrderSize)
        return maxOf(floor, Math.round(wanted * 10.0) / 10.0)
    }

    /** Crossing the spread by a tick: this is meant to be taken now. */
    fun crossPrice(ask: Double, tick: Double): Double {
        val step = if (tick > 0) tick else 0.01
        return (ask + step).coerceAtMost(1.0 - step)
    }

    private fun money(usd: Double) = "$" + String.format("%.0f", usd)
    private fun cents(price: Double) = "${Math.round(price * 100)}¢"
    private fun pct(share: Double) = "${Math.round(share * 100)}%"
}
