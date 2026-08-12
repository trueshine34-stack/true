package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LeadEntity
import com.trueshine.threadsposter.data.db.LeadStatus
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.data.db.QueryEntity
import com.trueshine.threadsposter.data.remote.ThreadsClient
import com.trueshine.threadsposter.data.remote.ThreadsException
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class SearchRunResult(
    val found: Int = 0,
    val fresh: Int = 0,
    val relevant: Int = 0,
    val queued: Int = 0,
)

/**
 * Поиск чужих постов по ключевым словам и подготовка ответов.
 *
 * Порядок такой: поиск → грубые фильтры без затрат на модель → пакетная оценка
 * релевантности → генерация ответа только для прошедших порог. Так на модель
 * уходит малая часть найденного.
 */
class LeadFinder(
    private val repo: ThreadsRepository,
    private val threads: ThreadsClient,
    private val generator: ContentGenerator,
) {

    suspend fun runDueQueries(): SearchRunResult = withContext(Dispatchers.IO) {
        var total = SearchRunResult()
        val now = System.currentTimeMillis()

        for (query in repo.queries.enabled()) {
            val account = repo.accounts.byId(query.accountId) ?: continue
            if (!account.enabled) continue
            val due = (query.lastRunAt ?: 0L) + query.checkIntervalMinutes.coerceAtLeast(15) * 60_000L
            if (due > now) continue
            total = total + runQuery(account, query)
        }
        total
    }

    suspend fun runQuery(account: AccountEntity, query: QueryEntity): SearchRunResult =
        withContext(Dispatchers.IO) {
            val token = repo.tokenFor(account.id) ?: run {
                repo.logError("search", "Нет токена у «${account.displayName}»", account.id)
                return@withContext SearchRunResult()
            }

            val posts = try {
                threads.keywordSearch(token, query.query, query.searchType)
            } catch (e: ThreadsException) {
                repo.queries.upsert(
                    query.copy(lastRunAt = System.currentTimeMillis(), lastError = e.message)
                )
                repo.logError("search", "«${query.query}»: ${e.message}", account.id)
                return@withContext SearchRunResult()
            }

            val excluded = query.excludeKeywords.split(',', ';', '\n')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }

            // Грубая фильтрация до обращения к модели — она самая дорогая часть.
            val candidates = posts.filter { post ->
                post.text.length >= query.minTextLength &&
                    !post.username.equals(account.username, ignoreCase = true) &&
                    (!query.skipReplies || !post.isReply) &&
                    excluded.none { post.text.contains(it, ignoreCase = true) }
            }

            var fresh = 0
            val newLeads = mutableListOf<LeadEntity>()
            for (post in candidates) {
                val id = repo.leads.insertIgnore(
                    LeadEntity(
                        accountId = account.id,
                        queryId = query.id,
                        threadsPostId = post.id,
                        permalink = post.permalink,
                        authorUsername = post.username,
                        text = post.text,
                        postedAt = post.timestamp,
                    )
                )
                if (id > 0) {
                    fresh++
                    repo.leads.byId(id)?.let { newLeads += it }
                }
            }

            var relevant = 0
            var queued = 0
            if (newLeads.isNotEmpty()) {
                val batch = newLeads.take(MAX_SCORED_PER_RUN)
                val scores = runCatching { generator.scoreLeads(account, query, batch) }
                    .onFailure { repo.logError("search", "Оценка не удалась: ${it.message}", account.id) }
                    .getOrDefault(emptyList())

                for (scored in scores) {
                    val lead = batch.getOrNull(scored.index) ?: continue
                    val passed = scored.score >= query.minScore
                    repo.leads.update(
                        lead.copy(
                            score = scored.score,
                            reason = scored.reason,
                            status = if (passed) LeadStatus.NEW else LeadStatus.SKIPPED,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    if (passed) relevant++
                }

                // Не прошедшие оценку вовсе (модель их не вернула) не висят в NEW навсегда.
                val scoredIndices = scores.map { it.index }.toSet()
                batch.forEachIndexed { index, lead ->
                    if (index !in scoredIndices) {
                        repo.leads.update(
                            lead.copy(
                                status = LeadStatus.SKIPPED,
                                reason = "Модель не оценила пост",
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }

                queued = prepareReplies(account, query)
            }

            repo.queries.upsert(
                query.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastError = null,
                    foundTotal = query.foundTotal + fresh,
                )
            )
            repo.log(
                LogLevel.INFO, "search",
                "«${query.query}»: найдено ${posts.size}, новых $fresh, подходящих $relevant, ответов $queued",
                account.id
            )
            SearchRunResult(posts.size, fresh, relevant, queued)
        }

    /**
     * Готовит ответы для отобранных находок. Если у запроса выключен автоответ
     * или у аккаунта включена модерация — ответ остаётся черновиком и ждёт
     * подтверждения.
     */
    suspend fun prepareReplies(account: AccountEntity, query: QueryEntity): Int {
        if (!query.autoReply) return 0

        val quota = repo.remainingQuota(account, PostKind.REPLY)
        if (quota <= 0) {
            repo.log(LogLevel.WARN, "reply", "Дневной лимит ответов исчерпан", account.id)
            return 0
        }

        val pending = repo.leads.byStatus(LeadStatus.NEW, 50)
            .filter { it.accountId == account.id && it.queryId == query.id && it.score >= query.minScore }
            .sortedByDescending { it.score }
            .take(minOf(query.maxRepliesPerRun, quota))

        var queued = 0
        for (lead in pending) {
            // К одному автору не возвращаемся слишком часто — иначе это выглядит навязчиво.
            val recent = repo.leads.repliesToAuthorSince(
                account.id, lead.authorUsername, System.currentTimeMillis() - AUTHOR_COOLDOWN_MS
            )
            if (recent > 0) {
                repo.leads.update(
                    lead.copy(
                        status = LeadStatus.SKIPPED,
                        reason = "Этому автору уже отвечали недавно",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                continue
            }

            val text = runCatching { generator.draftReply(account, query, lead) }
                .onFailure {
                    repo.leads.update(
                        lead.copy(lastError = it.message, updatedAt = System.currentTimeMillis())
                    )
                }
                .getOrNull()

            if (text.isNullOrBlank()) {
                repo.leads.update(
                    lead.copy(
                        status = LeadStatus.SKIPPED,
                        reason = lead.reason.ifBlank { "Модель не нашла, что ответить по делу" },
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
                enqueueReply(account, lead, text)
                queued++
            }
        }
        return queued
    }

    /** Ставит готовый ответ в очередь публикации. */
    suspend fun enqueueReply(account: AccountEntity, lead: LeadEntity, text: String): Long {
        // Небольшой случайный сдвиг: мгновенные ответы выглядят машинными.
        val delayMinutes = Random.nextInt(1, account.minMinutesBetweenReplies.coerceAtLeast(2) + 1)
        val postId = repo.posts.insert(
            PostEntity(
                accountId = account.id,
                kind = PostKind.REPLY,
                status = PostStatus.READY,
                scheduledAt = System.currentTimeMillis() + delayMinutes * 60_000L,
                text = text,
                replyToId = lead.threadsPostId,
                replyToAuthor = lead.authorUsername,
                replyToText = lead.text.take(500),
                replyToPermalink = lead.permalink,
                leadId = lead.id,
                manual = true,
            )
        )
        repo.leads.update(
            lead.copy(
                status = LeadStatus.QUEUED,
                replyText = text,
                replyPostId = postId,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return postId
    }

    private operator fun SearchRunResult.plus(other: SearchRunResult) = SearchRunResult(
        found + other.found,
        fresh + other.fresh,
        relevant + other.relevant,
        queued + other.queued,
    )

    private companion object {
        const val MAX_SCORED_PER_RUN = 12
        const val AUTHOR_COOLDOWN_MS = 7 * 24 * 3_600_000L
    }
}
