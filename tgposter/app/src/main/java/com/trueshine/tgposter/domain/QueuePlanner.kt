package com.trueshine.tgposter.domain

import com.trueshine.tgposter.data.db.LogLevel
import com.trueshine.tgposter.data.db.PostEntity
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.data.repo.PostingRepository

/**
 * Раскладывает расписания каналов в конкретные посты-заготовки.
 *
 * Слоты считаются детерминированно, а уникальный индекс
 * (channelId, scheduleId, scheduledAt) вместе с INSERT OR IGNORE гарантирует,
 * что повторный проход не создаст дублей.
 */
class QueuePlanner(private val repo: PostingRepository) {

    suspend fun plan(horizonHours: Int = DEFAULT_HORIZON_HOURS): Int {
        val now = System.currentTimeMillis()
        val until = now + horizonHours * 3_600_000L
        var created = 0

        for (channel in repo.channels.getEnabled()) {
            val account = repo.accounts.byId(channel.accountId) ?: continue
            if (!account.enabled) continue

            for (schedule in repo.schedules.enabledByChannel(channel.id)) {
                val slots = SlotCalculator.slots(schedule, channel.timeZone, now, until)
                for (slot in slots) {
                    val id = repo.posts.insertIgnore(
                        PostEntity(
                            channelId = channel.id,
                            scheduleId = schedule.id,
                            status = PostStatus.QUEUED,
                            scheduledAt = slot,
                        )
                    )
                    if (id > 0) created++
                }
            }
        }
        if (created > 0) {
            repo.log(LogLevel.INFO, "planner", "Запланировано новых постов: $created")
        }
        return created
    }

    /** Пересобрать очередь канала после изменения расписания. */
    suspend fun replanChannel(channelId: Long) {
        repo.posts.clearQueuedForChannel(channelId)
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
