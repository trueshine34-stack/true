package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the venue says, in words.
 *
 * The raw refusal is a URL, a status code and a JSON body carrying token
 * amounts in six-decimal integers. All true, and none of it readable on a phone
 * with three minutes left in the window.
 */
class HumanErrorTest {

    @Test
    fun theCommonRefusalIsNamed() {
        val raw = "400 https://clob.polymarket.com/order: {\"error\":\"not enough " +
            "balance / allowance: the balance is not enough - \\u003e balance: " +
            "13896, order amount: 9000000\"}"

        assertEquals("Не хватает баланса", ClobApi.humanError(raw, 400))
    }

    @Test
    fun aRateLimitIsNotAMystery() {
        assertEquals("Биржа просит подождать", ClobApi.humanError("429 Too Many Requests", 429))
    }

    @Test
    fun aClosedMarketSaysSo() {
        assertEquals(
            "Рынок больше не принимает заявки",
            ClobApi.humanError("{\"error\":\"market not accepting orders\"}", 400),
        )
    }

    @Test
    fun aSizeOrPriceRefusalIsNamedToo() {
        assertEquals(
            "Заявка меньше минимума биржи",
            ClobApi.humanError("{\"error\":\"invalid amount\"}", 400),
        )
        assertEquals(
            "Цена не по сетке рынка",
            ClobApi.humanError("{\"error\":\"invalid price: not a tick\"}", 400),
        )
    }

    @Test
    fun anUnknownRefusalKeepsItsWordsButLosesTheUrl() {
        val out = ClobApi.humanError(
            "400 https://clob.polymarket.com/order: {\"error\":\"something new\"}",
            400,
        )

        assertTrue(out.contains("something new"))
        assertTrue("the URL is noise", !out.contains("https://"))
    }
}
