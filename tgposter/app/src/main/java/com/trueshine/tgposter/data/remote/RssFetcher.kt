package com.trueshine.tgposter.data.remote

import com.trueshine.tgposter.core.TelegramText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class FeedItem(val title: String, val summary: String, val link: String)

/** Забирает RSS/Atom-ленты, чтобы дать модели свежий фактический материал. */
class RssFetcher(private val http: OkHttpClient) {

    suspend fun fetch(url: String, limit: Int = 8): List<FeedItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TgPoster/1.0 (+android)")
            .build()
        val xml = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        parse(xml, limit)
    }

    private fun parse(xml: String, limit: Int): List<FeedItem> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val items = mutableListOf<FeedItem>()
        var title = StringBuilder()
        var summary = StringBuilder()
        var link = StringBuilder()
        var current: StringBuilder? = null
        var inItem = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && items.size < limit) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "item", "entry" -> {
                            inItem = true
                            title = StringBuilder(); summary = StringBuilder(); link = StringBuilder()
                        }
                        "title" -> if (inItem) current = title
                        "description", "summary", "content" -> if (inItem) current = summary
                        "link" -> if (inItem) {
                            // В Atom ссылка лежит в атрибуте href.
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) link.append(href) else current = link
                        }
                    }
                }

                XmlPullParser.TEXT -> current?.append(parser.text)

                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "item", "entry" -> {
                            if (inItem && title.isNotBlank()) {
                                items += FeedItem(
                                    title = title.toString().cleanup(200),
                                    summary = TelegramText.stripHtml(summary.toString()).cleanup(500),
                                    link = link.toString().trim(),
                                )
                            }
                            inItem = false
                            current = null
                        }
                        else -> current = null
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun String.cleanup(max: Int): String =
        replace(Regex("\\s+"), " ").trim().take(max)
}
