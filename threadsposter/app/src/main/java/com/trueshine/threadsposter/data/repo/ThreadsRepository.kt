package com.trueshine.threadsposter.data.repo

import android.util.Log
import com.trueshine.threadsposter.core.SecretStore
import com.trueshine.threadsposter.data.db.AccountDao
import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LeadDao
import com.trueshine.threadsposter.data.db.LogDao
import com.trueshine.threadsposter.data.db.LogEntity
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.db.PostDao
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.data.db.QueryDao
import com.trueshine.threadsposter.data.db.RubricDao
import com.trueshine.threadsposter.data.db.ScheduleDao

/** Единая точка доступа к данным: DAO + операции, которым нужны секреты. */
class ThreadsRepository(
    val accounts: AccountDao,
    val rubrics: RubricDao,
    val schedules: ScheduleDao,
    val posts: PostDao,
    val queries: QueryDao,
    val leads: LeadDao,
    val logs: LogDao,
    private val secrets: SecretStore,
) {

    suspend fun log(level: String, tag: String, message: String, accountId: Long? = null) {
        Log.d("ThreadsPoster/$tag", message)
        runCatching {
            logs.insert(LogEntity(level = level, tag = tag, message = message, accountId = accountId))
        }
    }

    suspend fun logError(tag: String, message: String, accountId: Long? = null) =
        log(LogLevel.ERROR, tag, message, accountId)

    // --- Аккаунты и токены ---

    suspend fun createAccount(displayName: String, token: String): Long {
        val id = accounts.insert(
            AccountEntity(
                displayName = displayName.trim().ifBlank { "Аккаунт" },
                timeZone = java.util.TimeZone.getDefault().id,
            )
        )
        saveToken(id, token, null)
        return id
    }

    suspend fun saveToken(accountId: Long, token: String, expiresInSeconds: Long?) {
        secrets.put(SecretStore.accountTokenRef(accountId), token.trim())
        val now = System.currentTimeMillis()
        accounts.byId(accountId)?.let {
            accounts.update(
                it.copy(
                    tokenRef = SecretStore.accountTokenRef(accountId),
                    tokenObtainedAt = now,
                    tokenExpiresAt = expiresInSeconds?.let { seconds -> now + seconds * 1000 },
                )
            )
        }
    }

    fun tokenFor(accountId: Long): String? = secrets.get(SecretStore.accountTokenRef(accountId))

    fun hasToken(accountId: Long): Boolean = !tokenFor(accountId).isNullOrBlank()

    suspend fun deleteAccount(account: AccountEntity) {
        secrets.remove(SecretStore.accountTokenRef(account.id))
        accounts.delete(account)
    }

    // --- Посты ---

    suspend fun markFailed(post: PostEntity, error: String) {
        posts.update(
            post.copy(
                status = PostStatus.FAILED,
                lastError = error,
                attempts = post.attempts + 1,
                updatedAt = System.currentTimeMillis(),
            )
        )
        logError("post", "Пост #${post.id}: $error", post.accountId)
    }

    suspend fun updateStatus(post: PostEntity, status: String) {
        posts.update(post.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Сколько ещё публикаций этого типа можно сделать сегодня.
     * Threads разрешает 250 постов в сутки, но полезнее держать свой лимит.
     */
    suspend fun remainingQuota(account: AccountEntity, kind: String): Int {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val used = posts.publishedCountSince(account.id, kind, since)
        val limit = if (kind == PostKind.REPLY) account.dailyReplyLimit else account.dailyPostLimit
        return (limit - used).coerceAtLeast(0)
    }

    /** Не пора ли ещё отвечать: между ответами держим паузу. */
    suspend fun replyCooldownLeftMs(account: AccountEntity): Long {
        val last = posts.lastReplyAt(account.id) ?: return 0
        val gap = account.minMinutesBetweenReplies.coerceAtLeast(0) * 60_000L
        return (last + gap - System.currentTimeMillis()).coerceAtLeast(0)
    }

    suspend fun cleanup(keepPostsDays: Int, keepLeadsDays: Int, keepLogsDays: Int) {
        val now = System.currentTimeMillis()
        posts.purgeOld(now - keepPostsDays * 86_400_000L)
        leads.purgeOld(now - keepLeadsDays * 86_400_000L)
        logs.purgeOld(now - keepLogsDays * 86_400_000L)
    }
}
