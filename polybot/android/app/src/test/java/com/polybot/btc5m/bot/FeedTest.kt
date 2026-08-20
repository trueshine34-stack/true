package com.polybot.btc5m.bot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frame routing for the live feed.
 *
 * The live socket subscribes to whole topics rather than filtered ones — a
 * filtered subscription is answered once and then goes silent — so every symbol
 * on both topics arrives here and has to be sorted out locally. These tests pin
 * that routing down, since getting it wrong is indistinguishable from a healthy
 * feed until a window settles against a price the bot never saw.
 */
class FeedTest {

    private fun frame(symbol: String, timestamp: Long, value: Double): String =
        JSONObject()
            .put(
                "payload",
                JSONObject()
                    .put("symbol", symbol)
                    .put("timestamp", timestamp)
                    .put("value", value),
            )
            .toString()

    @Test
    fun keepsBtcFromTheChainlinkTopic() {
        val feed = ChainlinkFeed()
        feed.handleMessage(frame("btc/usd", 1_000L, 71_653.4))

        assertEquals(71_653.4, feed.last!!.value, 1e-9)
        assertEquals(1_000L, feed.last!!.timestamp)
    }

    @Test
    fun dropsEveryOtherSymbolOnTheTopic() {
        val feed = ChainlinkFeed()
        for (symbol in listOf("eth/usd", "sol/usd", "xrp/usd", "doge/usd", "hype/usd")) {
            feed.handleMessage(frame(symbol, 1_000L, 2_273.17))
        }

        assertNull(feed.last)
    }

    @Test
    fun routesSpotToItsOwnSlotAndNeverIntoHistory() {
        val feed = ChainlinkFeed()
        feed.handleMessage(frame("btcusdt", 1_000L, 71_714.0))

        assertNull("spot must not reach the settlement series", feed.last)
        assertNotNull(feed.spot)
        assertEquals(71_714.0, feed.spot!!.value, 1e-9)
    }

    @Test
    fun spotAndSettlementCoexist() {
        val feed = ChainlinkFeed()
        feed.handleMessage(frame("btcusdt", 1_000L, 71_714.0))
        feed.handleMessage(frame("btc/usd", 1_000L, 71_653.4))

        assertEquals(71_653.4, feed.last!!.value, 1e-9)
        assertEquals(71_714.0, feed.spot!!.value, 1e-9)
    }

    @Test
    fun ignoresMalformedFrames() {
        val feed = ChainlinkFeed()
        feed.handleMessage("")
        feed.handleMessage("PING")
        feed.handleMessage("PONG")
        feed.handleMessage("not json at all")
        feed.handleMessage(JSONObject().put("topic", "crypto_prices").toString())
        feed.handleMessage(frame("btc/usd", 0L, 71_653.4))
        feed.handleMessage(frame("btc/usd", 1_000L, 0.0))

        assertNull(feed.last)
    }

    @Test
    fun seedFillsInUnderLiveTicksThatArrivedFirst() {
        val feed = ChainlinkFeed()
        // Live ticks land before the backfill socket answers.
        feed.handleMessage(frame("btc/usd", 5_000L, 71_005.0))
        feed.handleMessage(frame("btc/usd", 6_000L, 71_006.0))

        val batch = JSONArray()
        for (t in 1..6) {
            batch.put(JSONObject().put("timestamp", t * 1_000L).put("value", 71_000.0 + t))
        }
        feed.seed(batch)

        val ticks = feed.ticksBetween(0L, 10_000L)
        assertEquals(6, ticks.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L), ticks.map { it.timestamp })
        // The newest tick must still be the newest, not the last one appended.
        assertEquals(6_000L, feed.last!!.timestamp)
    }

    @Test
    fun seedNeverRewindsTheLatestPrice() {
        val feed = ChainlinkFeed()
        feed.handleMessage(frame("btc/usd", 9_000L, 71_009.0))

        val batch = JSONArray().put(JSONObject().put("timestamp", 1_000L).put("value", 70_000.0))
        feed.seed(batch)

        assertEquals(71_009.0, feed.last!!.value, 1e-9)
    }

    @Test
    fun windowLookupsSeeSeededHistory() {
        val feed = ChainlinkFeed()
        val batch = JSONArray()
        for (t in 1..10) {
            batch.put(JSONObject().put("timestamp", t * 1_000L).put("value", 71_000.0 + t))
        }
        feed.seed(batch)

        assertEquals(4_000L, feed.firstTickAtOrAfter(3_500L)!!.timestamp)
        assertEquals(3, feed.ticksBetween(4_000L, 6_000L).size)
    }
}
