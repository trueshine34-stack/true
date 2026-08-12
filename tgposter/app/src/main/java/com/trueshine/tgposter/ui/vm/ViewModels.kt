package com.trueshine.tgposter.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trueshine.tgposter.AppContainer
import com.trueshine.tgposter.core.SecretStore
import com.trueshine.tgposter.data.AppSettings
import com.trueshine.tgposter.data.db.AccountEntity
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.LogEntity
import com.trueshine.tgposter.data.db.PostEntity
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.data.db.RubricEntity
import com.trueshine.tgposter.data.db.ScheduleEntity
import com.trueshine.tgposter.data.db.SourceEntity
import com.trueshine.tgposter.domain.SlotCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Одноразовое сообщение для снэкбара. */
open class MessageVm : ViewModel() {
    protected val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    protected val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    protected fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                _message.value = e.message ?: "Ошибка"
            } finally {
                _busy.value = false
            }
        }
    }
}

data class DashboardState(
    val settings: AppSettings = AppSettings(),
    val channelsTotal: Int = 0,
    val channelsActive: Int = 0,
    val accounts: Int = 0,
    val publishedToday: Int = 0,
    val failedToday: Int = 0,
    val queued: Int = 0,
    val awaitingApproval: Int = 0,
    val nextPost: PostEntity? = null,
    val nextChannelTitle: String = "",
)

class DashboardVm(private val container: AppContainer) : MessageVm() {

    private val startOfDay: Long
        get() = System.currentTimeMillis() - 24 * 3_600_000L

    val state: StateFlow<DashboardState> = combine(
        container.settings.settings,
        container.repository.channels.observeAll(),
        container.repository.accounts.observeAll(),
        container.repository.posts.observeNextPost(),
        combine(
            container.repository.posts.observePublishedSince(startOfDay),
            container.repository.posts.observeFailedSince(startOfDay),
            container.repository.posts.observeCountByStatus(PostStatus.QUEUED),
            container.repository.posts.observeCountByStatus(PostStatus.NEEDS_APPROVAL),
        ) { published, failed, queued, approval -> listOf(published, failed, queued, approval) }
    ) { settings, channels, accounts, next, counters ->
        DashboardState(
            settings = settings,
            channelsTotal = channels.size,
            channelsActive = channels.count { it.enabled },
            accounts = accounts.size,
            publishedToday = counters[0],
            failedToday = counters[1],
            queued = counters[2],
            awaitingApproval = counters[3],
            nextPost = next,
            nextChannelTitle = channels.firstOrNull { it.id == next?.channelId }?.title.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun setAutopilot(enabled: Boolean) = act {
        container.settings.setAutopilot(enabled)
        val wifiOnly = container.settings.current().wifiOnly
        if (enabled) {
            container.workScheduler.ensureAutopilot(wifiOnly)
            container.workScheduler.runAutopilotNow()
            _message.value = "Автопилот включён"
        } else {
            container.workScheduler.cancelAutopilot()
            _message.value = "Автопилот выключен"
        }
    }

    fun runNow() = act {
        val created = container.planner.plan()
        container.workScheduler.runAutopilotNow()
        _message.value = if (created > 0) "Запланировано новых постов: $created" else "Очередь актуальна, проход запущен"
    }
}

class AccountsVm(private val container: AppContainer) : MessageVm() {

    val accounts: StateFlow<List<AccountEntity>> = container.repository.accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun hasToken(accountId: Long) = container.repository.hasToken(accountId)

    fun addAccount(name: String, token: String) = act {
        if (token.isBlank()) {
            _message.value = "Введите токен бота"
            return@act
        }
        val info = container.telegram.getMe(token.trim())
        val id = container.repository.createAccount(name.ifBlank { info.username }, token)
        container.repository.accounts.byId(id)?.let {
            container.repository.accounts.update(
                it.copy(
                    botUsername = info.username,
                    botId = info.id,
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = true,
                    lastError = null,
                )
            )
        }
        _message.value = "Аккаунт @${info.username} добавлен"
    }

    fun updateToken(account: AccountEntity, token: String) = act {
        val info = container.telegram.getMe(token.trim())
        container.repository.updateAccountToken(account.id, token)
        container.repository.accounts.update(
            account.copy(
                botUsername = info.username,
                botId = info.id,
                lastCheckAt = System.currentTimeMillis(),
                lastCheckOk = true,
                lastError = null,
            )
        )
        _message.value = "Токен обновлён: @${info.username}"
    }

    fun rename(account: AccountEntity, name: String) = act {
        container.repository.accounts.update(account.copy(name = name.trim().ifBlank { account.name }))
    }

    fun setEnabled(account: AccountEntity, enabled: Boolean) = act {
        container.repository.accounts.update(account.copy(enabled = enabled))
    }

    fun verify(account: AccountEntity) = act {
        val token = container.repository.tokenFor(account.id)
        if (token.isNullOrBlank()) {
            container.repository.accounts.update(
                account.copy(lastCheckOk = false, lastError = "Токен не задан", lastCheckAt = System.currentTimeMillis())
            )
            _message.value = "Токен не задан"
            return@act
        }
        try {
            val info = container.telegram.getMe(token)
            container.repository.accounts.update(
                account.copy(
                    botUsername = info.username,
                    botId = info.id,
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = true,
                    lastError = null,
                )
            )
            _message.value = "Бот @${info.username} на связи"
        } catch (e: Exception) {
            container.repository.accounts.update(
                account.copy(
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = false,
                    lastError = e.message,
                )
            )
            _message.value = "Ошибка: ${e.message}"
        }
    }

    fun delete(account: AccountEntity) = act {
        container.repository.deleteAccount(account)
        _message.value = "Аккаунт удалён вместе с его каналами"
    }
}

data class ChannelRow(
    val channel: ChannelEntity,
    val accountName: String,
    val schedulesText: String,
    val postsPerDay: Int,
)

class ChannelsVm(private val container: AppContainer) : MessageVm() {

    val rows: StateFlow<List<ChannelRow>> = combine(
        container.repository.channels.observeAll(),
        container.repository.accounts.observeAll(),
    ) { channels, accounts ->
        channels.map { channel ->
            val schedules = container.repository.schedules.enabledByChannel(channel.id)
            ChannelRow(
                channel = channel,
                accountName = accounts.firstOrNull { it.id == channel.accountId }?.name ?: "—",
                schedulesText = schedules.joinToString("; ") { SlotCalculator.describe(it) }
                    .ifBlank { "Расписание не задано" },
                postsPerDay = schedules.sumOf { SlotCalculator.postsPerDay(it) } / 7,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = container.repository.accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(channel: ChannelEntity, enabled: Boolean) = act {
        container.repository.channels.update(channel.copy(enabled = enabled))
        if (enabled) container.workScheduler.runAutopilotNow()
    }

    fun delete(channel: ChannelEntity) = act {
        container.repository.channels.delete(channel)
        _message.value = "Канал «${channel.title}» удалён"
    }

    fun duplicate(channel: ChannelEntity) = act {
        val newId = container.repository.channels.insert(
            channel.copy(id = 0, title = "${channel.title} (копия)", chatId = "", enabled = false)
        )
        val schedules = container.repository.schedules.enabledByChannel(channel.id)
        schedules.forEach { container.repository.schedules.upsert(it.copy(id = 0, channelId = newId)) }
        container.repository.rubrics.enabledByChannel(channel.id).forEach {
            container.repository.rubrics.upsert(it.copy(id = 0, channelId = newId, lastUsedAt = null))
        }
        _message.value = "Канал скопирован — укажите chat_id и включите"
    }
}

data class ChannelEditState(
    val channel: ChannelEntity? = null,
    val schedules: List<ScheduleEntity> = emptyList(),
    val rubrics: List<RubricEntity> = emptyList(),
    val sources: List<SourceEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
)

class ChannelEditVm(
    private val container: AppContainer,
    private val channelId: Long,
) : MessageVm() {

    private val _draft = MutableStateFlow<ChannelEntity?>(null)
    val draft: StateFlow<ChannelEntity?> = _draft.asStateFlow()

    private val _analysis = MutableStateFlow<String?>(null)
    val analysis: StateFlow<String?> = _analysis.asStateFlow()

    val state: StateFlow<ChannelEditState> = combine(
        if (channelId > 0) container.repository.channels.observeById(channelId) else flowOf(null),
        if (channelId > 0) container.repository.schedules.observeByChannel(channelId) else flowOf(emptyList<ScheduleEntity>()),
        if (channelId > 0) container.repository.rubrics.observeByChannel(channelId) else flowOf(emptyList<RubricEntity>()),
        if (channelId > 0) container.repository.sources.observeByChannel(channelId) else flowOf(emptyList<SourceEntity>()),
        container.repository.accounts.observeAll(),
    ) { channel, schedules, rubrics, sources, accounts ->
        ChannelEditState(channel, schedules, rubrics, sources, accounts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelEditState())

    init {
        viewModelScope.launch {
            _draft.value = if (channelId > 0) {
                container.repository.channels.byId(channelId)
            } else {
                val firstAccount = container.repository.accounts.getAll().firstOrNull()
                ChannelEntity(
                    accountId = firstAccount?.id ?: 0L,
                    title = "",
                    chatId = "",
                    timeZone = java.util.TimeZone.getDefault().id,
                )
            }
        }
    }

    fun edit(transform: (ChannelEntity) -> ChannelEntity) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save(onSaved: (Long) -> Unit = {}) = act {
        val draft = _draft.value ?: return@act
        if (draft.title.isBlank()) {
            _message.value = "Укажите название канала"
            return@act
        }
        if (draft.chatId.isBlank()) {
            _message.value = "Укажите @username или chat_id канала"
            return@act
        }
        if (draft.accountId <= 0) {
            _message.value = "Выберите аккаунт-бота"
            return@act
        }
        val id = if (draft.id > 0) {
            container.repository.channels.update(draft)
            draft.id
        } else {
            container.repository.channels.insert(draft)
        }
        _draft.value = container.repository.channels.byId(id)
        container.workScheduler.runAutopilotNow()
        _message.value = "Сохранено"
        onSaved(id)
    }

    /** Проверяет, что бот видит канал и имеет право публиковать. */
    fun testChannel() = act {
        val draft = _draft.value ?: return@act
        val account = container.repository.accounts.byId(draft.accountId)
            ?: run { _message.value = "Аккаунт не найден"; return@act }
        val token = container.repository.tokenFor(account.id)
            ?: run { _message.value = "У аккаунта нет токена"; return@act }
        val chat = container.telegram.getChat(token, draft.chatId.trim())
        val botId = account.botId ?: container.telegram.getMe(token).id
        val canPost = container.telegram.canPost(token, draft.chatId.trim(), botId)
        _message.value = if (canPost) {
            "Канал «${chat.title}» доступен, права на публикацию есть"
        } else {
            "Канал «${chat.title}» найден, но бот не может публиковать — сделайте его админом с правом отправки"
        }
    }

    fun sendTestPost() = act {
        val draft = _draft.value ?: return@act
        val token = container.repository.tokenFor(draft.accountId)
            ?: run { _message.value = "У аккаунта нет токена"; return@act }
        container.telegram.sendMessage(
            token = token,
            chatId = draft.chatId.trim(),
            text = "Тестовое сообщение из TG Poster ✅",
            parseMode = draft.parseMode,
            disablePreview = true,
            silent = true,
        )
        _message.value = "Тестовое сообщение отправлено"
    }

    fun generateInstructions(brief: String) = act {
        val draft = _draft.value ?: return@act
        val text = container.generator.draftInstructions(draft.title, brief)
        edit { it.copy(instructions = text.trim()) }
        _message.value = "Инструкция сгенерирована — проверьте и сохраните"
    }

    fun analyze() = act {
        val channel = container.repository.channels.byId(channelId)
            ?: run { _message.value = "Сначала сохраните канал"; return@act }
        _analysis.value = container.generator.analyzeChannel(channel)
    }

    fun clearAnalysis() { _analysis.value = null }

    // --- Расписания ---

    fun saveSchedule(schedule: ScheduleEntity) = act {
        val id = _draft.value?.id ?: 0L
        if (id <= 0) {
            _message.value = "Сначала сохраните канал"
            return@act
        }
        container.repository.schedules.upsert(schedule.copy(channelId = id))
        container.planner.replanChannel(id)
        _message.value = "Расписание сохранено"
    }

    fun deleteSchedule(schedule: ScheduleEntity) = act {
        container.repository.schedules.delete(schedule)
        container.planner.replanChannel(schedule.channelId)
    }

    // --- Рубрики ---

    fun saveRubric(rubric: RubricEntity) = act {
        val id = _draft.value?.id ?: 0L
        if (id <= 0) {
            _message.value = "Сначала сохраните канал"
            return@act
        }
        container.repository.rubrics.upsert(rubric.copy(channelId = id))
    }

    fun deleteRubric(rubric: RubricEntity) = act { container.repository.rubrics.delete(rubric) }

    // --- Источники ---

    fun saveSource(source: SourceEntity) = act {
        val id = _draft.value?.id ?: 0L
        if (id <= 0) {
            _message.value = "Сначала сохраните канал"
            return@act
        }
        container.repository.sources.upsert(source.copy(channelId = id))
    }

    fun deleteSource(source: SourceEntity) = act { container.repository.sources.delete(source) }

    fun testSource(source: SourceEntity) = act {
        val items = container.rss.fetch(source.url)
        _message.value = if (items.isEmpty()) {
            "Лента доступна, но записей не найдено"
        } else {
            "Загружено записей: ${items.size}. Первая: «${items.first().title}»"
        }
    }

    /** Создать пост вне расписания. */
    fun createManualPost(brief: String, minutesFromNow: Int, onCreated: (Long) -> Unit) = act {
        val id = _draft.value?.id ?: 0L
        if (id <= 0) {
            _message.value = "Сначала сохраните канал"
            return@act
        }
        val postId = container.repository.posts.insert(
            PostEntity(
                channelId = id,
                scheduledAt = System.currentTimeMillis() + minutesFromNow.coerceAtLeast(1) * 60_000L,
                manual = true,
                status = PostStatus.QUEUED,
            )
        )
        container.workScheduler.enqueueGenerate(postId, brief.takeIf { it.isNotBlank() })
        _message.value = "Пост поставлен в очередь на генерацию"
        onCreated(postId)
    }
}

class QueueVm(private val container: AppContainer) : MessageVm() {

    private val _filter = MutableStateFlow(Filter.ACTIVE)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    enum class Filter(val title: String, val statuses: List<String>) {
        ACTIVE("Активные", PostStatus.active),
        APPROVAL("На модерации", listOf(PostStatus.NEEDS_APPROVAL)),
        PUBLISHED("Опубликованные", listOf(PostStatus.PUBLISHED)),
        PROBLEMS("Проблемы", listOf(PostStatus.FAILED, PostStatus.SKIPPED)),
    }

    val channels: StateFlow<List<ChannelEntity>> = container.repository.channels.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val posts: StateFlow<List<PostEntity>> = _filter
        .flatMapLatest { container.repository.posts.observeByStatuses(it.statuses) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: Filter) { _filter.value = value }

    fun generateNow(post: PostEntity) = act {
        container.workScheduler.enqueueGenerate(post.id)
        _message.value = "Генерация запущена"
    }

    fun publishNow(post: PostEntity) = act {
        if (post.text.isBlank()) {
            _message.value = "Сначала сгенерируйте текст"
            return@act
        }
        container.repository.posts.update(
            post.copy(status = PostStatus.READY, scheduledAt = System.currentTimeMillis())
        )
        container.workScheduler.enqueuePublish(post.id, 0)
        _message.value = "Публикуем…"
    }

    fun approve(post: PostEntity) = act {
        container.repository.posts.update(post.copy(status = PostStatus.READY))
        val delay = (post.scheduledAt - System.currentTimeMillis()).coerceAtLeast(0)
        container.workScheduler.enqueuePublish(post.id, delay)
        _message.value = "Пост подтверждён"
    }

    fun cancel(post: PostEntity) = act {
        container.workScheduler.cancelPost(post.id)
        container.repository.posts.update(post.copy(status = PostStatus.CANCELED))
    }

    fun delete(post: PostEntity) = act {
        container.workScheduler.cancelPost(post.id)
        container.repository.posts.delete(post)
    }

    fun retry(post: PostEntity) = act {
        container.repository.posts.update(
            post.copy(
                status = if (post.text.isBlank()) PostStatus.QUEUED else PostStatus.READY,
                scheduledAt = maxOf(post.scheduledAt, System.currentTimeMillis() + 60_000L),
                lastError = null,
            )
        )
        container.workScheduler.runAutopilotNow()
        _message.value = "Пост возвращён в очередь"
    }
}

class PostVm(private val container: AppContainer, private val postId: Long) : MessageVm() {

    val post: StateFlow<PostEntity?> = container.repository.posts.observeById(postId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _channel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = _channel.asStateFlow()

    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = container.repository.posts.byId(postId)
            _text.value = loaded?.text
            _channel.value = loaded?.let { container.repository.channels.byId(it.channelId) }
        }
    }

    fun setText(value: String) { _text.value = value }

    fun setImageUrl(value: String) = act {
        val current = container.repository.posts.byId(postId) ?: return@act
        container.repository.posts.update(current.copy(imageUrl = value.trim().ifBlank { null }))
    }

    fun setScheduledAt(millis: Long) = act {
        val current = container.repository.posts.byId(postId) ?: return@act
        container.repository.posts.update(current.copy(scheduledAt = millis, updatedAt = System.currentTimeMillis()))
        container.workScheduler.cancelPost(postId)
        if (current.status == PostStatus.READY) {
            container.workScheduler.enqueuePublish(postId, millis - System.currentTimeMillis())
        }
        _message.value = "Время публикации обновлено"
    }

    fun saveText() = act {
        val current = container.repository.posts.byId(postId) ?: return@act
        val channel = container.repository.channels.byId(current.channelId)
        val prepared = com.trueshine.tgposter.core.TelegramText
            .prepare(_text.value.orEmpty(), channel?.parseMode ?: "HTML")
        container.repository.posts.update(
            current.copy(text = prepared, updatedAt = System.currentTimeMillis())
        )
        _text.value = prepared
        _message.value = "Текст сохранён"
    }

    fun regenerate(brief: String?) = act {
        container.workScheduler.enqueueGenerate(postId, brief)
        _message.value = "Перегенерация запущена"
    }

    fun publishNow() = act {
        val current = container.repository.posts.byId(postId) ?: return@act
        if (current.text.isBlank()) {
            _message.value = "Текст пуст"
            return@act
        }
        container.repository.posts.update(
            current.copy(status = PostStatus.READY, scheduledAt = System.currentTimeMillis())
        )
        container.workScheduler.enqueuePublish(postId, 0)
        _message.value = "Публикуем…"
    }

    /** Подтягивает актуальный текст из БД после перегенерации. */
    fun refreshText() = act {
        _text.value = container.repository.posts.byId(postId)?.text
    }
}

class SettingsVm(private val container: AppContainer) : MessageVm() {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun hasKey(): Boolean = !container.secrets.get(SecretStore.DEEPSEEK_API_KEY).isNullOrBlank()

    fun saveKey(key: String) = act {
        if (key.isBlank()) {
            _message.value = "Ключ пустой"
            return@act
        }
        container.secrets.put(SecretStore.DEEPSEEK_API_KEY, key.trim())
        container.settings.setHasDeepSeekKey(true)
        _message.value = "Ключ сохранён"
    }

    fun clearKey() = act {
        container.secrets.remove(SecretStore.DEEPSEEK_API_KEY)
        container.settings.setHasDeepSeekKey(false)
        _message.value = "Ключ удалён"
    }

    fun testKey() = act {
        val cfg = container.settings.current()
        val key = container.secrets.get(SecretStore.DEEPSEEK_API_KEY).orEmpty()
        _message.value = container.deepSeek.testKey(key, cfg.deepSeekBaseUrl, cfg.deepSeekModel)
    }

    fun setModel(model: String) = act { container.settings.setModel(model) }
    fun setBaseUrl(url: String) = act { container.settings.setBaseUrl(url) }
    fun setMaxTokens(value: Int) = act { container.settings.setMaxTokens(value) }
    fun setNotifyError(value: Boolean) = act { container.settings.setNotifyOnError(value) }
    fun setNotifyApproval(value: Boolean) = act { container.settings.setNotifyOnApproval(value) }
    fun setWifiOnly(value: Boolean) = act {
        container.settings.setWifiOnly(value)
        container.workScheduler.ensureAutopilot(value)
    }
    fun setKeepPosts(value: Int) = act { container.settings.setKeepPostsDays(value) }
    fun setKeepLogs(value: Int) = act { container.settings.setKeepLogsDays(value) }

    fun exportConfig(includeSecrets: Boolean, onReady: (String) -> Unit) = act {
        onReady(container.backup.export(includeSecrets))
    }

    fun importConfig(content: String) = act {
        _message.value = container.backup.restore(content)
        container.workScheduler.runAutopilotNow()
    }

    fun cleanupNow() = act {
        val cfg = container.settings.current()
        container.repository.cleanup(cfg.keepPostsDays, cfg.keepLogsDays)
        _message.value = "Старые записи удалены"
    }
}

class LogsVm(private val container: AppContainer) : MessageVm() {

    val logs: StateFlow<List<LogEntity>> = container.repository.logs.observeRecent(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = act {
        container.repository.logs.clear()
        _message.value = "Журнал очищен"
    }

    suspend fun dump(): String = container.repository.logs.recent(300)
        .joinToString("\n") { "${com.trueshine.tgposter.core.Time.formatFull(it.ts)} [${it.level}] ${it.tag}: ${it.message}" }
}
