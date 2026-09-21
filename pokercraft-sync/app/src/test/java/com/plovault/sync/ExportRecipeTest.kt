package com.plovault.sync

import com.plovault.sync.data.ExportRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ExportRecipeTest {

    private fun ts(date: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!.time

    @Test
    fun templatizesIsoDatesInUrl() {
        val url = "https://pokercraft.gg/api/hand-history/download?from=2024-03-01&to=2024-03-12&game=PLO"
        val (tpl, found) = ExportRecipe.templatize(url, "")
        assertTrue(found)
        assertEquals("DASH", tpl.third)
        assertEquals(
            "https://pokercraft.gg/api/hand-history/download?from=${ExportRecipe.FROM}&to=${ExportRecipe.TO}&game=PLO",
            tpl.first
        )
    }

    @Test
    fun templatizesDatesInJsonBody() {
        val body = """{"startDate":"20240301","endDate":"20240312","gameType":"PLO"}"""
        val (tpl, found) = ExportRecipe.templatize("https://x.gg/api/export", body)
        assertTrue(found)
        assertEquals("COMPACT", tpl.third)
        assertTrue(tpl.second.contains(ExportRecipe.FROM))
        assertTrue(tpl.second.contains(ExportRecipe.TO))
    }

    @Test
    fun templatizesEpochMillis() {
        val body = """{"from":1709251200000,"to":1710288000000}"""
        val (tpl, found) = ExportRecipe.templatize("https://x.gg/api/export", body)
        assertTrue(found)
        assertEquals("EPOCH_MS", tpl.third)
    }

    @Test
    fun substitutesNewDatesOnReplay() {
        val recipe = ExportRecipe(
            url = "https://x.gg/api/dl?from=${ExportRecipe.FROM}&to=${ExportRecipe.TO}",
            method = "GET",
            headers = mapOf("Accept" to "application/json"),
            body = "",
            dateFormat = "DASH",
            capturedAt = 0L
        )
        val url = recipe.urlFor(ts("2025-01-05"), ts("2025-01-09"))
        assertEquals("https://x.gg/api/dl?from=2025-01-05&to=2025-01-09", url)
    }

    @Test
    fun survivesJsonRoundTrip() {
        val recipe = ExportRecipe(
            url = "https://x.gg/api/dl?from=${ExportRecipe.FROM}&to=${ExportRecipe.TO}",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json", "X-Token" to "abc"),
            body = """{"from":"${ExportRecipe.FROM}","to":"${ExportRecipe.TO}"}""",
            dateFormat = "SLASH",
            capturedAt = 123L,
            note = "тест"
        )
        val back = ExportRecipe.fromJson(recipe.toJson())!!
        assertEquals(recipe.url, back.url)
        assertEquals(recipe.method, back.method)
        assertEquals(recipe.body, back.body)
        assertEquals(recipe.dateFormat, back.dateFormat)
        assertEquals("abc", back.headers["X-Token"])
        val body = back.bodyFor(ts("2025-02-01"), ts("2025-02-03"))
        assertEquals("""{"from":"2025/02/01","to":"2025/02/03"}""", body)
    }

    @Test
    fun handlesRequestWithoutDates() {
        val (tpl, found) = ExportRecipe.templatize("https://x.gg/api/dl?session=last", "")
        assertTrue(!found)
        assertEquals("https://x.gg/api/dl?session=last", tpl.first)
    }
}
