package com.trueshine.threadsposter.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.trueshine.threadsposter.AppContainer
import com.trueshine.threadsposter.ThreadsPosterApp
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.domain.PublishOutcome

private val Context.container: AppContainer
    get() = (applicationContext as ThreadsPosterApp).container

/**
 * Сердце автопилота: раз в 15 минут достраивает очередь, заранее генерирует
 * тексты, публикует всё, чему подошло время, прогоняет поисковые запросы и
 * продлевает токены.
 *
 * WorkManager не гарантирует точную минуту запуска, поэтому для ближайших постов
 * дополнительно ставится одноразовая задача с точной задержкой, а этот проход
 * работает страховкой на случай, если устройство спало.
 */
class AutopilotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val repo = container.repository
        val settings = container.settings.current()

        if (!settings.autopilot) {
            repo.log(LogLevel.INFO, "autopilot", "Автопилот выключен, проход пропущен")
            return Result.success()
        }

        return try {
            container.tokenKeeper.refreshExpiring()
            container.planner.plan()
            container.planner.expireStale()
            generateUpcoming(container)
            publishDue(container)
            scheduleExactPublishes(container, settings.wifiOnly)

            if (settings.searchEnabled) {
                runCatching { container.leadFinder.runDueQueries() }
                    .onFailure { repo.logError("search", "Прогон поиска: ${it.message}") }
                runCatching { container.inbox.run() }
                    .onFailure { repo.logError("inbox", "Разбор комментариев: ${it.message}") }
            }

            repo.cleanup(settings.keepPostsDays, settings.keepLeadsDays, settings.keepLogsDays)
            Result.success()
        } catch (e: Exception) {
            repo.logError("autopilot", "Сбой прохода: ${e.message}")
            Result.retry()
        }
    }

    /** Генерируем тексты заранее, чтобы в момент публикации сеть уже была не нужна. */
    private suspend fun generateUpcoming(container: AppContainer) {
        val repo = container.repository
        val now = System.currentTimeMillis()
        val candidates = repo.posts
            .dueByStatus(PostStatus.QUEUED, now + MAX_LOOKAHEAD_MS)
            .take(MAX_GENERATIONS_PER_RUN)

        for (post in candidates) {
            val account = repo.accounts.byId(post.accountId) ?: continue
            val aheadMs = account.generateAheadMinutes.coerceIn(1, 720) * 60_000L
            if (post.scheduledAt - now > aheadMs) continue

            repo.updateStatus(post, PostStatus.GENERATING)
            try {
                val generated = container.generator.generateFor(post)
                repo.posts.update(generated)
                if (generated.status == PostStatus.NEEDS_APPROVAL) {
                    notifyApproval(container, account.displayName, generated)
                }
            } catch (e: Exception) {
                repo.markFailed(post.copy(status = PostStatus.QUEUED), "Генерация не удалась: ${e.message}")
                notifyError(container, "Ошибка генерации", "«${account.displayName}»: ${e.message}", post.id)
            }
        }
    }

    private suspend fun publishDue(container: AppContainer) {
        val repo = container.repository
        val now = System.currentTimeMillis()
        val due = repo.posts.dueByStatus(PostStatus.READY, now + PUBLISH_WINDOW_MS)
        for (post in due) {
            when (val outcome = container.publisher.publish(post)) {
                is PublishOutcome.Success -> Unit
                is PublishOutcome.Deferred -> {
                    // Сдвигаем слот, чтобы пост не считался просроченным.
                    repo.posts.update(
                        post.copy(
                            status = PostStatus.READY,
                            scheduledAt = now + outcome.retryAfterMs,
                            lastError = outcome.reason,
                            updatedAt = now,
                        )
                    )
                    repo.log(LogLevel.WARN, "publish", "Отложено: ${outcome.reason}", post.accountId)
                }
                is PublishOutcome.Permanent -> {
                    val account = repo.accounts.byId(post.accountId)
                    notifyError(
                        container, "Публикация не прошла",
                        "«${account?.displayName ?: "аккаунт"}»: ${outcome.reason}", post.id
                    )
                }
                is PublishOutcome.Retryable ->
                    repo.log(LogLevel.WARN, "publish", "Повтор позже: ${outcome.reason}", post.accountId)
            }
        }
    }

    private suspend fun scheduleExactPublishes(container: AppContainer, wifiOnly: Boolean) {
        val now = System.currentTimeMillis()
        val soon = container.repository.posts.dueByStatus(PostStatus.READY, now + PERIOD_MS)
        for (post in soon) {
            if (post.scheduledAt <= now) continue
            container.workScheduler.enqueuePublish(post.id, post.scheduledAt - now, wifiOnly)
        }
    }

    private suspend fun notifyError(container: AppContainer, title: String, text: String, id: Long) {
        if (!container.settings.current().notifyOnError) return
        Notifications.error(applicationContext, title, text, (id % Int.MAX_VALUE).toInt())
    }

    private suspend fun notifyApproval(container: AppContainer, accountName: String, post: PostEntity) {
        if (!container.settings.current().notifyOnApproval) return
        Notifications.info(
            applicationContext,
            "Пост ждёт подтверждения",
            "«$accountName»: ${post.topic.ifBlank { "новый пост" }}",
            (post.id % Int.MAX_VALUE).toInt()
        )
    }

    private companion object {
        const val MAX_GENERATIONS_PER_RUN = 8
        const val MAX_LOOKAHEAD_MS = 12 * 3_600_000L
        const val PUBLISH_WINDOW_MS = 60_000L
        const val PERIOD_MS = 16 * 60_000L
    }
}

/** Разовый прогон поиска: всех запросов или одного конкретного. */
class SearchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val repo = container.repository
        val queryId = inputData.getLong(WorkScheduler.KEY_QUERY_ID, 0L)

        return try {
            if (queryId > 0) {
                val query = repo.queries.byId(queryId) ?: return Result.success()
                val account = repo.accounts.byId(query.accountId) ?: return Result.success()
                container.leadFinder.runQuery(account, query)
            } else {
                container.leadFinder.runDueQueries()
                container.inbox.run()
            }
            Result.success()
        } catch (e: Exception) {
            repo.logError("search", "Поиск не удался: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

/** Генерация конкретного поста, в том числе по кнопке. */
class GenerateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val repo = container.repository
        val postId = inputData.getLong(WorkScheduler.KEY_POST_ID, 0L)
        val brief = inputData.getString(WorkScheduler.KEY_BRIEF)
        val post = repo.posts.byId(postId) ?: return Result.success()

        repo.updateStatus(post, PostStatus.GENERATING)
        return try {
            val generated = container.generator.generateFor(post, brief)
            repo.posts.update(generated)
            if (generated.status == PostStatus.READY) {
                val wifiOnly = container.settings.current().wifiOnly
                container.workScheduler.enqueuePublish(
                    generated.id,
                    generated.scheduledAt - System.currentTimeMillis(),
                    wifiOnly
                )
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                repo.updateStatus(post, PostStatus.QUEUED)
                Result.retry()
            } else {
                repo.markFailed(post, "Генерация не удалась: ${e.message}")
                Notifications.error(
                    applicationContext, "Ошибка генерации",
                    e.message ?: "Неизвестная ошибка", (postId % Int.MAX_VALUE).toInt()
                )
                Result.failure()
            }
        }
    }
}

/** Публикация конкретного поста или ответа в назначенное время. */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val repo = container.repository
        val postId = inputData.getLong(WorkScheduler.KEY_POST_ID, 0L)
        val post = repo.posts.byId(postId) ?: return Result.success()

        if (post.status != PostStatus.READY) return Result.success()
        if (!container.settings.current().autopilot && !post.manual) return Result.success()

        return when (val outcome = container.publisher.publish(post)) {
            is PublishOutcome.Success -> Result.success()
            is PublishOutcome.Deferred -> {
                repo.posts.update(
                    post.copy(
                        status = PostStatus.READY,
                        scheduledAt = System.currentTimeMillis() + outcome.retryAfterMs,
                        lastError = outcome.reason,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                container.workScheduler.enqueuePublish(postId, outcome.retryAfterMs)
                Result.success()
            }
            is PublishOutcome.Permanent -> {
                val account = repo.accounts.byId(post.accountId)
                val what = if (post.kind == PostKind.REPLY) "Ответ" else "Пост"
                Notifications.error(
                    applicationContext, "$what не опубликован",
                    "«${account?.displayName ?: "аккаунт"}»: ${outcome.reason}",
                    (postId % Int.MAX_VALUE).toInt()
                )
                Result.failure()
            }
            is PublishOutcome.Retryable ->
                if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
