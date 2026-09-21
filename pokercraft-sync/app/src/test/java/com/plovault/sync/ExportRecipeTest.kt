package com.plovault.sync

import com.plovault.sync.data.ExportRecipe
import com.plovault.sync.data.Prefs
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
    fun tokenIsReplacedByPlaceholderAndRefreshedOnReplay() {
        val token = "aaaaaaaabbbbbbbbccccccccdddddddd"
        val url = "https://my.pokercraft.com/api/hh/download?token=$token&from=2024-03-01&to=2024-03-12"
        val (tpl, found) = ExportRecipe.templatize(url, "", token)
        assertTrue(found)
        assertTrue(tpl.first.contains(ExportRecipe.TOKEN))
        assertTrue(!tpl.first.contains(token))

        val recipe = ExportRecipe(
            url = tpl.first,
            method = "GET",
            headers = ExportRecipe.templatizeHeaders(mapOf("Authorization" to "Bearer $token"), token),
            body = tpl.second,
            dateFormat = tpl.third,
            capturedAt = 0L
        )
        assertTrue(recipe.usesToken)
        assertEquals("Bearer ${ExportRecipe.TOKEN}", recipe.headers["Authorization"])

        val fresh = "11112222333344445555666677778888"
        assertEquals(
            "https://my.pokercraft.com/api/hh/download?token=$fresh&from=2025-01-05&to=2025-01-09",
            recipe.urlFor(ts("2025-01-05"), ts("2025-01-09"), fresh)
        )
        assertEquals("Bearer $fresh", recipe.headersFor(fresh)["Authorization"])
    }

    @Test
    fun recipeWithoutTokenDoesNotRequireOne() {
        val recipe = ExportRecipe(
            url = "https://my.pokercraft.com/api/hh?from=${ExportRecipe.FROM}&to=${ExportRecipe.TO}",
            method = "GET", headers = emptyMap(), body = "", dateFormat = "DASH", capturedAt = 0L
        )
        assertTrue(!recipe.usesToken)
    }

    @Test
    fun extractsTokenFromPokercraftLink() {
        val link = "https://my.pokercraft.com/?token=0123456789abcdef0123456789abcdef&lang=ru"
        val token = Prefs.extractToken(link)
        assertEquals(32, token?.length)
        assertEquals("https://my.pokercraft.com/?token=${ExportRecipe.TOKEN}&lang=ru", link.replace(token!!, ExportRecipe.TOKEN))
        assertTrue(Prefs.maskToken(token).contains("…"))
        assertTrue(!Prefs.maskToken(token).contains(token.substring(6, 20)))
        assertEquals("нет", Prefs.maskToken(null))
    }

    @Test
    fun handlesRequestWithoutDates() {
        val (tpl, found) = ExportRecipe.templatize("https://x.gg/api/dl?session=last", "")
        assertTrue(!found)
        assertEquals("https://x.gg/api/dl?session=last", tpl.first)
    }
}
