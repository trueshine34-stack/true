package com.plovault.sync.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * «Рецепт» выгрузки: HTTP-запрос, который PokerCraft отправляет при скачивании
 * истории рук. Записывается один раз в режиме обучения и потом повторяется
 * автоматически с подстановкой новых дат.
 */
data class ExportRecipe(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    /** Формат дат, найденный в запросе: DASH (2024-03-12), SLASH, COMPACT (20240312), EPOCH_MS, EPOCH_S. */
    val dateFormat: String,
    val capturedAt: Long,
    val note: String = ""
) {
    fun urlFor(from: Long, to: Long, token: String? = null): String = substitute(url, from, to, token)

    fun bodyFor(from: Long, to: Long, token: String? = null): String = substitute(body, from, to, token)

    /** Заголовки с подставленным свежим токеном (он мог быть в Authorization). */
    fun headersFor(token: String? = null): Map<String, String> =
        headers.mapValues { (_, v) -> if (token != null) v.replace(TOKEN, token) else v }

    /** Нужен ли рецепту токен — то есть протухнет ли он вместе со ссылкой из клиента. */
    val usesToken: Boolean
        get() = url.contains(TOKEN) || body.contains(TOKEN) || headers.values.any { it.contains(TOKEN) }

    private fun substitute(s: String, from: Long, to: Long, token: String?): String {
        var r = s.replace(FROM, render(from)).replace(TO, render(to))
        if (token != null) r = r.replace(TOKEN, token)
        return r
    }

    private fun render(ts: Long): String = when (dateFormat) {
        "EPOCH_MS" -> ts.toString()
        "EPOCH_S" -> (ts / 1000).toString()
        "COMPACT" -> fmt("yyyyMMdd", ts)
        "SLASH" -> fmt("yyyy/MM/dd", ts)
        else -> fmt("yyyy-MM-dd", ts)
    }

    private fun fmt(pattern: String, ts: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ts))

    fun toJson(): String = JSONObject().apply {
        put("url", url)
        put("method", method)
        put("body", body)
        put("dateFormat", dateFormat)
        put("capturedAt", capturedAt)
        put("note", note)
        put("headers", JSONObject(headers as Map<*, *>))
    }.toString(2)

    companion object {
        const val FROM = "{{FROM}}"
        const val TO = "{{TO}}"
        const val TOKEN = "{{TOKEN}}"

        fun fromJson(json: String): ExportRecipe? = try {
            val o = JSONObject(json)
            val h = HashMap<String, String>()
            o.optJSONObject("headers")?.let { ho ->
                ho.keys().forEach { k -> h[k] = ho.optString(k) }
            }
            ExportRecipe(
                url = o.getString("url"),
                method = o.optString("method", "GET"),
                headers = h,
                body = o.optString("body", ""),
                dateFormat = o.optString("dateFormat", "DASH"),
                capturedAt = o.optLong("capturedAt", 0L),
                note = o.optString("note", "")
            )
        } catch (e: Exception) {
            null
        }

        /**
         * Подставляет плейсхолдеры вместо дат в записанном запросе.
         * Первая найденная дата → {{FROM}}, вторая → {{TO}}.
         */
        fun templatize(
            url: String,
            body: String,
            token: String? = null
        ): Pair<Triple<String, String, String>, Boolean> {
            // Токен живёт недолго, поэтому в рецепте он заменяется на плейсхолдер
            // и при каждой синхронизации подставляется свежий.
            val u0 = if (!token.isNullOrBlank()) url.replace(token, TOKEN) else url
            val b0 = if (!token.isNullOrBlank()) body.replace(token, TOKEN) else body
            return templatizeDates(u0, b0)
        }

        /** Подставляет токен в записанные заголовки. */
        fun templatizeHeaders(headers: Map<String, String>, token: String?): Map<String, String> =
            if (token.isNullOrBlank()) headers
            else headers.mapValues { (_, v) -> v.replace(token, TOKEN) }

        private fun templatizeDates(url: String, body: String): Pair<Triple<String, String, String>, Boolean> {
            val patterns = listOf(
                "DASH" to Regex("""\d{4}-\d{2}-\d{2}"""),
                "SLASH" to Regex("""\d{4}/\d{2}/\d{2}"""),
                "COMPACT" to Regex("""(?<!\d)\d{8}(?!\d)"""),
                "EPOCH_MS" to Regex("""(?<!\d)1\d{12}(?!\d)"""),
                "EPOCH_S" to Regex("""(?<!\d)1\d{9}(?!\d)""")
            )
            for ((name, rx) in patterns) {
                val combined = "$url\n$body"
                val found = rx.findAll(combined).map { it.value }.distinct().toList()
                if (found.isEmpty()) continue
                val fromVal = found[0]
                val toVal = if (found.size > 1) found[1] else found[0]
                var u = url
                var b = body
                fun repl(s: String): String {
                    var r = s.replaceFirst(fromVal, FROM)
                    r = if (found.size > 1) r.replaceFirst(toVal, TO) else r
                    return r
                }
                u = repl(u)
                b = repl(b)
                return Triple(u, b, name) to true
            }
            return Triple(url, body, "DASH") to false
        }
    }
}
