package com.trueshine.threadsposter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Аккаунт Threads.
 *
 * В Threads у аккаунта одна лента, поэтому настройки контента живут прямо здесь,
 * а не в отдельной сущности «канал», как это было бы для Telegram.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val username: String = "",
    /** threads-user-id, он же подставляется в пути эндпоинтов. */
    val threadsUserId: String = "",
    /** Ключ в SecretStore; сам токен в базе не хранится. */
    val tokenRef: String = "",
    val tokenObtainedAt: Long? = null,
    val tokenExpiresAt: Long? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckAt: Long? = null,
    val lastCheckOk: Boolean? = null,
    val lastError: String? = null,
    val timeZone: String = "Europe/Moscow",

    // --- Контент ---
    val instructions: String = "",
    val audience: String = "",
    val tone: String = "живой, по делу",
    val language: String = "русский",
    val minChars: Int = 150,
    val maxChars: Int = 480,
    /** 0 — без эмодзи, 1 — умеренно, 2 — активно. */
    val emojiLevel: Int = 1,
    val hashtags: String = "",
    val ctaText: String = "",
    val signature: String = "",
    val bannedTopics: String = "",
    val extraRules: String = "",
    val avoidRepeatLastN: Int = 20,
    val model: String = "",
    val temperature: Float = 0.9f,

    // --- Публикация ---
    /** everyone | accounts_you_follow | mentioned_only */
    val replyControl: String = "everyone",
    val moderation: Boolean = false,
    val generateAheadMinutes: Int = 30,
    /** Длинный текст разбивается в цепочку постов-ответов вместо обрезки. */
    val splitLongPosts: Boolean = true,

    // --- Ответы ---
    val autoReplyToMyThreads: Boolean = false,
    val replyInstructions: String = "",
    val moderateReplies: Boolean = true,

    // --- Дневные лимиты (Threads разрешает 250 публикаций в сутки) ---
    val dailyPostLimit: Int = 20,
    val dailyReplyLimit: Int = 30,
    val minMinutesBetweenReplies: Int = 4,
)

/** Рубрика: тематический блок, из которого выбирается тема очередного поста. */
@Entity(
    tableName = "rubrics",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class RubricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val title: String,
    val prompt: String = "",
    val weight: Int = 1,
    val enabled: Boolean = true,
    val lastUsedAt: Long? = null,
)

object ScheduleMode {
    const val DAILY_TIMES = "DAILY_TIMES"
    const val INTERVAL = "INTERVAL"
}

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val label: String = "",
    val enabled: Boolean = true,
    val mode: String = ScheduleMode.DAILY_TIMES,
    val timesCsv: String = "10:00",
    /** Битовая маска дней недели: бит 0 = понедельник … бит 6 = воскресенье. */
    val daysMask: Int = 0b1111111,
    val intervalMinutes: Int = 180,
    val windowStart: String = "09:00",
    val windowEnd: String = "22:00",
    val jitterMinutes: Int = 0,
)

object PostKind {
    const val POST = "POST"
    const val REPLY = "REPLY"
}

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
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("accountId"),
        Index("status"),
        Index("scheduledAt"),
        // Защита от дублей при пересчёте расписания.
        Index(value = ["accountId", "scheduleId", "scheduledAt"], unique = true),
    ]
)
data class PostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val scheduleId: Long = 0,
    val rubricId: Long = 0,
    val kind: String = PostKind.POST,
    val status: String = PostStatus.QUEUED,
    val scheduledAt: Long,
    val topic: String = "",
    val text: String = "",
    val imageUrl: String? = null,

    /** Для ответов: id чужого или своего поста в Threads. */
    val replyToId: String? = null,
    val replyToAuthor: String = "",
    val replyToText: String = "",
    val replyToPermalink: String = "",
    /** Ссылка на найденный поиском пост, если ответ родился оттуда. */
    val leadId: Long = 0,

    val promptSnapshot: String = "",
    val model: String = "",
    val tokensUsed: Int = 0,
    val manual: Boolean = false,
    val attempts: Int = 0,
    val lastError: String? = null,
    val threadsMediaId: String? = null,
    val permalink: String? = null,
    val publishedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object SearchType {
    const val TOP = "TOP"
    const val RECENT = "RECENT"
}

/** Сохранённый поисковый запрос: что ищем и как на это отвечаем. */
@Entity(
    tableName = "queries",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class QueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val query: String,
    val searchType: String = SearchType.RECENT,
    val enabled: Boolean = true,
    val checkIntervalMinutes: Int = 60,

    /** Что именно ищем — уточнение для модели при оценке релевантности. */
    val relevanceBrief: String = "",
    val replyInstructions: String = "",
    val excludeKeywords: String = "",
    val minTextLength: Int = 60,
    val skipReplies: Boolean = true,

    /** Порог релевантности 1–10; ниже него пост игнорируется. */
    val minScore: Int = 7,
    /** false — только собирать и показывать, ничего не отвечать. */
    val autoReply: Boolean = false,
    val maxRepliesPerRun: Int = 3,

    val lastRunAt: Long? = null,
    val lastError: String? = null,
    val foundTotal: Int = 0,
    val repliedTotal: Int = 0,
)

object LeadStatus {
    const val NEW = "NEW"
    const val SKIPPED = "SKIPPED"
    const val DRAFTED = "DRAFTED"
    const val QUEUED = "QUEUED"
    const val REPLIED = "REPLIED"
    const val FAILED = "FAILED"

    fun title(status: String) = when (status) {
        NEW -> "Новый"
        SKIPPED -> "Пропущен"
        DRAFTED -> "Черновик ответа"
        QUEUED -> "Ответ в очереди"
        REPLIED -> "Отвечено"
        FAILED -> "Ошибка"
        else -> status
    }
}

/** Найденный поиском чужой пост — кандидат на ответ. */
@Entity(
    tableName = "leads",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("accountId"),
        Index("status"),
        // Один и тот же пост не должен попадать в список дважды.
        Index(value = ["accountId", "threadsPostId"], unique = true),
    ]
)
data class LeadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val queryId: Long = 0,
    val threadsPostId: String,
    val permalink: String = "",
    val authorUsername: String = "",
    val text: String = "",
    val postedAt: Long? = null,
    val score: Int = 0,
    val reason: String = "",
    val status: String = LeadStatus.NEW,
    val replyText: String = "",
    val replyPostId: Long = 0,
    val repliedAt: Long? = null,
    val lastError: String? = null,
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
    val accountId: Long? = null,
)
