package com.polybot.btc5m.bot

/**
 * Two delays the venue imposes and never states, measured instead of guessed.
 *
 * Shares are not sellable the instant a buy matches, and money from a sale is
 * not spendable the instant the sale fills. Both waits were handled by sitting
 * on a number in the settings and retrying against it, which is the wrong shape
 * twice over: the number is a guess, and a retry loop turns the guess into a
 * stream of refusals aimed at the exchange.
 *
 * So the app times them. The first sell that the venue *accepts* after a
 * purchase says how long the shares were locked; the first balance reading
 * that shows a sale's proceeds says how long the money took. A couple of
 * samples of each is enough to stop guessing.
 *
 * The measurement is deliberately self-limiting. A sample only counts when the
 * attempt began promptly after the purchase — once the rule starts waiting for
 * the measured moment instead of trying immediately, its own wait would be all
 * it ever measured again, and the number would drift upward forever. What is
 * measured is therefore frozen until it goes stale, and then measured afresh.
 */
object Timings {

    /** Somewhere to keep what was measured, so a restart does not forget it. */
    interface Store {
        fun read(key: String): String?
        fun write(key: String, value: String)
    }

    /** One timing, and when it was taken. */
    data class Sample(
        val ms: Long,
        val at: Long,
        /**
         * False when the money was already there by the first look, so the
         * true wait was somewhere below this. An upper bound is still worth
         * showing; pretending it is exact is not.
         */
        val exact: Boolean = true,
    )

    /** How many timings to keep, and how few will do. */
    private const val KEEP = 6
    const val MIN_SAMPLES = 2

    /** A measurement older than this is taken again rather than trusted. */
    private const val FRESH_MS = 6 * 60 * 60 * 1000L

    /**
     * An attempt that did not start this soon after the purchase is timing our
     * own hesitation, not the venue's lock, and is not recorded.
     */
    private const val PROMPT_MS = 4_000L

    /** Beyond this the lot belongs to some earlier window; not a measurement. */
    private const val SANE_READY_MS = 3 * 60_000L

    /** Never sit on sellable shares longer than this, whatever was measured. */
    const val MAX_HOLD_MS = 25_000L

    /**
     * A little past the measured moment, so the wait is not cut fine.
     *
     * Half a second, not a whole one: this sits on top of a measurement that
     * is already an upper bound, and every tenth of it is time the shares are
     * held without an exit arranged.
     */
    private const val MARGIN_MS = 500L

    /** How much of the expected proceeds must show up to call the money there. */
    private const val COVER = 0.6

    /** Give up on timing a sale that has not landed by then. */
    private const val CASH_TIMEOUT_MS = 120_000L

    /** Too small to pick out of a balance that moves for other reasons. */
    private const val CASH_MIN_USD = 1.0

    @Volatile
    var store: Store? = null
        set(value) {
            field = value
            load()
        }

    private val ready = ArrayList<Sample>()
    private val cash = ArrayList<Sample>()

    /** A purchase whose first sell attempt is being timed. */
    private data class Chase(val lotAt: Long, val firstTryAt: Long, var refusals: Int)

    private val chases = HashMap<String, Chase>()

    /** A sale whose proceeds are being waited for. */
    private data class CashWatch(val soldAt: Long, val expected: Double, val baseline: Double)

    private var cashWatch: CashWatch? = null

    /** The most recent balance reading, whoever took it, and when. */
    private var lastBalance: Double? = null
    private var lastBalanceAt: Long = 0L

    // ------------------------------------------------- buy -> sellable

    /**
     * A sell for this purchase is about to be sent.
     *
     * The first such moment is what decides whether the purchase can be timed
     * at all: only a chase that began right after the buy measures the venue.
     */
    @Synchronized
    fun sellTried(asset: String, lotAt: Long, now: Long) {
        if (asset.isEmpty() || lotAt <= 0L) return
        val chase = chases[asset]
        if (chase == null || chase.lotAt != lotAt) chases[asset] = Chase(lotAt, now, 0)
    }

    /** The venue refused; the shares are still locked. */
    @Synchronized
    fun sellRefused(asset: String, lotAt: Long) {
        chases[asset]?.takeIf { it.lotAt == lotAt }?.let { it.refusals += 1 }
    }

    /** The venue took the order: the shares became sellable at some point before now. */
    @Synchronized
    fun sellAccepted(asset: String, lotAt: Long, now: Long) {
        val chase = chases.remove(asset) ?: return
        if (chase.lotAt != lotAt || lotAt <= 0L) return
        // Started late — this times our own wait, not the lock.
        if (chase.firstTryAt - lotAt > PROMPT_MS) return
        val ms = now - lotAt
        if (ms < 0L || ms > SANE_READY_MS) return
        add(ready, Sample(ms, now))
        save()
    }

    /** Forget a chase that came to nothing, so a stale one cannot be credited later. */
    @Synchronized
    fun sellDropped(asset: String) {
        chases.remove(asset)
    }

    /** How long the shares stay locked, if that has been measured. */
    @Synchronized
    fun readyMs(): Long? = median(fresh(ready))

    /** Samples behind the figure above. */
    @Synchronized
    fun readySamples(): Int = fresh(ready).size

    /** Still short of a usable measurement, so the rule should keep trying blind. */
    @Synchronized
    fun measuring(): Boolean = fresh(ready).size < MIN_SAMPLES

    /**
     * How much longer to leave a purchase alone before offering it.
     *
     * Zero while nothing has been measured — trying at once and being refused
     * is how the measurement gets taken in the first place.
     */
    @Synchronized
    fun holdMs(lotAt: Long, now: Long): Long {
        if (lotAt <= 0L) return 0L
        val measured = median(fresh(ready)) ?: return 0L
        val wait = minOf(measured + MARGIN_MS, MAX_HOLD_MS)
        return (lotAt + wait - now).coerceAtLeast(0L)
    }

    // ------------------------------------------------- sell -> money

    /**
     * A sale filled: start timing when its proceeds turn up in the balance.
     *
     * Only worth starting when there is a balance reading from *before* the
     * sale to measure against — without one there is no way to tell proceeds
     * that just arrived from proceeds that arrived while nobody was looking.
     */
    @Synchronized
    fun sellFilled(usd: Double, at: Long) {
        if (usd < CASH_MIN_USD) return
        if (!wantsCash()) return
        if (cashWatch != null) return
        val baseline = lastBalance ?: return
        if (lastBalanceAt >= at) return
        cashWatch = CashWatch(at, usd, baseline)
    }

    /** Is a sale still waiting to be seen in the balance? */
    @Synchronized
    fun cashPending(): Boolean = cashWatch != null

    /** Nothing left to learn about the money; stop probing the balance. */
    @Synchronized
    fun wantsCash(): Boolean = fresh(cash).size < MIN_SAMPLES

    /**
     * A balance reading, from wherever. Doubles as the baseline for the next
     * sale, which is why every reader hands one in and not only the probe.
     *
     * @return true when this reading completed a measurement.
     */
    @Synchronized
    fun balanceRead(usd: Double, now: Long): Boolean {
        lastBalance = usd
        lastBalanceAt = now

        val watch = cashWatch ?: return false
        if (now <= watch.soldAt) return false
        if (usd - watch.baseline >= watch.expected * COVER) {
            add(cash, Sample(now - watch.soldAt, now))
            cashWatch = null
            save()
            return true
        }
        // A buy in the meantime can hide the proceeds as surely as a slow
        // settlement can. Unmeasurable is not the same as slow, so it is
        // dropped rather than recorded as a long wait.
        if (now - watch.soldAt > CASH_TIMEOUT_MS) cashWatch = null
        return false
    }

    /** How long money takes to become spendable, if that has been measured. */
    @Synchronized
    fun cashMs(): Long? = median(fresh(cash))

    @Synchronized
    fun cashSamples(): Int = fresh(cash).size

    /** True when every timing kept is only an upper bound. */
    @Synchronized
    fun cashExact(): Boolean = fresh(cash).let { it.isNotEmpty() && it.any { s -> s.exact } }

    // ------------------------------------------------- housekeeping

    @Synchronized
    fun reset() {
        ready.clear()
        cash.clear()
        chases.clear()
        cashWatch = null
        lastBalance = null
        lastBalanceAt = 0L
    }

    private fun add(list: ArrayList<Sample>, sample: Sample) {
        list.add(sample)
        while (list.size > KEEP) list.removeAt(0)
    }

    private fun fresh(list: List<Sample>): List<Sample> {
        val now = System.currentTimeMillis()
        return list.filter { now - it.at <= FRESH_MS }
    }

    /**
     * The middle timing, not the mean: one refusal that dragged on because the
     * exchange was busy should not move what every ordinary purchase waits.
     */
    private fun median(samples: List<Sample>): Long? {
        if (samples.size < MIN_SAMPLES) return null
        val sorted = samples.map { it.ms }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    private fun save() {
        val store = store ?: return
        store.write(KEY_READY, encode(ready))
        store.write(KEY_CASH, encode(cash))
    }

    private fun load() {
        val store = store ?: return
        ready.clear()
        ready.addAll(decode(store.read(KEY_READY)))
        cash.clear()
        cash.addAll(decode(store.read(KEY_CASH)))
    }

    private const val KEY_READY = "readySamples"
    private const val KEY_CASH = "cashSamples"

    private fun encode(samples: List<Sample>): String =
        samples.joinToString(",") { "${it.ms}:${it.at}:${if (it.exact) 1 else 0}" }

    private fun decode(raw: String?): List<Sample> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { part ->
            val bits = part.split(":")
            if (bits.size < 2) return@mapNotNull null
            val ms = bits[0].toLongOrNull() ?: return@mapNotNull null
            val at = bits[1].toLongOrNull() ?: return@mapNotNull null
            Sample(ms, at, bits.getOrNull(2) != "0")
        }
    }
}
