package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LeadEntity
import com.trueshine.threadsposter.data.db.LeadStatus
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.remote.ThreadsException
import com.trueshine.threadsposter.data.remote.ThreadsClient
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Следит за комментариями под нашими собственными постами и готовит на них
 * ответы. Такие находки попадают в тот же список, что и находки поиска, но с
 * queryId = 0 — по нему в интерфейсе видно источник.
 */
class InboxWatcher(
    private val repo: ThreadsRepository,
    private val threads: ThreadsClient,
    private val generator: ContentGenerator,
    private val leadFinder: LeadFinder,
) {

    suspend fun run(): Int = withContext(Dispatchers.IO) {
        var prepared = 0
        for (account in repo.accounts.getEnabled()) {
            if (!account.autoReplyToMyThreads) continue
            prepared += runFor(account)
        }
        prepared
    }

    suspend fun runFor(account: AccountEntity): Int = withContext(Dispatchers.IO) {
        val token = repo.tokenFor(account.id) ?: return@withContext 0
        if (account.threadsUserId.isBlank()) return@withContext 0

        val quota = repo.remainingQuota(account, PostKind.REPLY)
        if (quota <= 0) return@withContext 0

        val myPosts = try {
            threads.myThreads(token, account.threadsUserId, RECENT_POSTS)
        } catch (e: ThreadsException) {
            repo.logError("inbox", "Не удалось получить свои посты: ${e.message}", account.id)
            return@withContext 0
        }

        var prepared = 0
        for (myPost in myPosts.filter { it.hasReplies && !it.isReply }) {
            if (prepared >= quota) break

            val replies = try {
                threads.repliesTo(token, myPost.id)
            } catch (e: ThreadsException) {
                repo.log(LogLevel.WARN, "inbox", "Ответы к ${myPost.id}: ${e.message}", account.id)
                continue
            }

            for (reply in replies) {
                if (prepared >= quota) break
                // Свои же комментарии не считаем входящими.
                if (reply.username.equals(account.username, ignoreCase = true)) continue

                val leadId = repo.leads.insertIgnore(
                    LeadEntity(
                        accountId = account.id,
                        queryId = 0,
                        threadsPostId = reply.id,
                        permalink = reply.permalink,
                        authorUsername = reply.username,
                        text = reply.text,
                        postedAt = reply.timestamp,
                        score = 10,
                        reason = "Комментарий под нашим постом",
                    )
                )
                // id <= 0 означает, что этот комментарий уже обрабатывали.
                if (leadId <= 0) continue
                val lead = repo.leads.byId(leadId) ?: continue

                val text = runCatching { generator.draftOwnReply(account, myPost.text, reply) }
                    .onFailure {
                        repo.leads.update(lead.copy(lastError = it.message, updatedAt = System.currentTimeMillis()))
                    }
                    .getOrNull()

                if (text.isNullOrBlank()) {
                    repo.leads.update(
                        lead.copy(
                            status = LeadStatus.SKIPPED,
                            reason = "Модель решила не отвечать",
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    continue
                }

                if (account.moderateReplies) {
                    repo.leads.update(
                        lead.copy(
                            status = LeadStatus.DRAFTED,
                            replyText = text,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } else {
                    leadFinder.enqueueReply(account, lead, text)
                }
                prepared++
            }
        }

        if (prepared > 0) {
            repo.log(LogLevel.INFO, "inbox", "Подготовлено ответов на комментарии: $prepared", account.id)
        }
        prepared
    }

    private companion object {
        const val RECENT_POSTS = 10
    }
}
