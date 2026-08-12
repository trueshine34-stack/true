package com.trueshine.threadsposter.data.remote

import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.core.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ThreadsException(
    message: String,
    val code: Int? = null,
    val subcode: Int? = null,
) : Exception(message) {
    /** Ошибки, которые не исправятся повтором: протухший токен, нет прав, битый пост. */
    val permanent: Boolean
        get() = code in setOf(190, 100, 200, 803) ||
            message.orEmpty().contains("Invalid OAuth", ignoreCase = true)
}

data class ThreadsProfile(
    val id: String,
    val username: String,
    val biography: String = "",
)

data class ThreadsPost(
    val id: String,
    val text: String,
    val username: String,
    val permalink: String,
    val timestamp: Long?,
    val isReply: Boolean,
    val hasReplies: Boolean,
)

/**
 * Клиент Threads Graph API.
 *
 * Публикация двухшаговая: сначала создаётся медиа-контейнер, потом он публикуется.
 * Между шагами Meta рекомендует подождать — без паузы публикация контейнера с
 * картинкой регулярно отваливается, потому что сервер ещё не дообработал загрузку.
 */
class ThreadsClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    private val publishGate = Mutex()
    private var lastPublishAt = 0L

    // --- Профиль ---

    suspend fun me(token: String): ThreadsProfile {
        val result = get(
            token, "me",
            "fields" to "id,username,threads_biography"
        ).jsonObject
        return ThreadsProfile(
            id = result.str("id"),
            username = result.str("username"),
            biography = result.str("threads_biography"),
        )
    }

    // --- Публикация ---

    /**
     * Публикует один пост и возвращает его media id.
     * Для ответа достаточно передать [replyToId].
     */
    suspend fun publish(
        token: String,
        userId: String,
        text: String,
        imageUrl: String? = null,
        replyToId: String? = null,
        replyControl: String? = null,
    ): String {
        throttlePublish()
        val params = mutableListOf<Pair<String, String>>()
        if (imageUrl.isNullOrBlank()) {
            params += "media_type" to "TEXT"
            params += "text" to text
        } else {
            params += "media_type" to "IMAGE"
            params += "image_url" to imageUrl
            if (text.isNotBlank()) params += "text" to text
        }
        replyToId?.takeIf { it.isNotBlank() }?.let { params += "reply_to_id" to it }
        replyControl?.takeIf { it.isNotBlank() && it != "everyone" }?.let {
            params += "reply_control" to it
        }

        val container = post(token, "$userId/threads", params).jsonObject.str("id")
        if (container.isBlank()) throw ThreadsException("Threads не вернул id контейнера")

        // Пауза перед публикацией: для картинок она обязательна, для текста — дешёвая страховка.
        delay(if (imageUrl.isNullOrBlank()) TEXT_SETTLE_MS else IMAGE_SETTLE_MS)

        val published = post(
            token, "$userId/threads_publish",
            listOf("creation_id" to container)
        ).jsonObject.str("id")
        if (published.isBlank()) throw ThreadsException("Threads не вернул id опубликованного поста")
        return published
    }

    /** Публикует цепочку: каждый следующий кусок — ответ на предыдущий. */
    suspend fun publishChain(
        token: String,
        userId: String,
        parts: List<String>,
        imageUrl: String? = null,
        replyToId: String? = null,
        replyControl: String? = null,
    ): List<String> {
        val ids = mutableListOf<String>()
        var parent = replyToId
        parts.forEachIndexed { index, part ->
            val id = publish(
                token = token,
                userId = userId,
                text = part,
                imageUrl = if (index == 0) imageUrl else null,
                replyToId = parent,
                replyControl = if (index == 0) replyControl else null,
            )
            ids += id
            parent = id
        }
        return ids
    }

    suspend fun permalinkOf(token: String, mediaId: String): String? = runCatching {
        get(token, mediaId, "fields" to "permalink").jsonObject.str("permalink").takeIf { it.isNotBlank() }
    }.getOrNull()

    // --- Поиск ---

    suspend fun keywordSearch(
        token: String,
        query: String,
        searchType: String,
        limit: Int = 25,
    ): List<ThreadsPost> {
        val result = get(
            token, "keyword_search",
            "q" to query,
            "search_type" to searchType,
            "fields" to SEARCH_FIELDS,
            "limit" to limit.toString(),
        )
        return result.jsonObject["data"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject.toPost() }.getOrNull() }
            .orEmpty()
    }

    // --- Ответы под своими постами ---

    suspend fun myThreads(token: String, userId: String, limit: Int = 15): List<ThreadsPost> {
        val result = get(
            token, "$userId/threads",
            "fields" to SEARCH_FIELDS,
            "limit" to limit.toString(),
        )
        return result.jsonObject["data"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject.toPost() }.getOrNull() }
            .orEmpty()
    }

    suspend fun repliesTo(token: String, mediaId: String, limit: Int = 25): List<ThreadsPost> {
        val result = get(
            token, "$mediaId/replies",
            "fields" to SEARCH_FIELDS,
            "limit" to limit.toString(),
        )
        return result.jsonObject["data"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject.toPost() }.getOrNull() }
            .orEmpty()
    }

    // --- Внутреннее ---

    private fun JsonObject.toPost() = ThreadsPost(
        id = str("id"),
        text = str("text"),
        username = str("username"),
        permalink = str("permalink"),
        timestamp = Time.parseIso(str("timestamp")),
        isReply = str("is_reply").toBoolean(),
        hasReplies = str("has_replies").toBoolean(),
    )

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    /**
     * Threads считает публикации в сутках, но и слишком частые запросы отбивает.
     * Держим минимальный зазор между публикациями.
     */
    private suspend fun throttlePublish() {
        publishGate.withLock {
            val wait = lastPublishAt + MIN_PUBLISH_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastPublishAt = System.currentTimeMillis()
        }
    }

    private suspend fun get(
        token: String,
        path: String,
        vararg params: Pair<String, String>,
    ): kotlinx.serialization.json.JsonElement {
        val url = baseUrl(path).newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("access_token", token)
        }.build()
        return execute(Request.Builder().url(url).get().build())
    }

    private suspend fun post(
        token: String,
        path: String,
        params: List<Pair<String, String>>,
    ): kotlinx.serialization.json.JsonElement {
        // Graph API принимает параметры в query-строке и для POST.
        val url = baseUrl(path).newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("access_token", token)
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null, 0, 0))
            .build()
        return execute(request)
    }

    private fun baseUrl(path: String): HttpUrl = "$BASE_URL/$path".toHttpUrl()

    private suspend fun execute(
        request: Request,
        attempt: Int = 0,
    ): kotlinx.serialization.json.JsonElement {
        val (code, raw) = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
        } catch (e: Exception) {
            if (attempt < MAX_RETRIES) {
                delay(1500L * (attempt + 1))
                return execute(request, attempt + 1)
            }
            throw ThreadsException("Сеть недоступна: ${e.describe()}")
        }

        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw ThreadsException("Некорректный ответ Threads (HTTP $code)")

        val error = root["error"]?.jsonObject
        if (error == null && code in 200..299) return root

        val message = error?.get("message")?.jsonPrimitive?.content ?: "Ошибка Threads (HTTP $code)"
        val errorCode = error?.get("code")?.jsonPrimitive?.content?.toIntOrNull()
        val subcode = error?.get("error_subcode")?.jsonPrimitive?.content?.toIntOrNull()

        // 4 — лимит запросов приложения, 613 — лимит вызовов: имеет смысл подождать.
        if ((code == 429 || errorCode == 4 || errorCode == 613) && attempt < MAX_RETRIES) {
            delay(RATE_LIMIT_BACKOFF_MS * (attempt + 1))
            return execute(request, attempt + 1)
        }
        throw ThreadsException(message, errorCode, subcode)
    }

    companion object {
        const val BASE_URL = "https://graph.threads.net/v1.0"
        const val MAX_POST_LENGTH = 500

        private const val SEARCH_FIELDS =
            "id,text,username,permalink,timestamp,is_reply,has_replies"
        private const val MIN_PUBLISH_INTERVAL_MS = 20_000L
        private const val TEXT_SETTLE_MS = 3_000L
        private const val IMAGE_SETTLE_MS = 30_000L
        private const val MAX_RETRIES = 2
        private const val RATE_LIMIT_BACKOFF_MS = 20_000L
    }
}
