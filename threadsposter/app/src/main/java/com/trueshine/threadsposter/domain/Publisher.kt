package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.core.ThreadsText
import com.trueshine.threadsposter.data.db.LeadStatus
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.data.remote.ThreadsClient
import com.trueshine.threadsposter.data.remote.ThreadsException
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PublishOutcome {
    data class Success(val mediaId: String) : PublishOutcome
    /** Ошибка, которую нет смысла повторять: протух токен, нет прав, пост отклонён. */
    data class Permanent(val reason: String) : PublishOutcome
    /** Временная ошибка — воркер повторит попытку. */
    data class Retryable(val reason: String) : PublishOutcome
    /** Публиковать пока нельзя: упёрлись в дневной лимит или в паузу между ответами. */
    data class Deferred(val reason: String, val retryAfterMs: Long) : PublishOutcome
}

/** Отправка готового поста или ответа в Threads. */
class Publisher(
    private val repo: ThreadsRepository,
    private val threads: ThreadsClient,
) {

    suspend fun publish(post: PostEntity): PublishOutcome = withContext(Dispatchers.IO) {
        val account = repo.accounts.byId(post.accountId)
            ?: return@withContext PublishOutcome.Permanent("Аккаунт удалён")
        if (!account.enabled) return@withContext PublishOutcome.Permanent("Аккаунт отключён")
        if (account.threadsUserId.isBlank()) {
            return@withContext PublishOutcome.Permanent("У аккаунта не определён threads-user-id — проверьте токен")
        }
        val token = repo.tokenFor(account.id)
            ?: return@withContext PublishOutcome.Permanent("Нет токена у аккаунта «${account.displayName}»")
        if (post.text.isBlank()) return@withContext PublishOutcome.Permanent("Пустой текст")

        // Дневной лимит — своя защита от того, чтобы аккаунт не выглядел ботом.
        val quota = repo.remainingQuota(account, post.kind)
        if (quota <= 0) {
            return@withContext PublishOutcome.Deferred(
                "Дневной лимит исчерпан (${post.kind})", DAY_MS / 4
            )
        }
        if (post.kind == PostKind.REPLY) {
            val cooldown = repo.replyCooldownLeftMs(account)
            if (cooldown > 0) {
                return@withContext PublishOutcome.Deferred("Пауза между ответами", cooldown)
            }
        }

        repo.updateStatus(post, PostStatus.PUBLISHING)

        try {
            val parts = when {
                post.kind == PostKind.REPLY -> listOf(ThreadsText.trimTo(post.text))
                account.splitLongPosts -> ThreadsText.numberParts(ThreadsText.split(post.text))
                else -> listOf(ThreadsText.trimTo(post.text))
            }

            val ids = threads.publishChain(
                token = token,
                userId = account.threadsUserId,
                parts = parts,
                imageUrl = post.imageUrl?.takeIf { it.isNotBlank() },
                replyToId = post.replyToId,
                replyControl = account.replyControl,
            )
            val mediaId = ids.firstOrNull().orEmpty()
            val permalink = threads.permalinkOf(token, mediaId)

            repo.posts.update(
                post.copy(
                    status = PostStatus.PUBLISHED,
                    threadsMediaId = mediaId,
                    permalink = permalink,
                    publishedAt = System.currentTimeMillis(),
                    lastError = null,
                    attempts = post.attempts + 1,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            markLeadReplied(post, permalink)

            val what = if (post.kind == PostKind.REPLY) "ответ" else "пост"
            repo.log(
                LogLevel.INFO, "publish",
                "«${account.displayName}»: $what опубликован" +
                    if (parts.size > 1) " (цепочка из ${parts.size})" else "",
                account.id
            )
            PublishOutcome.Success(mediaId)
        } catch (e: ThreadsException) {
            if (e.permanent) {
                repo.markFailed(post, "Threads: ${e.message}")
                markLeadFailed(post, e.message.orEmpty())
                PublishOutcome.Permanent(e.message.orEmpty())
            } else {
                backToReady(post, e.message)
                PublishOutcome.Retryable(e.message.orEmpty())
            }
        } catch (e: Exception) {
            backToReady(post, e.message)
            PublishOutcome.Retryable(e.message ?: "Неизвестная ошибка")
        }
    }

    private suspend fun backToReady(post: PostEntity, error: String?) {
        repo.posts.update(
            post.copy(
                status = PostStatus.READY,
                lastError = error,
                attempts = post.attempts + 1,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun markLeadReplied(post: PostEntity, permalink: String?) {
        if (post.leadId <= 0) return
        val lead = repo.leads.byId(post.leadId) ?: return
        repo.leads.update(
            lead.copy(
                status = LeadStatus.REPLIED,
                repliedAt = System.currentTimeMillis(),
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        repo.queries.byId(lead.queryId)?.let {
            repo.queries.upsert(it.copy(repliedTotal = it.repliedTotal + 1))
        }
        if (permalink != null) {
            repo.log(LogLevel.INFO, "reply", "Ответ @${lead.authorUsername}: $permalink", post.accountId)
        }
    }

    private suspend fun markLeadFailed(post: PostEntity, error: String) {
        if (post.leadId <= 0) return
        val lead = repo.leads.byId(post.leadId) ?: return
        repo.leads.update(
            lead.copy(
                status = LeadStatus.FAILED,
                lastError = error,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
