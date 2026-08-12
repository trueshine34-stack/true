package com.trueshine.tgposter.data.remote

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DeepSeekException(message: String, val httpCode: Int? = null) : Exception(message)

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ResponseFormat(val type: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean = false,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

data class ChatResult(val content: String, val tokensUsed: Int, val model: String)

object DeepSeekModels {
    const val CHAT = "deepseek-chat"
    const val REASONER = "deepseek-reasoner"
    val all = listOf(CHAT, REASONER)
}

/** Клиент DeepSeek (OpenAI-совместимый /chat/completions). */
class DeepSeekClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    suspend fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.9,
        maxTokens: Int = 2000,
        jsonMode: Boolean = false,
    ): ChatResult {
        if (apiKey.isBlank()) throw DeepSeekException("Не задан API-ключ DeepSeek")

        val payload = ChatRequest(
            model = model.ifBlank { DeepSeekModels.CHAT },
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            responseFormat = if (jsonMode) ResponseFormat("json_object") else null,
        )
        val body = json.encodeToString(ChatRequest.serializer(), payload)
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        var lastError: DeepSeekException? = null
        repeat(MAX_RETRIES) { attempt ->
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val (code, raw) = try {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: Exception) {
                lastError = DeepSeekException("Сеть недоступна: ${e.message}")
                delay(BACKOFF_MS * (attempt + 1))
                return@repeat
            }

            if (code in 200..299) {
                val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: throw DeepSeekException("Некорректный ответ DeepSeek")
                val content = root["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content
                    ?: throw DeepSeekException("DeepSeek вернул пустой ответ")
                val tokens = root["usage"]?.jsonObject?.get("total_tokens")
                    ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val usedModel = root["model"]?.jsonPrimitive?.content ?: payload.model
                return ChatResult(content.trim(), tokens, usedModel)
            }

            val message = extractError(raw) ?: "HTTP $code"
            lastError = DeepSeekException(message, code)
            // 429 и 5xx имеет смысл повторить, остальное — нет.
            if (code != 429 && code < 500) throw lastError!!
            delay(BACKOFF_MS * (attempt + 1))
        }
        throw lastError ?: DeepSeekException("DeepSeek недоступен")
    }

    /** Быстрая проверка ключа: короткий запрос без генерации контента. */
    suspend fun testKey(apiKey: String, baseUrl: String, model: String): String {
        val result = chat(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = listOf(ChatMessage("user", "Ответь одним словом: ок")),
            temperature = 0.0,
            maxTokens = 16,
        )
        return "Ключ рабочий, модель ${result.model}"
    }

    private fun extractError(raw: String): String? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val err = obj["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.content
            null -> obj["message"]?.jsonPrimitive?.content
            else -> err.jsonPrimitive.content
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 3
        private const val BACKOFF_MS = 1500L
    }
}
