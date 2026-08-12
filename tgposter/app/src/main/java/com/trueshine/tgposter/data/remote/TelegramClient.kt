package com.trueshine.tgposter.data.remote

import com.trueshine.tgposter.core.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TelegramException(
    message: String,
    val errorCode: Int? = null,
    val retryAfterSeconds: Int? = null,
) : Exception(message)

data class TgBotInfo(val id: Long, val username: String, val firstName: String)

data class TgChatInfo(val id: Long, val title: String, val type: String, val username: String?)

data class TgSentMessage(val messageId: Long, val chatId: Long)

/**
 * Клиент Telegram Bot API.
 *
 * Bot API не отдаёт список каналов бота, поэтому канал добавляется вручную по
 * @username или числовому id, а приложение проверяет доступ через getChat.
 */
class TelegramClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    private val globalGate = Mutex()
    private var lastGlobalSendAt = 0L
    private val perChatLastSend = mutableMapOf<String, Long>()

    suspend fun getMe(token: String): TgBotInfo {
        val result = call(token, "getMe", buildJsonObject { }).jsonObject
        return TgBotInfo(
            id = result["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            username = result["username"]?.jsonPrimitive?.content.orEmpty(),
            firstName = result["first_name"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    suspend fun getChat(token: String, chatId: String): TgChatInfo {
        val result = call(token, "getChat", buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
        }).jsonObject
        return TgChatInfo(
            id = result["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            title = result["title"]?.jsonPrimitive?.content
                ?: result["username"]?.jsonPrimitive?.content.orEmpty(),
            type = result["type"]?.jsonPrimitive?.content.orEmpty(),
            username = result["username"]?.jsonPrimitive?.content,
        )
    }

    /** Проверка, что бот действительно админ канала и может публиковать. */
    suspend fun canPost(token: String, chatId: String, botId: Long): Boolean = runCatching {
        val result = call(token, "getChatMember", buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
            put("user_id", JsonPrimitive(botId))
        }).jsonObject
        val status = result["status"]?.jsonPrimitive?.content
        when (status) {
            "creator" -> true
            "administrator" -> result["can_post_messages"]?.jsonPrimitive?.content?.toBoolean() ?: true
            else -> false
        }
    }.getOrDefault(false)

    suspend fun sendMessage(
        token: String,
        chatId: String,
        text: String,
        parseMode: String? = "HTML",
        disablePreview: Boolean = true,
        silent: Boolean = false,
        protectContent: Boolean = false,
    ): TgSentMessage {
        throttle(chatId)
        val result = call(token, "sendMessage", buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
            put("text", JsonPrimitive(text))
            if (!parseMode.isNullOrBlank() && parseMode != "NONE") {
                put("parse_mode", JsonPrimitive(parseMode))
            }
            put("link_preview_options", buildJsonObject {
                put("is_disabled", JsonPrimitive(disablePreview))
            })
            put("disable_notification", JsonPrimitive(silent))
            put("protect_content", JsonPrimitive(protectContent))
        }).jsonObject
        return result.toSentMessage()
    }

    suspend fun sendPhoto(
        token: String,
        chatId: String,
        photoUrl: String,
        caption: String?,
        parseMode: String? = "HTML",
        silent: Boolean = false,
        protectContent: Boolean = false,
    ): TgSentMessage {
        throttle(chatId)
        val result = call(token, "sendPhoto", buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
            put("photo", JsonPrimitive(photoUrl))
            if (!caption.isNullOrBlank()) {
                put("caption", JsonPrimitive(caption))
                if (!parseMode.isNullOrBlank() && parseMode != "NONE") {
                    put("parse_mode", JsonPrimitive(parseMode))
                }
            }
            put("disable_notification", JsonPrimitive(silent))
            put("protect_content", JsonPrimitive(protectContent))
        }).jsonObject
        return result.toSentMessage()
    }

    suspend fun pinMessage(token: String, chatId: String, messageId: Long) {
        call(token, "pinChatMessage", buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
            put("message_id", JsonPrimitive(messageId))
            put("disable_notification", JsonPrimitive(true))
        })
    }

    suspend fun deleteMessage(token: String, chatId: String, messageId: Long) {
        call(token, "deleteMessage", buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
            put("message_id", JsonPrimitive(messageId))
        })
    }

    private fun JsonObject.toSentMessage(): TgSentMessage = TgSentMessage(
        messageId = this["message_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        chatId = this["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
    )

    /**
     * Телеграм ограничивает частоту: ~30 сообщений в секунду суммарно и не чаще
     * одного сообщения в секунду в один и тот же чат. Держим запас.
     */
    private suspend fun throttle(chatId: String) {
        globalGate.withLock {
            val now = System.currentTimeMillis()
            val waitGlobal = (lastGlobalSendAt + MIN_GLOBAL_INTERVAL_MS) - now
            val waitChat = (perChatLastSend[chatId] ?: 0L) + MIN_CHAT_INTERVAL_MS - now
            val wait = maxOf(waitGlobal, waitChat)
            if (wait > 0) delay(wait)
            val sentAt = System.currentTimeMillis()
            lastGlobalSendAt = sentAt
            perChatLastSend[chatId] = sentAt
        }
    }

    private suspend fun call(
        token: String,
        method: String,
        body: JsonObject,
        attempt: Int = 0,
    ): kotlinx.serialization.json.JsonElement {
        if (token.isBlank()) throw TelegramException("Не задан токен бота")
        val request = Request.Builder()
            .url("$BASE_URL/bot$token/$method")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        // Запрос всегда уходит в IO: метод вызывается и из воркеров, и напрямую из
        // экранов настройки, где корутина живёт на главном потоке.
        val (code, raw) = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            }
        } catch (e: Exception) {
            if (attempt < MAX_NETWORK_RETRIES) {
                delay(1000L * (attempt + 1))
                return call(token, method, body, attempt + 1)
            }
            throw TelegramException("Сеть недоступна: ${e.describe()}")
        }

        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw TelegramException("Некорректный ответ Telegram (HTTP $code)")

        val ok = parsed["ok"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (ok) return parsed["result"] ?: buildJsonObject { }

        val description = parsed["description"]?.jsonPrimitive?.content ?: "Ошибка Telegram"
        val errorCode = parsed["error_code"]?.jsonPrimitive?.content?.toIntOrNull()
        val retryAfter = parsed["parameters"]?.jsonObject
            ?.get("retry_after")?.jsonPrimitive?.content?.toIntOrNull()

        if (errorCode == 429 && retryAfter != null && attempt < MAX_FLOOD_RETRIES) {
            delay(retryAfter * 1000L + 500L)
            return call(token, method, body, attempt + 1)
        }
        throw TelegramException(description, errorCode, retryAfter)
    }

    companion object {
        private const val BASE_URL = "https://api.telegram.org"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MIN_GLOBAL_INTERVAL_MS = 1_100L
        private const val MIN_CHAT_INTERVAL_MS = 3_500L
        private const val MAX_NETWORK_RETRIES = 2
        private const val MAX_FLOOD_RETRIES = 3

        const val MAX_MESSAGE_LENGTH = 4096
        const val MAX_CAPTION_LENGTH = 1024
    }
}
