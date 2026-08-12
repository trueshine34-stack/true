package com.trueshine.threadsposter

import com.trueshine.threadsposter.core.ThreadsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsTextTest {

    @Test
    fun `markdown is stripped because threads has no formatting`() {
        val result = ThreadsText.prepare("**Важно**: читайте _дальше_")
        assertEquals("Важно: читайте дальше", result)
    }

    @Test
    fun `html tags are removed`() {
        val result = ThreadsText.prepare("<b>жирный</b> текст<br>вторая строка")
        assertFalse(result.contains("<"))
        assertTrue(result.contains("жирный текст"))
    }

    @Test
    fun `markdown link keeps the url visible`() {
        val result = ThreadsText.prepare("[сайт](https://example.com)")
        assertTrue(result.contains("https://example.com"))
        assertTrue(result.contains("сайт"))
    }

    @Test
    fun `headings and bullets become plain text`() {
        val result = ThreadsText.prepare("## Заголовок\n* первый\n* второй")
        assertFalse(result.contains("#"))
        assertTrue(result.contains("• первый"))
    }

    @Test
    fun `long text splits into chunks within the limit`() {
        val sentence = "Это предложение примерно на сорок символов. "
        val text = sentence.repeat(40)
        val parts = ThreadsText.split(text)

        assertTrue(parts.size > 1)
        parts.forEach { assertTrue("часть длиной ${it.length}", it.length <= ThreadsText.MAX_LENGTH) }
    }

    @Test
    fun `splitting keeps all words`() {
        val text = List(30) { "слово$it" }.joinToString(" ") { it.repeat(3) }
        val joined = ThreadsText.split(text, 100).joinToString(" ")
        assertEquals(text.split(" ").size, joined.split(" ").size)
    }

    @Test
    fun `short text stays one part`() {
        assertEquals(listOf("Короткий пост"), ThreadsText.split("Короткий пост"))
    }

    @Test
    fun `numbering is added only to chains`() {
        val single = ThreadsText.numberParts(listOf("один"))
        assertEquals(listOf("один"), single)

        val chain = ThreadsText.numberParts(listOf("первый", "второй"))
        assertEquals(listOf("первый 1/2", "второй 2/2"), chain)
    }

    @Test
    fun `numbering never pushes a part over the limit`() {
        val long = "я".repeat(ThreadsText.MAX_LENGTH)
        val parts = ThreadsText.numberParts(listOf(long, "хвост"))
        parts.forEach { assertTrue(it.length <= ThreadsText.MAX_LENGTH) }
        assertEquals(long, parts[0])
    }

    @Test
    fun `trimTo cuts on a word boundary`() {
        val result = ThreadsText.trimTo("один два три четыре пять", 12)
        assertTrue(result.endsWith("…"))
        assertTrue(result.length <= 12)
        assertFalse(result.contains("четыре"))
    }

    @Test
    fun `blank lines are collapsed`() {
        assertEquals("а\n\nб", ThreadsText.prepare("а\n\n\n\n\nб"))
    }
}
