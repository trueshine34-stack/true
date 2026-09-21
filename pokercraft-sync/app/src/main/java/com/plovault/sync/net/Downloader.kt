package com.plovault.sync.net

import android.content.Context
import android.webkit.CookieManager
import com.plovault.sync.data.ExportRecipe
import com.plovault.sync.data.Prefs
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class DownloadResult(
    val ok: Boolean,
    val bytes: ByteArray,
    val fileName: String,
    val contentType: String,
    val status: Int,
    val error: String? = null
)

/** Скачивает файлы, подставляя куки из WebView — то есть от имени залогиненного пользователя. */
class Downloader(context: Context) {

    private val prefs = Prefs(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val forbiddenHeaders = setOf(
        "host", "content-length", "connection", "cookie", "accept-encoding",
        "sec-fetch-mode", "sec-fetch-site", "sec-fetch-dest"
    )

    fun run(recipe: ExportRecipe, fromMillis: Long, toMillis: Long): DownloadResult {
        val token = prefs.authToken
        val url = recipe.urlFor(fromMillis, toMillis, token)
        val body = recipe.bodyFor(fromMillis, toMillis, token)
        return execute(url, recipe.method, recipe.headersFor(token), body)
    }

    fun get(url: String): DownloadResult = execute(url, "GET", emptyMap(), "")

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String
    ): DownloadResult {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                if (k.lowercase() !in forbiddenHeaders) runCatching { builder.header(k, v) }
            }
            CookieManager.getInstance().getCookie(url)?.let { builder.header("Cookie", it) }
            prefs.userAgent?.let { builder.header("User-Agent", it) }
            builder.header("Accept", "*/*")
            runCatching { builder.header("Referer", prefs.portalUrl) }

            val m = method.uppercase()
            if (m == "GET" || m == "HEAD") {
                builder.method(m, null)
            } else {
                val ct = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
                    ?: "application/json"
                builder.method(m, body.toRequestBody(ct.toMediaTypeOrNull()))
            }

            client.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val cd = resp.header("Content-Disposition") ?: ""
                val name = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""")
                    .find(cd)?.groupValues?.get(1)
                    ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "export.bin" }
                DownloadResult(
                    ok = resp.isSuccessful && bytes.isNotEmpty(),
                    bytes = bytes,
                    fileName = name,
                    contentType = resp.header("Content-Type") ?: "",
                    status = resp.code,
                    error = if (resp.isSuccessful) null else "HTTP ${resp.code}"
                )
            }
        } catch (e: Exception) {
            DownloadResult(false, ByteArray(0), "", "", 0, e.message ?: e.toString())
        }
    }
}
