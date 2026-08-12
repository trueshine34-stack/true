package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.core.SecretStore
import com.trueshine.threadsposter.core.ThreadsText
import com.trueshine.threadsposter.data.SettingsStore
import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LeadEntity
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.data.db.QueryEntity
import com.trueshine.threadsposter.data.db.RubricEntity
import com.trueshine.threadsposter.data.remote.ChatMessage
import com.trueshine.threadsposter.data.remote.DeepSeekClient
import com.trueshine.threadsposter.data.remote.DeepSeekException
import com.trueshine.threadsposter.data.remote.ThreadsPost
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

data class Scored(val index: Int, val score: Int, val reason: String)

/** Всё, что просит текст у DeepSeek: посты, оценки находок, ответы. */
class ContentGenerator(
    private val repo: ThreadsRepository,
    private val deepSeek: DeepSeekClient,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val json: Json,
) {

    // --- Собственные посты ---

    suspend fun generateFor(post: PostEntity, brief: String? = null): PostEntity =
        withContext(Dispatchers.IO) {
            val account = repo.accounts.byId(post.accountId)
                ?: error("Аккаунт #${post.accountId} не найден")
            val cfg = settings.current()
            val apiKey = requireKey()

            val rubric = pickRubric(account.id, post.rubricId)
            val recentTopics = repo.posts.recentTopics(account.id, account.avoidRepeatLastN)

            val system = PromptBuilder.systemPrompt(account)
            val user = PromptBuilder.userPrompt(account, rubric, recentTopics, brief)

            val result = deepSeek.chat(
                apiKey = apiKey,
                baseUrl = cfg.deepSeekBaseUrl,
                model = account.model.ifBlank { cfg.deepSeekModel },
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = account.temperature.toDouble(),
                maxTokens = cfg.maxTokens,
                jsonMode = true,
            )

            val parsed = parsePost(result.content)
            val assembled = PromptBuilder.assemble(account, parsed.text, parsed.hashtags)
            val prepared = ThreadsText.prepare(assembled)
            val finalText = if (account.splitLongPosts) prepared else ThreadsText.trimTo(prepared)

            rubric?.let { repo.rubrics.markUsed(it.id, System.currentTimeMillis()) }
            repo.log(
                LogLevel.INFO, "generate",
                "«${account.displayName}»: пост «${parsed.topic}» (${result.tokensUsed} токенов)",
                account.id
            )

            post.copy(
                topic = parsed.topic.ifBlank { post.topic },
                text = finalText,
                promptSnapshot = user,
                model = result.model,
                tokensUsed = result.tokensUsed,
                rubricId = rubric?.id ?: 0L,
                status = if (account.moderation) PostStatus.NEEDS_APPROVAL else PostStatus.READY,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

    // --- Оценка находок ---

    suspend fun scoreLeads(
        account: AccountEntity,
        query: QueryEntity,
        leads: List<LeadEntity>,
    ): List<Scored> = withContext(Dispatchers.IO) {
        if (leads.isEmpty()) return@withContext emptyList()
        val cfg = settings.current()
        val result = deepSeek.chat(
            apiKey = requireKey(),
            baseUrl = cfg.deepSeekBaseUrl,
            model = account.model.ifBlank { cfg.deepSeekModel },
            messages = listOf(ChatMessage("user", PromptBuilder.scoringPrompt(account, query, leads))),
            temperature = 0.2,
            maxTokens = 1200,
            jsonMode = true,
        )
        parseScores(result.content, leads.size)
    }

    /** Возвращает текст комментария или null, если модель решила промолчать. */
    suspend fun draftReply(
        account: AccountEntity,
        query: QueryEntity?,
        lead: LeadEntity,
    ): String? = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val result = deepSeek.chat(
            apiKey = requireKey(),
            baseUrl = cfg.deepSeekBaseUrl,
            model = account.model.ifBlank { cfg.deepSeekModel },
            messages = listOf(ChatMessage("user", PromptBuilder.replyPrompt(account, query, lead))),
            temperature = account.temperature.toDouble().coerceAtMost(0.9),
            maxTokens = 500,
            jsonMode = true,
        )
        cleanReply(result.content)
    }

    suspend fun draftOwnReply(
        account: AccountEntity,
        ourPostText: String,
        reply: ThreadsPost,
    ): String? = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val result = deepSeek.chat(
            apiKey = requireKey(),
            baseUrl = cfg.deepSeekBaseUrl,
            model = account.model.ifBlank { cfg.deepSeekModel },
            messages = listOf(ChatMessage("user", PromptBuilder.ownReplyPrompt(account, ourPostText, reply))),
            temperature = 0.7,
            maxTokens = 400,
            jsonMode = true,
        )
        cleanReply(result.content)
    }

    // --- Свободные запросы ---

    suspend fun ask(prompt: String, temperature: Double = 0.7, maxTokens: Int = 1800): String =
        withContext(Dispatchers.IO) {
            val cfg = settings.current()
            deepSeek.chat(
                apiKey = requireKey(),
                baseUrl = cfg.deepSeekBaseUrl,
                model = cfg.deepSeekModel,
                messages = listOf(ChatMessage("user", prompt)),
                temperature = temperature,
                maxTokens = maxTokens,
            ).content
        }

    suspend fun analyzeAccount(account: AccountEntity): String {
        val topics = repo.posts.recentTopics(account.id, 40)
        val published = repo.posts.publishedCount(account.id)
        return ask(PromptBuilder.analysisPrompt(account, topics, published), temperature = 0.5)
    }

    suspend fun draftInstructions(name: String, brief: String): String =
        ask(PromptBuilder.instructionWizardPrompt(name, brief), temperature = 0.8, maxTokens = 900)

    // --- Внутреннее ---

    private fun requireKey(): String =
        secrets.get(SecretStore.DEEPSEEK_API_KEY)?.takeIf { it.isNotBlank() }
            ?: throw DeepSeekException("Не задан API-ключ DeepSeek — добавьте его в настройках")

    /**
     * Взвешенный выбор рубрики: чем больше вес и чем дольше рубрика не
     * использовалась, тем выше шанс.
     */
    private suspend fun pickRubric(accountId: Long, preferredId: Long): RubricEntity? {
        val rubrics = repo.rubrics.enabledByAccount(accountId)
        if (rubrics.isEmpty()) return null
        preferredId.takeIf { it > 0 }?.let { id -> rubrics.firstOrNull { it.id == id }?.let { return it } }

        val now = System.currentTimeMillis()
        val weights = rubrics.map { rubric ->
            val idleDays = ((now - (rubric.lastUsedAt ?: 0L)) / 86_400_000.0).coerceIn(0.0, 14.0)
            rubric.weight.coerceAtLeast(1) * (1.0 + idleDays)
        }
        val total = weights.sum()
        if (total <= 0.0) return rubrics.random()
        var point = Random.nextDouble(total)
        rubrics.forEachIndexed { index, rubric ->
            point -= weights[index]
            if (point <= 0) return rubric
        }
        return rubrics.last()
    }

    private data class ParsedPost(val topic: String, val text: String, val hashtags: String)

    private fun parsePost(raw: String): ParsedPost {
        val obj = asJsonObject(raw)
            ?: return ParsedPost(
                topic = raw.lineSequence().firstOrNull()?.take(80).orEmpty(),
                text = raw.trim(),
                hashtags = "",
            )
        fun str(key: String) = obj[key]?.jsonPrimitive?.content?.trim().orEmpty()
        return ParsedPost(
            topic = str("topic").ifBlank { str("title") }.take(120),
            text = str("text").ifBlank { str("post") }.ifBlank { raw.trim() },
            hashtags = str("hashtags"),
        )
    }

    private fun parseScores(raw: String, size: Int): List<Scored> {
        val obj = asJsonObject(raw) ?: return emptyList()
        val array = obj["results"]?.jsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val item = element.jsonObject
                val index = item["i"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@runCatching null
                if (index !in 0 until size) return@runCatching null
                Scored(
                    index = index,
                    score = item["score"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 10) ?: 1,
                    reason = item["reason"]?.jsonPrimitive?.content?.trim().orEmpty().take(200),
                )
            }.getOrNull()
        }
    }

    private fun cleanReply(raw: String): String? {
        val obj = asJsonObject(raw)
        val text = obj?.get("text")?.jsonPrimitive?.content ?: raw
        val prepared = ThreadsText.prepare(text)
        if (prepared.isBlank()) return null
        return ThreadsText.trimTo(prepared, REPLY_LIMIT)
    }

    /** Модель иногда добавляет ```json-обёртку вопреки просьбе вернуть чистый JSON. */
    private fun asJsonObject(raw: String): kotlinx.serialization.json.JsonObject? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
    }

    private companion object {
        const val REPLY_LIMIT = 400
    }
}
