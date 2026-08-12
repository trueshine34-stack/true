package com.trueshine.tgposter

import com.trueshine.tgposter.core.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorsTest {

    /** Именно этот случай раньше показывался пользователю как «Сеть недоступна: null». */
    @Test
    fun `exception without message never renders as null`() {
        val described = IllegalStateException().describe()
        assertFalse(described.contains("null"))
        assertEquals("IllegalStateException", described)
    }

    @Test
    fun `no internet is explained in plain words`() {
        assertEquals("нет подключения к интернету", UnknownHostException("api.telegram.org").describe())
    }

    @Test
    fun `timeout is explained in plain words`() {
        assertEquals("сервер не ответил вовремя", SocketTimeoutException("timeout").describe())
    }

    @Test
    fun `io message is kept when present`() {
        assertEquals("unexpected end of stream", IOException("unexpected end of stream").describe())
    }

    @Test
    fun `io without message falls back`() {
        assertEquals("сбой соединения", IOException().describe())
    }
}
