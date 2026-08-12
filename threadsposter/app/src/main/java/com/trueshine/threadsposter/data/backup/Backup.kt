package com.trueshine.threadsposter.data.backup

import com.trueshine.threadsposter.core.SecretStore
import com.trueshine.threadsposter.data.SettingsStore
import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.QueryEntity
import com.trueshine.threadsposter.data.db.RubricEntity
import com.trueshine.threadsposter.data.db.ScheduleEntity
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupSchedule(
    val label: String = "",
    val enabled: Boolean = true,
    val mode: String = "DAILY_TIMES",
    val timesCsv: String = "10:00",
    val daysMask: Int = 127,
    val intervalMinutes: Int = 180,
    val windowStart: String = "09:00",
    val windowEnd: String = "22:00",
    val jitterMinutes: Int = 0,
)

@Serializable
data class BackupRubric(
    val title: String,
    val prompt: String = "",
    val weight: Int = 1,
    val enabled: Boolean = true,
)

@Serializable
data class BackupQuery(
    val query: String,
    val searchType: String = "RECENT",
    val enabled: Boolean = true,
    val checkIntervalMinutes: Int = 60,
    val relevanceBrief: String = "",
    val replyInstructions: String = "",
    val excludeKeywords: String = "",
    val minTextLength: Int = 60,
    val skipReplies: Boolean = true,
    val minScore: Int = 7,
    val autoReply: Boolean = false,
    val maxRepliesPerRun: Int = 3,
)

@Serializable
data class BackupAccount(
    val displayName: String,
    val username: String = "",
    val threadsUserId: String = "",
    val enabled: Boolean = true,
    val timeZone: String = "Europe/Moscow",
    val instructions: String = "",
    val audience: String = "",
    val tone: String = "",
    val language: String = "русский",
    val minChars: Int = 150,
    val maxChars: Int = 480,
    val emojiLevel: Int = 1,
    val hashtags: String = "",
    val ctaText: String = "",
    val signature: String = "",
    val bannedTopics: String = "",
    val extraRules: String = "",
    val avoidRepeatLastN: Int = 20,
    val model: String = "",
    val temperature: Float = 0.9f,
    val replyControl: String = "everyone",
    val moderation: Boolean = false,
    val generateAheadMinutes: Int = 30,
    val splitLongPosts: Boolean = true,
    val autoReplyToMyThreads: Boolean = false,
    val replyInstructions: String = "",
    val moderateReplies: Boolean = true,
    val dailyPostLimit: Int = 20,
    val dailyReplyLimit: Int = 30,
    val minMinutesBetweenReplies: Int = 4,
    /** Заполняется только если пользователь явно согласился выгрузить ключи. */
    val token: String? = null,
    val schedules: List<BackupSchedule> = emptyList(),
    val rubrics: List<BackupRubric> = emptyList(),
    val queries: List<BackupQuery> = emptyList(),
)

@Serializable
data class BackupFile(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val containsSecrets: Boolean = false,
    val deepSeekBaseUrl: String = "",
    val deepSeekModel: String = "",
    val deepSeekApiKey: String? = null,
    val accounts: List<BackupAccount> = emptyList(),
)

/** Экспорт и импорт конфигурации: аккаунты, расписания, рубрики, поисковые запросы. */
class BackupRepository(
    private val repo: ThreadsRepository,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val json: Json,
) {

    private val pretty = Json(json) { prettyPrint = true }

    suspend fun export(includeSecrets: Boolean): String = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val accounts = repo.accounts.getAll().map { account ->
            BackupAccount(
                displayName = account.displayName,
                username = account.username,
                threadsUserId = account.threadsUserId,
                enabled = account.enabled,
                timeZone = account.timeZone,
                instructions = account.instructions,
                audience = account.audience,
                tone = account.tone,
                language = account.language,
                minChars = account.minChars,
                maxChars = account.maxChars,
                emojiLevel = account.emojiLevel,
                hashtags = account.hashtags,
                ctaText = account.ctaText,
                signature = account.signature,
                bannedTopics = account.bannedTopics,
                extraRules = account.extraRules,
                avoidRepeatLastN = account.avoidRepeatLastN,
                model = account.model,
                temperature = account.temperature,
                replyControl = account.replyControl,
                moderation = account.moderation,
                generateAheadMinutes = account.generateAheadMinutes,
                splitLongPosts = account.splitLongPosts,
                autoReplyToMyThreads = account.autoReplyToMyThreads,
                replyInstructions = account.replyInstructions,
                moderateReplies = account.moderateReplies,
                dailyPostLimit = account.dailyPostLimit,
                dailyReplyLimit = account.dailyReplyLimit,
                minMinutesBetweenReplies = account.minMinutesBetweenReplies,
                token = if (includeSecrets) repo.tokenFor(account.id) else null,
                schedules = repo.schedules.observeByAccount(account.id).first().map {
                    BackupSchedule(
                        it.label, it.enabled, it.mode, it.timesCsv, it.daysMask,
                        it.intervalMinutes, it.windowStart, it.windowEnd, it.jitterMinutes
                    )
                },
                rubrics = repo.rubrics.observeByAccount(account.id).first().map {
                    BackupRubric(it.title, it.prompt, it.weight, it.enabled)
                },
                queries = repo.queries.observeByAccount(account.id).first().map {
                    BackupQuery(
                        it.query, it.searchType, it.enabled, it.checkIntervalMinutes,
                        it.relevanceBrief, it.replyInstructions, it.excludeKeywords,
                        it.minTextLength, it.skipReplies, it.minScore, it.autoReply,
                        it.maxRepliesPerRun
                    )
                },
            )
        }

        pretty.encodeToString(
            BackupFile.serializer(),
            BackupFile(
                containsSecrets = includeSecrets,
                deepSeekBaseUrl = cfg.deepSeekBaseUrl,
                deepSeekModel = cfg.deepSeekModel,
                deepSeekApiKey = if (includeSecrets) secrets.get(SecretStore.DEEPSEEK_API_KEY) else null,
                accounts = accounts,
            )
        )
    }

    /** Импорт добавляет данные к существующим, ничего не удаляя. */
    suspend fun restore(content: String): String = withContext(Dispatchers.IO) {
        val file = json.decodeFromString(BackupFile.serializer(), content)

        file.deepSeekApiKey?.takeIf { it.isNotBlank() }?.let {
            secrets.put(SecretStore.DEEPSEEK_API_KEY, it)
            settings.setHasDeepSeekKey(true)
        }
        if (file.deepSeekBaseUrl.isNotBlank()) settings.setBaseUrl(file.deepSeekBaseUrl)
        if (file.deepSeekModel.isNotBlank()) settings.setModel(file.deepSeekModel)

        var queryCount = 0
        for (account in file.accounts) {
            val accountId = repo.accounts.insert(
                AccountEntity(
                    displayName = account.displayName,
                    username = account.username,
                    threadsUserId = account.threadsUserId,
                    enabled = account.enabled,
                    timeZone = account.timeZone,
                    instructions = account.instructions,
                    audience = account.audience,
                    tone = account.tone,
                    language = account.language,
                    minChars = account.minChars,
                    maxChars = account.maxChars,
                    emojiLevel = account.emojiLevel,
                    hashtags = account.hashtags,
                    ctaText = account.ctaText,
                    signature = account.signature,
                    bannedTopics = account.bannedTopics,
                    extraRules = account.extraRules,
                    avoidRepeatLastN = account.avoidRepeatLastN,
                    model = account.model,
                    temperature = account.temperature,
                    replyControl = account.replyControl,
                    moderation = account.moderation,
                    generateAheadMinutes = account.generateAheadMinutes,
                    splitLongPosts = account.splitLongPosts,
                    autoReplyToMyThreads = account.autoReplyToMyThreads,
                    replyInstructions = account.replyInstructions,
                    moderateReplies = account.moderateReplies,
                    dailyPostLimit = account.dailyPostLimit,
                    dailyReplyLimit = account.dailyReplyLimit,
                    minMinutesBetweenReplies = account.minMinutesBetweenReplies,
                )
            )
            account.token?.takeIf { it.isNotBlank() }?.let { repo.saveToken(accountId, it, null) }

            account.schedules.forEach {
                repo.schedules.upsert(
                    ScheduleEntity(
                        accountId = accountId,
                        label = it.label,
                        enabled = it.enabled,
                        mode = it.mode,
                        timesCsv = it.timesCsv,
                        daysMask = it.daysMask,
                        intervalMinutes = it.intervalMinutes,
                        windowStart = it.windowStart,
                        windowEnd = it.windowEnd,
                        jitterMinutes = it.jitterMinutes,
                    )
                )
            }
            account.rubrics.forEach {
                repo.rubrics.upsert(
                    RubricEntity(
                        accountId = accountId,
                        title = it.title,
                        prompt = it.prompt,
                        weight = it.weight,
                        enabled = it.enabled,
                    )
                )
            }
            account.queries.forEach {
                repo.queries.upsert(
                    QueryEntity(
                        accountId = accountId,
                        query = it.query,
                        searchType = it.searchType,
                        enabled = it.enabled,
                        checkIntervalMinutes = it.checkIntervalMinutes,
                        relevanceBrief = it.relevanceBrief,
                        replyInstructions = it.replyInstructions,
                        excludeKeywords = it.excludeKeywords,
                        minTextLength = it.minTextLength,
                        skipReplies = it.skipReplies,
                        minScore = it.minScore,
                        autoReply = it.autoReply,
                        maxRepliesPerRun = it.maxRepliesPerRun,
                    )
                )
                queryCount++
            }
        }
        "Импортировано: ${file.accounts.size} аккаунтов, $queryCount поисковых запросов"
    }
}
