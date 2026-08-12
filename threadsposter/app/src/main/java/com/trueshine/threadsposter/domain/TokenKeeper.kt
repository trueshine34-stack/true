package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LogLevel
import com.trueshine.threadsposter.data.remote.ThreadsAuth
import com.trueshine.threadsposter.data.remote.ThreadsClient
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Держит токены живыми.
 *
 * Долгоживущий токен Threads действует 60 дней и продлевается ещё на 60 —
 * но только если ему уже больше суток и он ещё не истёк. Пропустить окно
 * продления означает заново проходить авторизацию, поэтому продлеваем заранее.
 */
class TokenKeeper(
    private val repo: ThreadsRepository,
    private val auth: ThreadsAuth,
    private val threads: ThreadsClient,
) {

    suspend fun refreshExpiring(): Int = withContext(Dispatchers.IO) {
        var refreshed = 0
        for (account in repo.accounts.getAll()) {
            if (!account.enabled) continue
            if (!needsRefresh(account)) continue
            if (refresh(account)) refreshed++
        }
        refreshed
    }

    fun needsRefresh(account: AccountEntity): Boolean {
        val token = repo.tokenFor(account.id)
        if (token.isNullOrBlank()) return false
        val obtained = account.tokenObtainedAt ?: return false
        val age = System.currentTimeMillis() - obtained
        if (age < MIN_AGE_MS) return false
        val expires = account.tokenExpiresAt ?: return age > REFRESH_AFTER_MS
        return expires - System.currentTimeMillis() < RENEW_WINDOW_MS
    }

    suspend fun refresh(account: AccountEntity): Boolean {
        val token = repo.tokenFor(account.id) ?: return false
        return try {
            val result = auth.refresh(token)
            repo.saveToken(account.id, result.accessToken, result.expiresInSeconds)
            repo.log(LogLevel.INFO, "token", "Токен «${account.displayName}» продлён", account.id)
            true
        } catch (e: Exception) {
            repo.accounts.update(
                account.copy(
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = false,
                    lastError = "Продление токена: ${e.message}",
                )
            )
            repo.logError("token", "Не удалось продлить токен: ${e.message}", account.id)
            false
        }
    }

    /** Проверяет токен и подтягивает username и threads-user-id. */
    suspend fun verify(account: AccountEntity): AccountEntity = withContext(Dispatchers.IO) {
        val token = repo.tokenFor(account.id)
            ?: return@withContext account.copy(
                lastCheckAt = System.currentTimeMillis(),
                lastCheckOk = false,
                lastError = "Токен не задан",
            )
        try {
            val profile = threads.me(token)
            val updated = account.copy(
                username = profile.username,
                threadsUserId = profile.id.ifBlank { account.threadsUserId },
                displayName = account.displayName.ifBlank { profile.username },
                lastCheckAt = System.currentTimeMillis(),
                lastCheckOk = true,
                lastError = null,
            )
            repo.accounts.update(updated)
            updated
        } catch (e: Exception) {
            val updated = account.copy(
                lastCheckAt = System.currentTimeMillis(),
                lastCheckOk = false,
                lastError = e.message,
            )
            repo.accounts.update(updated)
            updated
        }
    }

    private companion object {
        const val MIN_AGE_MS = 25 * 3_600_000L
        const val RENEW_WINDOW_MS = 10L * 24 * 3_600_000L
        const val REFRESH_AFTER_MS = 30L * 24 * 3_600_000L
    }
}
