package com.trueshine.tgposter.data.repo

import android.util.Log
import com.trueshine.tgposter.core.SecretStore
import com.trueshine.tgposter.data.db.AccountDao
import com.trueshine.tgposter.data.db.AccountEntity
import com.trueshine.tgposter.data.db.ChannelDao
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.LogDao
import com.trueshine.tgposter.data.db.LogEntity
import com.trueshine.tgposter.data.db.LogLevel
import com.trueshine.tgposter.data.db.PostDao
import com.trueshine.tgposter.data.db.PostEntity
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.data.db.RubricDao
import com.trueshine.tgposter.data.db.ScheduleDao
import com.trueshine.tgposter.data.db.SourceDao

/** Единая точка доступа к данным: DAO + операции, которым нужен доступ к секретам. */
class PostingRepository(
    val accounts: AccountDao,
    val channels: ChannelDao,
    val rubrics: RubricDao,
    val sources: SourceDao,
    val schedules: ScheduleDao,
    val posts: PostDao,
    val logs: LogDao,
    private val secrets: SecretStore,
) {

    suspend fun log(level: String, tag: String, message: String, channelId: Long? = null) {
        Log.d("TgPoster/$tag", message)
        runCatching {
            logs.insert(LogEntity(level = level, tag = tag, message = message, channelId = channelId))
        }
    }

    suspend fun logError(tag: String, message: String, channelId: Long? = null) =
        log(LogLevel.ERROR, tag, message, channelId)

    // --- Аккаунты ---

    suspend fun createAccount(name: String, token: String): Long {
        val id = accounts.insert(AccountEntity(name = name.trim().ifBlank { "Бот" }))
        secrets.put(SecretStore.accountTokenRef(id), token.trim())
        accounts.byId(id)?.let { accounts.update(it.copy(tokenRef = SecretStore.accountTokenRef(id))) }
        return id
    }

    suspend fun updateAccountToken(accountId: Long, token: String) {
        secrets.put(SecretStore.accountTokenRef(accountId), token.trim())
        accounts.byId(accountId)?.let {
            accounts.update(it.copy(tokenRef = SecretStore.accountTokenRef(accountId)))
        }
    }

    fun tokenFor(accountId: Long): String? = secrets.get(SecretStore.accountTokenRef(accountId))

    fun hasToken(accountId: Long): Boolean = !secrets.get(SecretStore.accountTokenRef(accountId)).isNullOrBlank()

    suspend fun deleteAccount(account: AccountEntity) {
        secrets.remove(SecretStore.accountTokenRef(account.id))
        accounts.delete(account)
    }

    suspend fun tokenForChannel(channel: ChannelEntity): String? = tokenFor(channel.accountId)

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
        logError("post", "Пост #${post.id}: $error", post.channelId)
    }

    suspend fun updateStatus(post: PostEntity, status: String) {
        posts.update(post.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    suspend fun cleanup(keepPostsDays: Int, keepLogsDays: Int) {
        val now = System.currentTimeMillis()
        posts.purgeOld(now - keepPostsDays * 86_400_000L)
        logs.purgeOld(now - keepLogsDays * 86_400_000L)
    }
}
