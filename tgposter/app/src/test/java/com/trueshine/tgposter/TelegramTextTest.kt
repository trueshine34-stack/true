package com.trueshine.tgposter

import com.trueshine.tgposter.core.TelegramText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTextTest {

    @Test
    fun `markdown bold becomes html bold`() {
        val result = TelegramText.prepare("**Важно**: читайте дальше", "HTML")
        assertEquals("<b>Важно</b>: читайте дальше", result)
    }

    @Test
    fun `unsupported tags are escaped`() {
        val result = TelegramText.prepare("<p>Текст</p><br><b>жирный</b>", "HTML")
        assertTrue(result.contains("&lt;p&gt;"))
        assertTrue(result.contains("<b>жирный</b>"))
    }

    @Test
    fun `unclosed tag is closed`() {
        val result = TelegramText.sanitizeHtml("<b>начали и не закрыли")
        assertEquals("<b>начали и не закрыли</b>", result)
    }

    @Test
    fun `stray closing tag is dropped`() {
        val result = TelegramText.sanitizeHtml("текст</i> дальше")
        assertFalse(result.contains("</i>"))
    }

    @Test
    fun `markdown link becomes anchor`() {
        val result = TelegramText.prepare("[сайт](https://example.com)", "HTML")
        assertEquals("<a href=\"https://example.com\">сайт</a>", result)
    }

    @Test
    fun `ampersand is escaped once`() {
        val result = TelegramText.prepare("Иванов & партнёры", "HTML")
        assertEquals("Иванов &amp; партнёры", result)
    }

    @Test
    fun `long text splits on paragraph boundary`() {
        val paragraph = "а".repeat(300)
        val text = List(20) { paragraph }.joinToString("\n\n")
        val parts = TelegramText.split(text, 1000)

        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.length <= 1000) }
        assertEquals(text.replace("\n\n", ""), parts.joinToString("").replace("\n\n", ""))
    }

    @Test
    fun `plain mode strips all tags`() {
        val result = TelegramText.prepare("<b>жирный</b> текст", "NONE")
        assertEquals("жирный текст", result)
    }

    @Test
    fun `markdownv2 escapes special characters`() {
        val result = TelegramText.prepare("Цена — 100$ (скидка 20%)", "MARKDOWNV2")
        assertTrue(result.contains("\\("))
        assertTrue(result.contains("\\)"))
    }
}
