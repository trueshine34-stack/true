package com.trueshine.tgposter.data.backup

import com.trueshine.tgposter.core.SecretStore
import com.trueshine.tgposter.data.SettingsStore
import com.trueshine.tgposter.data.db.AccountEntity
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.RubricEntity
import com.trueshine.tgposter.data.db.ScheduleEntity
import com.trueshine.tgposter.data.db.SourceEntity
import com.trueshine.tgposter.data.repo.PostingRepository
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
data class BackupSource(val title: String = "", val url: String, val enabled: Boolean = true)

@Serializable
data class BackupChannel(
    val title: String,
    val chatId: String,
    val enabled: Boolean = true,
    val timeZone: String = "Europe/Moscow",
    val instructions: String = "",
    val audience: String = "",
    val tone: String = "",
    val language: String = "русский",
    val minChars: Int = 400,
    val maxChars: Int = 900,
    val emojiLevel: Int = 1,
    val hashtags: String = "",
    val ctaText: String = "",
    val signature: String = "",
    val bannedTopics: String = "",
    val extraRules: String = "",
    val avoidRepeatLastN: Int = 20,
    val parseMode: String = "HTML",
    val disablePreview: Boolean = true,
    val silent: Boolean = false,
    val protectContent: Boolean = false,
    val pinAfterPost: Boolean = false,
    val moderation: Boolean = false,
    val generateAheadMinutes: Int = 30,
    val model: String = "",
    val temperature: Float = 0.9f,
    val schedules: List<BackupSchedule> = emptyList(),
    val rubrics: List<BackupRubric> = emptyList(),
    val sources: List<BackupSource> = emptyList(),
)

@Serializable
data class BackupAccount(
    val name: String,
    val botUsername: String? = null,
    val enabled: Boolean = true,
    /** Заполняется только если пользователь явно согласился выгрузить ключи. */
    val token: String? = null,
    val channels: List<BackupChannel> = emptyList(),
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

/** Экспорт и импорт конфигурации: аккаунты, каналы, расписания, рубрики, источники. */
class BackupRepository(
    private val repo: PostingRepository,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val json: Json,
) {

    private val pretty = Json(json) { prettyPrint = true }

    suspend fun export(includeSecrets: Boolean): String = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val accounts = repo.accounts.getAll().map { account ->
            val channels = repo.channels.observeByAccount(account.id).first().map { channel ->
                BackupChannel(
                    title = channel.title,
                    chatId = channel.chatId,
                    enabled = channel.enabled,
                    timeZone = channel.timeZone,
                    instructions = channel.instructions,
                    audience = channel.audience,
                    tone = channel.tone,
                    language = channel.language,
                    minChars = channel.minChars,
                    maxChars = channel.maxChars,
                    emojiLevel = channel.emojiLevel,
                    hashtags = channel.hashtags,
                    ctaText = channel.ctaText,
                    signature = channel.signature,
                    bannedTopics = channel.bannedTopics,
                    extraRules = channel.extraRules,
                    avoidRepeatLastN = channel.avoidRepeatLastN,
                    parseMode = channel.parseMode,
                    disablePreview = channel.disablePreview,
                    silent = channel.silent,
                    protectContent = channel.protectContent,
                    pinAfterPost = channel.pinAfterPost,
                    moderation = channel.moderation,
                    generateAheadMinutes = channel.generateAheadMinutes,
                    model = channel.model,
                    temperature = channel.temperature,
                    schedules = repo.schedules.observeByChannel(channel.id).first().map {
                        BackupSchedule(
                            it.label, it.enabled, it.mode, it.timesCsv, it.daysMask,
                            it.intervalMinutes, it.windowStart, it.windowEnd, it.jitterMinutes
                        )
                    },
                    rubrics = repo.rubrics.observeByChannel(channel.id).first().map {
                        BackupRubric(it.title, it.prompt, it.weight, it.enabled)
                    },
                    sources = repo.sources.observeByChannel(channel.id).first().map {
                        BackupSource(it.title, it.url, it.enabled)
                    },
                )
            }
            BackupAccount(
                name = account.name,
                botUsername = account.botUsername,
                enabled = account.enabled,
                token = if (includeSecrets) repo.tokenFor(account.id) else null,
                channels = channels,
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
        var channelCount = 0

        file.deepSeekApiKey?.takeIf { it.isNotBlank() }?.let {
            secrets.put(SecretStore.DEEPSEEK_API_KEY, it)
            settings.setHasDeepSeekKey(true)
        }
        if (file.deepSeekBaseUrl.isNotBlank()) settings.setBaseUrl(file.deepSeekBaseUrl)
        if (file.deepSeekModel.isNotBlank()) settings.setModel(file.deepSeekModel)

        for (account in file.accounts) {
            val accountId = repo.accounts.insert(
                AccountEntity(
                    name = account.name,
                    botUsername = account.botUsername,
                    enabled = account.enabled,
                    tokenRef = SecretStore.accountTokenRef(0),
                )
            )
            repo.accounts.byId(accountId)?.let {
                repo.accounts.update(it.copy(tokenRef = SecretStore.accountTokenRef(accountId)))
            }
            account.token?.takeIf { it.isNotBlank() }?.let { repo.updateAccountToken(accountId, it) }

            for (channel in account.channels) {
                val channelId = repo.channels.insert(
                    ChannelEntity(
                        accountId = accountId,
                        title = channel.title,
                        chatId = channel.chatId,
                        enabled = channel.enabled,
                        timeZone = channel.timeZone,
                        instructions = channel.instructions,
                        audience = channel.audience,
                        tone = channel.tone,
                        language = channel.language,
                        minChars = channel.minChars,
                        maxChars = channel.maxChars,
                        emojiLevel = channel.emojiLevel,
                        hashtags = channel.hashtags,
                        ctaText = channel.ctaText,
                        signature = channel.signature,
                        bannedTopics = channel.bannedTopics,
                        extraRules = channel.extraRules,
                        avoidRepeatLastN = channel.avoidRepeatLastN,
                        parseMode = channel.parseMode,
                        disablePreview = channel.disablePreview,
                        silent = channel.silent,
                        protectContent = channel.protectContent,
                        pinAfterPost = channel.pinAfterPost,
                        moderation = channel.moderation,
                        generateAheadMinutes = channel.generateAheadMinutes,
                        model = channel.model,
                        temperature = channel.temperature,
                    )
                )
                channelCount++
                channel.schedules.forEach {
                    repo.schedules.upsert(
                        ScheduleEntity(
                            channelId = channelId,
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
                channel.rubrics.forEach {
                    repo.rubrics.upsert(
                        RubricEntity(
                            channelId = channelId,
                            title = it.title,
                            prompt = it.prompt,
                            weight = it.weight,
                            enabled = it.enabled,
                        )
                    )
                }
                channel.sources.forEach {
                    repo.sources.upsert(
                        SourceEntity(channelId = channelId, title = it.title, url = it.url, enabled = it.enabled)
                    )
                }
            }
        }
        "Импортировано: ${file.accounts.size} аккаунтов, $channelCount каналов"
    }
}
