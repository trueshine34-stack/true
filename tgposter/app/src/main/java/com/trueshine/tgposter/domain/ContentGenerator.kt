package com.trueshine.tgposter.domain

import com.trueshine.tgposter.core.SecretStore
import com.trueshine.tgposter.core.TelegramText
import com.trueshine.tgposter.data.SettingsStore
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.LogLevel
import com.trueshine.tgposter.data.db.PostEntity
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.data.db.RubricEntity
import com.trueshine.tgposter.data.remote.ChatMessage
import com.trueshine.tgposter.data.remote.DeepSeekClient
import com.trueshine.tgposter.data.remote.DeepSeekException
import com.trueshine.tgposter.data.remote.FeedItem
import com.trueshine.tgposter.data.remote.RssFetcher
import com.trueshine.tgposter.data.repo.PostingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** Генерация текста поста: подбор рубрики, сбор фактуры, вызов DeepSeek, сборка. */
class ContentGenerator(
    private val repo: PostingRepository,
    private val deepSeek: DeepSeekClient,
    private val rss: RssFetcher,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val json: Json,
) {

    suspend fun generateFor(post: PostEntity, brief: String? = null): PostEntity =
        withContext(Dispatchers.IO) {
            val channel = repo.channels.byId(post.channelId)
                ?: error("Канал #${post.channelId} не найден")
            val cfg = settings.current()
            val apiKey = secrets.get(SecretStore.DEEPSEEK_API_KEY).orEmpty()
            if (apiKey.isBlank()) throw DeepSeekException("Не задан API-ключ DeepSeek — добавьте его в настройках")

            val rubric = pickRubric(channel.id, post.rubricId)
            val recentTopics = repo.posts.recentTopics(channel.id, channel.avoidRepeatLastN)
            val feed = collectSources(channel.id)

            val system = PromptBuilder.systemPrompt(channel)
            val user = PromptBuilder.userPrompt(channel, rubric, recentTopics, feed, brief)

            val result = deepSeek.chat(
                apiKey = apiKey,
                baseUrl = cfg.deepSeekBaseUrl,
                model = channel.model.ifBlank { cfg.deepSeekModel },
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = channel.temperature.toDouble(),
                maxTokens = cfg.maxTokens,
                jsonMode = true,
            )

            val parsed = parse(result.content)
            val assembled = PromptBuilder.assemble(channel, parsed.text, parsed.hashtags)
            val prepared = TelegramText.prepare(assembled, channel.parseMode)

            rubric?.let { repo.rubrics.markUsed(it.id, System.currentTimeMillis()) }
            repo.log(
                LogLevel.INFO, "generate",
                "«${channel.title}»: сгенерирован пост «${parsed.topic}» (${result.tokensUsed} токенов)",
                channel.id
            )

            post.copy(
                topic = parsed.topic.ifBlank { post.topic },
                text = prepared,
                promptSnapshot = user,
                model = result.model,
                tokensUsed = result.tokensUsed,
                rubricId = rubric?.id ?: 0L,
                status = if (channel.moderation) PostStatus.NEEDS_APPROVAL else PostStatus.READY,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

    /** Свободный запрос к модели (анализ канала, мастер инструкций). */
    suspend fun ask(prompt: String, temperature: Double = 0.7, maxTokens: Int = 1800): String =
        withContext(Dispatchers.IO) {
            val cfg = settings.current()
            val apiKey = secrets.get(SecretStore.DEEPSEEK_API_KEY).orEmpty()
            deepSeek.chat(
                apiKey = apiKey,
                baseUrl = cfg.deepSeekBaseUrl,
                model = cfg.deepSeekModel,
                messages = listOf(ChatMessage("user", prompt)),
                temperature = temperature,
                maxTokens = maxTokens,
            ).content
        }

    suspend fun analyzeChannel(channel: ChannelEntity): String {
        val topics = repo.posts.recentTopics(channel.id, 40)
        val published = repo.posts.publishedCount(channel.id)
        return ask(PromptBuilder.analysisPrompt(channel, topics, published), temperature = 0.5)
    }

    suspend fun draftInstructions(title: String, brief: String): String =
        ask(PromptBuilder.instructionWizardPrompt(title, brief), temperature = 0.8, maxTokens = 900)

    /**
     * Взвешенный выбор рубрики: чем больше вес и чем дольше рубрика не
     * использовалась, тем выше шанс — так канал не залипает на одной теме.
     */
    private suspend fun pickRubric(channelId: Long, preferredId: Long): RubricEntity? {
        val rubrics = repo.rubrics.enabledByChannel(channelId)
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

    private suspend fun collectSources(channelId: Long): List<FeedItem> {
        val sources = repo.sources.enabledByChannel(channelId)
        if (sources.isEmpty()) return emptyList()
        val items = mutableListOf<FeedItem>()
        for (source in sources.take(4)) {
            val fetched = runCatching { rss.fetch(source.url) }
            if (fetched.isSuccess) {
                items += fetched.getOrDefault(emptyList())
                repo.sources.upsert(source.copy(lastFetchAt = System.currentTimeMillis(), lastError = null))
            } else {
                val message = fetched.exceptionOrNull()?.message ?: "ошибка загрузки"
                repo.sources.upsert(source.copy(lastFetchAt = System.currentTimeMillis(), lastError = message))
                repo.log(LogLevel.WARN, "rss", "Источник ${source.url}: $message", channelId)
            }
        }
        return items.take(10)
    }

    private data class Parsed(
        val topic: String,
        val text: String,
        val hashtags: String,
        val imagePrompt: String,
    )

    /**
     * Модель просят вернуть JSON, но иногда она добавляет ```json-обёртку или
     * отвечает просто текстом — поддерживаем оба варианта.
     */
    private fun parse(raw: String): Parsed {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
            ?: return Parsed(
                topic = cleaned.lineSequence().firstOrNull()?.take(80).orEmpty(),
                text = cleaned,
                hashtags = "",
                imagePrompt = "",
            )
        fun str(key: String) = obj[key]?.jsonPrimitive?.content?.trim().orEmpty()
        val text = str("text").ifBlank { str("post") }.ifBlank { cleaned }
        return Parsed(
            topic = str("topic").ifBlank { str("title") }.take(120),
            text = text,
            hashtags = str("hashtags"),
            imagePrompt = str("image_prompt"),
        )
    }
}
