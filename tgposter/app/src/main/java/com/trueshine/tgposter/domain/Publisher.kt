package com.trueshine.tgposter.domain

import com.trueshine.tgposter.core.TelegramText
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.LogLevel
import com.trueshine.tgposter.data.db.PostEntity
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.data.remote.TelegramClient
import com.trueshine.tgposter.data.remote.TelegramException
import com.trueshine.tgposter.data.repo.PostingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PublishOutcome {
    data class Success(val messageId: Long) : PublishOutcome
    /** Ошибка, которую нет смысла повторять (нет прав, канал не найден). */
    data class Permanent(val reason: String) : PublishOutcome
    /** Временная ошибка — воркер повторит попытку. */
    data class Retryable(val reason: String) : PublishOutcome
}

/** Отправка готового поста в Telegram со всеми ограничениями API. */
class Publisher(
    private val repo: PostingRepository,
    private val telegram: TelegramClient,
) {

    suspend fun publish(post: PostEntity): PublishOutcome = withContext(Dispatchers.IO) {
        val channel = repo.channels.byId(post.channelId)
            ?: return@withContext PublishOutcome.Permanent("Канал удалён")
        val account = repo.accounts.byId(channel.accountId)
            ?: return@withContext PublishOutcome.Permanent("Аккаунт удалён")
        if (!account.enabled) return@withContext PublishOutcome.Permanent("Аккаунт отключён")
        val token = repo.tokenFor(account.id)
            ?: return@withContext PublishOutcome.Permanent("Нет токена у аккаунта «${account.name}»")
        if (post.text.isBlank()) return@withContext PublishOutcome.Permanent("Пустой текст поста")

        repo.updateStatus(post, PostStatus.PUBLISHING)

        try {
            val messageId = send(token, channel, post)
            if (channel.pinAfterPost) {
                runCatching { telegram.pinMessage(token, channel.chatId, messageId) }
                    .onFailure {
                        repo.log(LogLevel.WARN, "publish", "Не удалось закрепить пост: ${it.message}", channel.id)
                    }
            }
            repo.posts.update(
                post.copy(
                    status = PostStatus.PUBLISHED,
                    tgMessageId = messageId,
                    publishedAt = System.currentTimeMillis(),
                    lastError = null,
                    attempts = post.attempts + 1,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            repo.log(LogLevel.INFO, "publish", "«${channel.title}»: пост опубликован (id $messageId)", channel.id)
            PublishOutcome.Success(messageId)
        } catch (e: TelegramException) {
            val permanent = e.errorCode in PERMANENT_CODES ||
                PERMANENT_MARKERS.any { e.message.orEmpty().contains(it, ignoreCase = true) }
            if (permanent) {
                repo.markFailed(post, "Telegram: ${e.message}")
                PublishOutcome.Permanent(e.message.orEmpty())
            } else {
                repo.posts.update(
                    post.copy(
                        status = PostStatus.READY,
                        lastError = e.message,
                        attempts = post.attempts + 1,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                PublishOutcome.Retryable(e.message.orEmpty())
            }
        } catch (e: Exception) {
            repo.posts.update(
                post.copy(
                    status = PostStatus.READY,
                    lastError = e.message,
                    attempts = post.attempts + 1,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            PublishOutcome.Retryable(e.message ?: "Неизвестная ошибка")
        }
    }

    /**
     * Возвращает id первого отправленного сообщения. Длинный текст режется на
     * части, картинка с длинным текстом уходит отдельным сообщением, потому что
     * подпись к фото ограничена 1024 символами.
     */
    private suspend fun send(token: String, channel: ChannelEntity, post: PostEntity): Long {
        val parseMode = channel.parseMode
        val image = post.imageUrl?.takeIf { it.isNotBlank() }
        var firstMessageId: Long? = null

        if (image != null) {
            val fitsCaption = post.text.length <= TelegramClient.MAX_CAPTION_LENGTH
            val caption = if (fitsCaption) post.text else null
            val sent = telegram.sendPhoto(
                token = token,
                chatId = channel.chatId,
                photoUrl = image,
                caption = caption,
                parseMode = parseMode,
                silent = channel.silent,
                protectContent = channel.protectContent,
            )
            firstMessageId = sent.messageId
            if (fitsCaption) return sent.messageId
        }

        val parts = TelegramText.split(post.text, TelegramClient.MAX_MESSAGE_LENGTH)
        for (part in parts) {
            val body = if (parseMode.equals("HTML", ignoreCase = true)) TelegramText.balance(part) else part
            val sent = telegram.sendMessage(
                token = token,
                chatId = channel.chatId,
                text = body,
                parseMode = parseMode,
                disablePreview = channel.disablePreview,
                silent = channel.silent,
                protectContent = channel.protectContent,
            )
            if (firstMessageId == null) firstMessageId = sent.messageId
        }
        return firstMessageId ?: 0L
    }

    private companion object {
        val PERMANENT_CODES = setOf(400, 401, 403, 404)
        val PERMANENT_MARKERS = listOf(
            "chat not found", "bot was blocked", "not enough rights",
            "bot is not a member", "CHAT_WRITE_FORBIDDEN", "unauthorized",
        )
    }
}
