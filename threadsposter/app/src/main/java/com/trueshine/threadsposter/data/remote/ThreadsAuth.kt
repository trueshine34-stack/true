package com.trueshine.threadsposter.data.remote

import com.trueshine.threadsposter.core.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class TokenResult(
    val accessToken: String,
    val userId: String,
    /** Секунды жизни; у долгоживущего токена это ~60 дней. */
    val expiresInSeconds: Long?,
)

/**
 * OAuth для Threads.
 *
 * Обмен кода на токен требует client_secret, который Meta велит держать на
 * сервере. Своего сервера у приложения нет, поэтому основной путь — вставить уже
 * готовый долгоживущий токен: продлевать его приложение умеет само, для этого
 * секрет не нужен. Вход через встроенный браузер оставлен как удобная
 * альтернатива для тех, кто готов хранить секрет на телефоне.
 */
class ThreadsAuth(
    private val http: OkHttpClient,
    private val json: Json,
) {

    fun authorizeUrl(clientId: String, redirectUri: String, state: String): String =
        AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", SCOPES.joinToString(","))
            .addQueryParameter("response_type", "code")
            .addQueryParameter("state", state)
            .build()
            .toString()

    /** Код из redirect_uri → короткоживущий токен (около часа жизни). */
    suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
    ): TokenResult {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        return execute(request)
    }

    /** Короткоживущий токен → долгоживущий (60 дней). */
    suspend fun toLongLived(clientSecret: String, shortLivedToken: String): TokenResult {
        val url = LONG_LIVED_URL.toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "th_exchange_token")
            .addQueryParameter("client_secret", clientSecret)
            .addQueryParameter("access_token", shortLivedToken)
            .build()
        return execute(Request.Builder().url(url).get().build())
    }

    /**
     * Продление долгоживущего токена ещё на 60 дней. Секрет не нужен.
     * Токен должен быть старше суток и ещё не протухшим.
     */
    suspend fun refresh(longLivedToken: String): TokenResult {
        val url = REFRESH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "th_refresh_token")
            .addQueryParameter("access_token", longLivedToken)
            .build()
        return execute(Request.Builder().url(url).get().build())
    }

    private suspend fun execute(request: Request): TokenResult {
        val (code, raw) = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
        } catch (e: Exception) {
            throw ThreadsException("Сеть недоступна: ${e.describe()}")
        }

        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw ThreadsException("Некорректный ответ авторизации (HTTP $code)")

        root["error"]?.let { error ->
            val message = runCatching { error.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                ?: runCatching { error.jsonPrimitive.content }.getOrNull()
                ?: "Ошибка авторизации"
            val errorCode = runCatching {
                error.jsonObject["code"]?.jsonPrimitive?.content?.toIntOrNull()
            }.getOrNull()
            throw ThreadsException(message, errorCode)
        }

        val token = root["access_token"]?.jsonPrimitive?.content
            ?: throw ThreadsException("В ответе нет access_token")
        return TokenResult(
            accessToken = token,
            userId = root["user_id"]?.jsonPrimitive?.content.orEmpty(),
            expiresInSeconds = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

    companion object {
        const val AUTHORIZE_URL = "https://threads.net/oauth/authorize"
        const val TOKEN_URL = "https://graph.threads.net/oauth/access_token"
        const val LONG_LIVED_URL = "https://graph.threads.net/access_token"
        const val REFRESH_URL = "https://graph.threads.net/refresh_access_token"

        val SCOPES = listOf(
            "threads_basic",
            "threads_content_publish",
            "threads_read_replies",
            "threads_manage_replies",
            "threads_keyword_search",
        )
    }
}
