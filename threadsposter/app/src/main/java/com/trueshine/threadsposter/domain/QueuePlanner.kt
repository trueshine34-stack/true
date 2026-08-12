package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.data.repo.ThreadsRepository

/**
 * Раскладывает расписания аккаунтов в конкретные посты-заготовки.
 *
 * Слоты считаются детерминированно, а уникальный индекс
 * (accountId, scheduleId, scheduledAt) вместе с INSERT OR IGNORE не даёт
 * повторному проходу создать дубли.
 */
class QueuePlanner(private val repo: ThreadsRepository) {

    suspend fun plan(horizonHours: Int = DEFAULT_HORIZON_HOURS): Int {
        val now = System.currentTimeMillis()
        val until = now + horizonHours * 3_600_000L
        var created = 0

        for (account in repo.accounts.getEnabled()) {
            for (schedule in repo.schedules.enabledByAccount(account.id)) {
                val slots = SlotCalculator.slots(schedule, account.timeZone, now, until)
                for (slot in slots) {
                    val id = repo.posts.insertIgnore(
                        PostEntity(
                            accountId = account.id,
                            scheduleId = schedule.id,
                            kind = PostKind.POST,
                            status = PostStatus.QUEUED,
                            scheduledAt = slot,
                        )
                    )
                    if (id > 0) created++
                }
            }
        }
        if (created > 0) repo.log(LogLevel.INFO, "planner", "Запланировано новых постов: $created")
        return created
    }

    /** Пересобрать очередь аккаунта после изменения расписания. */
    suspend fun replanAccount(accountId: Long) {
        repo.posts.clearQueuedForAccount(accountId)
        plan()
    }

    /** Просроченные слоты: старые снимаем, свежие публикуем как есть. */
    suspend fun expireStale(): Int {
        val now = System.currentTimeMillis()
        val stale = repo.posts.dueByStatuses(
            listOf(PostStatus.QUEUED, PostStatus.READY, PostStatus.NEEDS_APPROVAL),
            now - STALE_AFTER_MS
        )
        for (post in stale) {
            repo.posts.update(
                post.copy(
                    status = PostStatus.SKIPPED,
                    lastError = "Слот просрочен более чем на ${STALE_AFTER_MS / 3_600_000} ч",
                    updatedAt = now,
                )
            )
        }
        if (stale.isNotEmpty()) {
            repo.log(LogLevel.WARN, "planner", "Пропущено просроченных постов: ${stale.size}")
        }
        return stale.size
    }

    companion object {
        const val DEFAULT_HORIZON_HOURS = 36
        const val STALE_AFTER_MS = 6 * 3_600_000L
    }
}
