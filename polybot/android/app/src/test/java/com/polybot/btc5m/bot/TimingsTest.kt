package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The two venue delays the app times for itself. */
class TimingsTest {

    @Before
    fun clean() {
        Timings.store = null
        Timings.reset()
    }

    // --------------------------------------------- buy -> sellable

    /** One purchase, refused twice and then taken: that is the lock, timed. */
    private fun measureBuy(asset: String, boughtAt: Long, acceptedAfterMs: Long) {
        Timings.sellTried(asset, boughtAt, boughtAt + 200)
        Timings.sellRefused(asset, boughtAt)
        Timings.sellTried(asset, boughtAt, boughtAt + 2_000)
        Timings.sellAccepted(asset, boughtAt, boughtAt + acceptedAfterMs)
    }

    @Test
    fun oneSampleIsNotEnoughToActOn() {
        val now = System.currentTimeMillis()
        measureBuy("a", now, 8_000)

        assertNull(Timings.readyMs())
        assertTrue(Timings.measuring())
        assertEquals(0L, Timings.holdMs(now, now))
    }

    @Test
    fun twoSamplesGiveTheMedianAndAWait() {
        val now = System.currentTimeMillis()
        measureBuy("a", now, 6_000)
        measureBuy("b", now, 10_000)

        assertEquals(8_000L, Timings.readyMs())
        assertEquals(2, Timings.readySamples())
        assertFalse(Timings.measuring())

        // The next purchase is left alone until the measured moment, plus a
        // second of margin — and not a moment longer.
        val bought = now + 60_000
        assertEquals(9_000L, Timings.holdMs(bought, bought))
        assertEquals(4_000L, Timings.holdMs(bought, bought + 5_000))
        assertEquals(0L, Timings.holdMs(bought, bought + 9_000))
        assertEquals(0L, Timings.holdMs(bought, bought + 30_000))
    }

    /**
     * The wait is capped however long the venue once took, or a single slow
     * afternoon would park every later purchase past its own window.
     */
    @Test
    fun theWaitIsCapped() {
        val now = System.currentTimeMillis()
        measureBuy("a", now, 90_000)
        measureBuy("b", now, 100_000)

        val bought = now + 200_000
        assertEquals(Timings.MAX_HOLD_MS, Timings.holdMs(bought, bought))
    }

    /**
     * The measurement must not measure itself. Once the rule waits for the
     * measured moment, its own wait is what the next attempt would time — so an
     * attempt that did not start promptly after the buy is not a sample.
     */
    @Test
    fun anAttemptThatStartedLateIsNotASample() {
        val now = System.currentTimeMillis()
        Timings.sellTried("a", now, now + 12_000)
        Timings.sellAccepted("a", now, now + 12_400)

        assertEquals(0, Timings.readySamples())
    }

    /** A lot from an earlier window is not a fresh purchase and cannot time one. */
    @Test
    fun anAncientLotIsIgnored() {
        val now = System.currentTimeMillis()
        Timings.sellTried("a", now, now + 100)
        Timings.sellAccepted("a", now, now + 10 * 60_000)

        assertEquals(0, Timings.readySamples())
    }

    /** A network failure says nothing about the venue and must not be credited. */
    @Test
    fun aDroppedChaseCannotBeCreditedLater() {
        val now = System.currentTimeMillis()
        Timings.sellTried("a", now, now + 100)
        Timings.sellDropped("a")
        Timings.sellAccepted("a", now, now + 5_000)

        assertEquals(0, Timings.readySamples())
    }

    /** A purchase with no known cost time gives nothing to measure against. */
    @Test
    fun noLotTimeNoSample() {
        val now = System.currentTimeMillis()
        Timings.sellTried("a", 0L, now)
        Timings.sellAccepted("a", 0L, now + 5_000)

        assertEquals(0, Timings.readySamples())
        assertEquals(0L, Timings.holdMs(0L, now))
    }

    // --------------------------------------------- sell -> money

    @Test
    fun theMoneyIsTimedFromTheSaleToTheReadingThatShowsIt() {
        val now = System.currentTimeMillis()
        Timings.balanceRead(100.0, now)
        Timings.sellFilled(usd = 10.0, at = now + 1_000)

        assertTrue(Timings.cashPending())
        // Still the old balance a few seconds on.
        assertFalse(Timings.balanceRead(100.0, now + 9_000))
        // And there it is.
        assertTrue(Timings.balanceRead(109.5, now + 19_000))

        assertFalse(Timings.cashPending())
        assertEquals(1, Timings.cashSamples())
        assertNull(Timings.cashMs())

        Timings.sellFilled(usd = 10.0, at = now + 30_000)
        assertTrue(Timings.balanceRead(119.0, now + 51_000))
        assertEquals(2, Timings.cashSamples())
        // 18 s and 21 s, so the pair sits at 19.5 s.
        assertEquals(19_500L, Timings.cashMs())
        assertFalse(Timings.wantsCash())
    }

    /**
     * Without a reading from before the sale there is nothing to compare
     * against: money already in the balance and money that just arrived look
     * exactly alike.
     */
    @Test
    fun aSaleWithNoBaselineIsNotTimed() {
        val now = System.currentTimeMillis()
        Timings.sellFilled(usd = 10.0, at = now)
        assertFalse(Timings.cashPending())

        Timings.balanceRead(100.0, now + 5_000)
        Timings.sellFilled(usd = 10.0, at = now + 1_000)
        assertFalse(Timings.cashPending())
    }

    /** A sale too small to pick out of a moving balance is not worth timing. */
    @Test
    fun aTinySaleIsSkipped() {
        val now = System.currentTimeMillis()
        Timings.balanceRead(100.0, now)
        Timings.sellFilled(usd = 0.4, at = now + 1_000)

        assertFalse(Timings.cashPending())
    }

    /**
     * A purchase in the meantime hides the proceeds. That is unmeasurable, not
     * slow, so the attempt is dropped rather than filed as a long wait.
     */
    @Test
    fun aWatchThatNeverLandsIsDroppedNotRecorded() {
        val now = System.currentTimeMillis()
        Timings.balanceRead(100.0, now)
        Timings.sellFilled(usd = 10.0, at = now + 1_000)

        assertFalse(Timings.balanceRead(60.0, now + 130_000))
        assertFalse(Timings.cashPending())
        assertEquals(0, Timings.cashSamples())
    }

    // --------------------------------------------- keeping it

    @Test
    fun whatWasMeasuredSurvivesARestart() {
        val kept = HashMap<String, String>()
        Timings.store = object : Timings.Store {
            override fun read(key: String): String? = kept[key]
            override fun write(key: String, value: String) {
                kept[key] = value
            }
        }

        val now = System.currentTimeMillis()
        measureBuy("a", now, 6_000)
        measureBuy("b", now, 10_000)
        assertEquals(8_000L, Timings.readyMs())

        Timings.reset()
        assertNull(Timings.readyMs())

        // A fresh process reading the same store.
        Timings.store = object : Timings.Store {
            override fun read(key: String): String? = kept[key]
            override fun write(key: String, value: String) {
                kept[key] = value
            }
        }
        assertEquals(8_000L, Timings.readyMs())
    }
}
