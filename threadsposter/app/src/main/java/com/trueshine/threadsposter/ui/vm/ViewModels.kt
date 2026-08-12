package com.trueshine.threadsposter.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trueshine.threadsposter.AppContainer
import com.trueshine.threadsposter.core.SecretStore
import com.trueshine.threadsposter.core.ThreadsText
import com.trueshine.threadsposter.core.describe
import com.trueshine.threadsposter.data.AppSettings
import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LeadEntity
import com.trueshine.threadsposter.data.db.LeadStatus
import com.trueshine.threadsposter.data.db.LogEntity
import com.trueshine.threadsposter.data.db.PostEntity
import com.trueshine.threadsposter.data.db.PostKind
import com.trueshine.threadsposter.data.db.PostStatus
import com.trueshine.threadsposter.data.db.QueryEntity
import com.trueshine.threadsposter.data.db.RubricEntity
import com.trueshine.threadsposter.data.db.ScheduleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** База: снэкбар-сообщения и индикатор занятости. */
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
                _message.value = e.describe()
            } finally {
                _busy.value = false
            }
        }
    }
}

data class DashboardState(
    val settings: AppSettings = AppSettings(),
    val accounts: Int = 0,
    val accountsActive: Int = 0,
    val publishedToday: Int = 0,
    val failedToday: Int = 0,
    val queued: Int = 0,
    val awaitingApproval: Int = 0,
    val leadsDrafted: Int = 0,
    val nextPost: PostEntity? = null,
    val nextAccountName: String = "",
)

class DashboardVm(private val container: AppContainer) : MessageVm() {

    private val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L

    val state: StateFlow<DashboardState> = combine(
        container.settings.settings,
        container.repository.accounts.observeAll(),
        container.repository.posts.observeNextPost(),
        combine(
            container.repository.posts.observePublishedSince(dayAgo),
            container.repository.posts.observeFailedSince(dayAgo),
            container.repository.posts.observeCountByStatus(PostStatus.QUEUED),
            container.repository.posts.observeCountByStatus(PostStatus.NEEDS_APPROVAL),
            container.repository.leads.observeCountByStatus(LeadStatus.DRAFTED),
        ) { published, failed, queued, approval, drafted ->
            listOf(published, failed, queued, approval, drafted)
        }
    ) { settings, accounts, next, counters ->
        DashboardState(
            settings = settings,
            accounts = accounts.size,
            accountsActive = accounts.count { it.enabled },
            publishedToday = counters[0],
            failedToday = counters[1],
            queued = counters[2],
            awaitingApproval = counters[3],
            leadsDrafted = counters[4],
            nextPost = next,
            nextAccountName = accounts.firstOrNull { it.id == next?.accountId }?.displayName.orEmpty(),
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

    fun setSearch(enabled: Boolean) = act {
        container.settings.setSearchEnabled(enabled)
        _message.value = if (enabled) "Поиск и ответы включены" else "Поиск и ответы выключены"
    }

    fun runNow() = act {
        val created = container.planner.plan()
        container.workScheduler.runAutopilotNow()
        _message.value = if (created > 0) "Запланировано новых постов: $created" else "Очередь актуальна, проход запущен"
    }

    fun searchNow() = act {
        container.workScheduler.runSearchNow()
        _message.value = "Поиск запущен — результаты появятся в разделе «Поиск»"
    }
}

class AccountsVm(private val container: AppContainer) : MessageVm() {

    val accounts: StateFlow<List<AccountEntity>> = container.repository.accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun hasToken(accountId: Long) = container.repository.hasToken(accountId)

    /** Основной путь: пользователь вставляет готовый долгоживущий токен. */
    fun addWithToken(name: String, token: String) = act {
        if (token.isBlank()) {
            _message.value = "Вставьте токен"
            return@act
        }
        val profile = container.threads.me(token.trim())
        val id = container.repository.createAccount(name.ifBlank { profile.username }, token)
        container.repository.accounts.byId(id)?.let {
            container.repository.accounts.update(
                it.copy(
                    username = profile.username,
                    threadsUserId = profile.id,
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = true,
                    lastError = null,
                )
            )
        }
        _message.value = "Аккаунт @${profile.username} добавлен"
    }

    /** Завершение входа через встроенный браузер: код → токен → аккаунт. */
    fun completeOAuth(code: String, onDone: () -> Unit) = act {
        val cfg = container.settings.current()
        val secret = container.secrets.get(SecretStore.META_APP_SECRET).orEmpty()
        if (cfg.metaAppId.isBlank() || secret.isBlank() || cfg.metaRedirectUri.isBlank()) {
            _message.value = "Заполните App ID, App Secret и Redirect URI в настройках"
            return@act
        }
        val short = container.auth.exchangeCode(cfg.metaAppId, secret, cfg.metaRedirectUri, code)
        val long = container.auth.toLongLived(secret, short.accessToken)
        val profile = container.threads.me(long.accessToken)

        val existing = container.repository.accounts.getAll()
            .firstOrNull { it.threadsUserId == profile.id }
        val id = existing?.id ?: container.repository.accounts.insert(
            AccountEntity(
                displayName = profile.username,
                username = profile.username,
                threadsUserId = profile.id,
                timeZone = java.util.TimeZone.getDefault().id,
            )
        )
        container.repository.saveToken(id, long.accessToken, long.expiresInSeconds)
        container.repository.accounts.byId(id)?.let {
            container.repository.accounts.update(
                it.copy(
                    username = profile.username,
                    threadsUserId = profile.id,
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = true,
                    lastError = null,
                )
            )
        }
        _message.value = "Вход выполнен: @${profile.username}"
        onDone()
    }

    fun updateToken(account: AccountEntity, token: String) = act {
        val profile = container.threads.me(token.trim())
        container.repository.saveToken(account.id, token, null)
        container.repository.accounts.byId(account.id)?.let {
            container.repository.accounts.update(
                it.copy(
                    username = profile.username,
                    threadsUserId = profile.id,
                    lastCheckAt = System.currentTimeMillis(),
                    lastCheckOk = true,
                    lastError = null,
                )
            )
        }
        _message.value = "Токен обновлён: @${profile.username}"
    }

    fun verify(account: AccountEntity) = act {
        val updated = container.tokenKeeper.verify(account)
        _message.value = if (updated.lastCheckOk == true) {
            "Аккаунт @${updated.username} на связи"
        } else {
            "Ошибка: ${updated.lastError}"
        }
    }

    fun refreshToken(account: AccountEntity) = act {
        _message.value = if (container.tokenKeeper.refresh(account)) {
            "Токен продлён ещё на 60 дней"
        } else {
            "Не удалось продлить токен — смотрите журнал"
        }
    }

    fun setEnabled(account: AccountEntity, enabled: Boolean) = act {
        container.repository.accounts.update(account.copy(enabled = enabled))
    }

    fun delete(account: AccountEntity) = act {
        container.repository.deleteAccount(account)
        _message.value = "Аккаунт удалён вместе с его постами и находками"
    }

    /** Ссылка на страницу входа Threads; null — пока не заполнены данные приложения Meta. */
    val authorizeUrl: StateFlow<String?> = container.settings.settings.map { cfg ->
        if (cfg.metaAppId.isBlank() || cfg.metaRedirectUri.isBlank()) null
        else container.auth.authorizeUrl(cfg.metaAppId, cfg.metaRedirectUri, state = "threadsposter")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

data class AccountEditState(
    val schedules: List<ScheduleEntity> = emptyList(),
    val rubrics: List<RubricEntity> = emptyList(),
)

class AccountEditVm(
    private val container: AppContainer,
    private val accountId: Long,
) : MessageVm() {

    private val _draft = MutableStateFlow<AccountEntity?>(null)
    val draft: StateFlow<AccountEntity?> = _draft.asStateFlow()

    private val _analysis = MutableStateFlow<String?>(null)
    val analysis: StateFlow<String?> = _analysis.asStateFlow()

    val state: StateFlow<AccountEditState> = combine(
        if (accountId > 0) container.repository.schedules.observeByAccount(accountId)
        else flowOf(emptyList<ScheduleEntity>()),
        if (accountId > 0) container.repository.rubrics.observeByAccount(accountId)
        else flowOf(emptyList<RubricEntity>()),
    ) { schedules, rubrics -> AccountEditState(schedules, rubrics) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountEditState())

    init {
        viewModelScope.launch { _draft.value = container.repository.accounts.byId(accountId) }
    }

    fun edit(transform: (AccountEntity) -> AccountEntity) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save() = act {
        val draft = _draft.value ?: return@act
        container.repository.accounts.update(draft)
        container.workScheduler.runAutopilotNow()
        _message.value = "Сохранено"
    }

    fun generateInstructions(brief: String) = act {
        val draft = _draft.value ?: return@act
        val text = container.generator.draftInstructions(draft.displayName, brief)
        edit { it.copy(instructions = text.trim()) }
        _message.value = "Инструкция сгенерирована — проверьте и сохраните"
    }

    fun analyze() = act {
        val account = container.repository.accounts.byId(accountId)
            ?: run { _message.value = "Аккаунт не найден"; return@act }
        _analysis.value = container.generator.analyzeAccount(account)
    }

    fun clearAnalysis() { _analysis.value = null }

    fun sendTestPost() = act {
        val draft = _draft.value ?: return@act
        val token = container.repository.tokenFor(accountId)
            ?: run { _message.value = "У аккаунта нет токена"; return@act }
        if (draft.threadsUserId.isBlank()) {
            _message.value = "Сначала проверьте аккаунт — нужен threads-user-id"
            return@act
        }
        container.threads.publish(
            token = token,
            userId = draft.threadsUserId,
            text = "Проверка связи из Threads Poster ✅",
        )
        _message.value = "Тестовый пост опубликован"
    }

    // --- Расписания ---

    fun saveSchedule(schedule: ScheduleEntity) = act {
        container.repository.schedules.upsert(schedule.copy(accountId = accountId))
        container.planner.replanAccount(accountId)
        _message.value = "Расписание сохранено"
    }

    fun deleteSchedule(schedule: ScheduleEntity) = act {
        container.repository.schedules.delete(schedule)
        container.planner.replanAccount(accountId)
    }

    // --- Рубрики ---

    fun saveRubric(rubric: RubricEntity) = act {
        container.repository.rubrics.upsert(rubric.copy(accountId = accountId))
    }

    fun deleteRubric(rubric: RubricEntity) = act { container.repository.rubrics.delete(rubric) }

    /** Разовый пост вне расписания. */
    fun createManualPost(brief: String, minutesFromNow: Int, onCreated: (Long) -> Unit) = act {
        val postId = container.repository.posts.insert(
            PostEntity(
                accountId = accountId,
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

    enum class Filter(val title: String, val statuses: List<String>) {
        ACTIVE("Активные", PostStatus.active),
        APPROVAL("На модерации", listOf(PostStatus.NEEDS_APPROVAL)),
        PUBLISHED("Опубликованные", listOf(PostStatus.PUBLISHED)),
        PROBLEMS("Проблемы", listOf(PostStatus.FAILED, PostStatus.SKIPPED)),
    }

    private val _filter = MutableStateFlow(Filter.ACTIVE)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    val accounts: StateFlow<List<AccountEntity>> = container.repository.accounts.observeAll()
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
        _message.value = "Возвращено в очередь"
    }
}

class PostVm(private val container: AppContainer, private val postId: Long) : MessageVm() {

    val post: StateFlow<PostEntity?> = container.repository.posts.observeById(postId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _account = MutableStateFlow<AccountEntity?>(null)
    val account: StateFlow<AccountEntity?> = _account.asStateFlow()

    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = container.repository.posts.byId(postId)
            _text.value = loaded?.text
            _account.value = loaded?.let { container.repository.accounts.byId(it.accountId) }
        }
    }

    fun setText(value: String) { _text.value = value }

    fun setImageUrl(value: String) = act {
        val current = container.repository.posts.byId(postId) ?: return@act
        container.repository.posts.update(current.copy(imageUrl = value.trim().ifBlank { null }))
        _message.value = "Картинка сохранена"
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
        val prepared = ThreadsText.prepare(_text.value.orEmpty())
        container.repository.posts.update(current.copy(text = prepared, updatedAt = System.currentTimeMillis()))
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

    fun refreshText() = act { _text.value = container.repository.posts.byId(postId)?.text }

    /** Во сколько частей уйдёт текст: у Threads жёсткий лимит 500 символов. */
    fun partsCount(): Int = ThreadsText.split(_text.value.orEmpty()).size
}

class SearchVm(private val container: AppContainer) : MessageVm() {

    val queries: StateFlow<List<QueryEntity>> = container.repository.queries.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = container.repository.accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(query: QueryEntity) = act {
        if (query.query.isBlank()) {
            _message.value = "Введите поисковый запрос"
            return@act
        }
        if (query.accountId <= 0) {
            _message.value = "Выберите аккаунт"
            return@act
        }
        container.repository.queries.upsert(query)
        _message.value = "Запрос сохранён"
    }

    fun delete(query: QueryEntity) = act { container.repository.queries.delete(query) }

    fun runNow(query: QueryEntity) = act {
        if (query.id <= 0) {
            _message.value = "Сначала сохраните запрос"
            return@act
        }
        container.workScheduler.runSearchNow(query.id)
        _message.value = "Поиск запущен — результаты появятся во вкладке «Находки»"
    }

    fun createFor(accountId: Long) = act {
        container.repository.queries.upsert(QueryEntity(accountId = accountId, query = ""))
    }
}

class LeadsVm(private val container: AppContainer) : MessageVm() {

    enum class Filter(val title: String, val statuses: List<String>) {
        DRAFTED("Ждут решения", listOf(LeadStatus.DRAFTED)),
        NEW("Найденные", listOf(LeadStatus.NEW)),
        QUEUED("В очереди", listOf(LeadStatus.QUEUED)),
        REPLIED("Отвечено", listOf(LeadStatus.REPLIED)),
        SKIPPED("Пропущено", listOf(LeadStatus.SKIPPED, LeadStatus.FAILED)),
    }

    private val _filter = MutableStateFlow(Filter.DRAFTED)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val leads: StateFlow<List<LeadEntity>> = _filter
        .flatMapLatest { container.repository.leads.observeByStatuses(it.statuses) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: Filter) { _filter.value = value }

    fun setReplyText(lead: LeadEntity, text: String) = act {
        container.repository.leads.update(
            lead.copy(replyText = text, updatedAt = System.currentTimeMillis())
        )
    }

    /** Подтверждение черновика: ответ уходит в очередь публикации. */
    fun approve(lead: LeadEntity, text: String) = act {
        val account = container.repository.accounts.byId(lead.accountId)
            ?: run { _message.value = "Аккаунт не найден"; return@act }
        val clean = ThreadsText.prepare(text)
        if (clean.isBlank()) {
            _message.value = "Текст ответа пуст"
            return@act
        }
        if (container.repository.remainingQuota(account, PostKind.REPLY) <= 0) {
            _message.value = "Дневной лимит ответов исчерпан"
            return@act
        }
        val postId = container.leadFinder.enqueueReply(account, lead.copy(replyText = clean), clean)
        val queued = container.repository.posts.byId(postId)
        val delay = ((queued?.scheduledAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
        container.workScheduler.enqueuePublish(postId, delay)
        _message.value = "Ответ поставлен в очередь"
    }

    fun skip(lead: LeadEntity) = act {
        container.repository.leads.update(
            lead.copy(
                status = LeadStatus.SKIPPED,
                reason = "Пропущено вручную",
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Сгенерировать (или перегенерировать) черновик ответа. */
    fun draft(lead: LeadEntity) = act {
        val account = container.repository.accounts.byId(lead.accountId)
            ?: run { _message.value = "Аккаунт не найден"; return@act }
        val query = if (lead.queryId > 0) container.repository.queries.byId(lead.queryId) else null
        val text = container.generator.draftReply(account, query, lead)
        if (text.isNullOrBlank()) {
            _message.value = "Модель не нашла, что ответить по делу"
            return@act
        }
        container.repository.leads.update(
            lead.copy(
                status = LeadStatus.DRAFTED,
                replyText = text,
                updatedAt = System.currentTimeMillis(),
            )
        )
        _message.value = "Черновик готов"
    }

    fun delete(lead: LeadEntity) = act { container.repository.leads.delete(lead) }
}

class SettingsVm(private val container: AppContainer) : MessageVm() {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

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

    fun saveMetaApp(appId: String, secret: String, redirectUri: String) = act {
        container.settings.setMetaAppId(appId)
        container.settings.setMetaRedirectUri(redirectUri)
        if (secret.isNotBlank()) {
            container.secrets.put(SecretStore.META_APP_SECRET, secret.trim())
            container.settings.setHasMetaSecret(true)
        }
        _message.value = "Данные приложения Meta сохранены"
    }

    fun clearMetaApp() = act {
        container.secrets.remove(SecretStore.META_APP_SECRET)
        container.settings.setHasMetaSecret(false)
        container.settings.setMetaAppId("")
        container.settings.setMetaRedirectUri("")
        _message.value = "Данные приложения Meta удалены"
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
    fun setKeepLeads(value: Int) = act { container.settings.setKeepLeadsDays(value) }
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
        container.repository.cleanup(cfg.keepPostsDays, cfg.keepLeadsDays, cfg.keepLogsDays)
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

    suspend fun dump(): String = container.repository.logs.recent(300).joinToString("\n") {
        "${com.trueshine.threadsposter.core.Time.formatFull(it.ts)} [${it.level}] ${it.tag}: ${it.message}"
    }
}
