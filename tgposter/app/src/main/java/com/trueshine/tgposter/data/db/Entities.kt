package com.trueshine.tgposter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Телеграм-аккаунт = отдельный бот (его токен). Один бот может вести много каналов. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Ключ в SecretStore, сам токен в БД не хранится. */
    val tokenRef: String = "",
    val botUsername: String? = null,
    val botId: Long? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckAt: Long? = null,
    val lastCheckOk: Boolean? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val title: String,
    /** @username или числовой -100... */
    val chatId: String,
    val enabled: Boolean = true,
    val timeZone: String = "Europe/Moscow",

    // --- Контент: инструкции для DeepSeek под конкретный канал ---
    val instructions: String = "",
    val audience: String = "",
    val tone: String = "дружелюбный, экспертный",
    val language: String = "русский",
    val minChars: Int = 400,
    val maxChars: Int = 900,
    /** 0 — без эмодзи, 1 — умеренно, 2 — активно. */
    val emojiLevel: Int = 1,
    val hashtags: String = "",
    val ctaText: String = "",
    val signature: String = "",
    val bannedTopics: String = "",
    val extraRules: String = "",
    /** Учитывать темы последних N постов, чтобы не повторяться. */
    val avoidRepeatLastN: Int = 20,

    // --- Публикация ---
    val parseMode: String = "HTML",
    val disablePreview: Boolean = true,
    val silent: Boolean = false,
    val protectContent: Boolean = false,
    val pinAfterPost: Boolean = false,

    // --- Режим работы ---
    /** true — пост не уходит сам, ждёт подтверждения в очереди. */
    val moderation: Boolean = false,
    val generateAheadMinutes: Int = 30,
    val model: String = "",
    val temperature: Float = 0.9f,

    val createdAt: Long = System.currentTimeMillis(),
)

/** Рубрика: тематический блок, из которого выбирается тема очередного поста. */
@Entity(
    tableName = "rubrics",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("channelId")]
)
data class RubricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val title: String,
    val prompt: String = "",
    /** Чем больше вес, тем чаще рубрика выпадает. */
    val weight: Int = 1,
    val enabled: Boolean = true,
    val lastUsedAt: Long? = null,
)

/** RSS-источник, который DeepSeek анализирует перед генерацией. */
@Entity(
    tableName = "sources",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("channelId")]
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val title: String = "",
    val url: String,
    val enabled: Boolean = true,
    val lastFetchAt: Long? = null,
    val lastError: String? = null,
)

object ScheduleMode {
    const val DAILY_TIMES = "DAILY_TIMES"
    const val INTERVAL = "INTERVAL"
}

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("channelId")]
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val label: String = "",
    val enabled: Boolean = true,
    val mode: String = ScheduleMode.DAILY_TIMES,
    /** Для DAILY_TIMES: "09:00,14:30,20:00". */
    val timesCsv: String = "10:00",
    /** Битовая маска дней недели: бит 0 = понедельник … бит 6 = воскресенье. */
    val daysMask: Int = 0b1111111,
    /** Для INTERVAL: шаг в минутах внутри окна. */
    val intervalMinutes: Int = 180,
    val windowStart: String = "09:00",
    val windowEnd: String = "22:00",
    /** Случайный сдвиг ±N минут, чтобы посты не выходили секунда в секунду. */
    val jitterMinutes: Int = 0,
)

object PostStatus {
    const val QUEUED = "QUEUED"
    const val GENERATING = "GENERATING"
    const val NEEDS_APPROVAL = "NEEDS_APPROVAL"
    const val READY = "READY"
    const val PUBLISHING = "PUBLISHING"
    const val PUBLISHED = "PUBLISHED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val CANCELED = "CANCELED"

    val active = listOf(QUEUED, GENERATING, NEEDS_APPROVAL, READY, PUBLISHING)

    fun title(status: String) = when (status) {
        QUEUED -> "В очереди"
        GENERATING -> "Генерация"
        NEEDS_APPROVAL -> "На модерации"
        READY -> "Готов"
        PUBLISHING -> "Публикуется"
        PUBLISHED -> "Опубликован"
        FAILED -> "Ошибка"
        SKIPPED -> "Пропущен"
        CANCELED -> "Отменён"
        else -> status
    }
}

@Entity(
    tableName = "posts",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("channelId"),
        Index("status"),
        Index("scheduledAt"),
        // Защита от дублей при пересчёте расписания.
        Index(value = ["channelId", "scheduleId", "scheduledAt"], unique = true),
    ]
)
data class PostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val scheduleId: Long = 0,
    val rubricId: Long = 0,
    val status: String = PostStatus.QUEUED,
    val scheduledAt: Long,
    val topic: String = "",
    val text: String = "",
    val imageUrl: String? = null,
    val promptSnapshot: String = "",
    val model: String = "",
    val tokensUsed: Int = 0,
    val manual: Boolean = false,
    val attempts: Int = 0,
    val lastError: String? = null,
    val tgMessageId: Long? = null,
    val publishedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object LogLevel {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
}

@Entity(tableName = "logs", indices = [Index("ts")])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val level: String = LogLevel.INFO,
    val tag: String = "",
    val message: String = "",
    val channelId: Long? = null,
)
