package com.trueshine.threadsposter.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.RubricEntity
import com.trueshine.threadsposter.data.db.ScheduleEntity
import com.trueshine.threadsposter.data.db.ScheduleMode
import com.trueshine.threadsposter.data.remote.DeepSeekModels
import com.trueshine.threadsposter.domain.SlotCalculator
import com.trueshine.threadsposter.ui.components.BusyIndicator
import com.trueshine.threadsposter.ui.components.Field
import com.trueshine.threadsposter.ui.components.NumberField
import com.trueshine.threadsposter.ui.components.SectionCard
import com.trueshine.threadsposter.ui.components.SwitchRow
import com.trueshine.threadsposter.ui.vm.AccountEditVm
import com.trueshine.threadsposter.ui.vm.rememberVm

private val tabTitles = listOf("Контент", "Расписание", "Рубрики", "Ответы", "Лимиты")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    accountId: Long,
    onBack: () -> Unit,
    onOpenPost: (Long) -> Unit,
) {
    val vm: AccountEditVm = rememberVm("account_$accountId") { AccountEditVm(it, accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(draft?.displayName?.takeIf { it.isNotBlank() } ?: "Аккаунт") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = { TextButton(onClick = vm::save) { Text("Сохранить") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val account = draft
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            BusyIndicator(busy)
            if (account == null) {
                Text("Загрузка…", Modifier.padding(16.dp))
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Column(Modifier.padding(top = 12.dp)) {} }
                when (tab) {
                    0 -> contentTab(vm, account, onOpenPost)
                    1 -> scheduleTab(vm, account, state.schedules)
                    2 -> rubricsTab(vm, state.rubrics)
                    3 -> repliesTab(vm, account)
                    4 -> limitsTab(vm, account)
                }
                item { Column(Modifier.padding(bottom = 32.dp)) {} }
            }
        }
    }

    analysis?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearAnalysis,
            title = { Text("Анализ аккаунта") },
            text = { Column(Modifier.fillMaxWidth()) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = vm::clearAnalysis) { Text("Закрыть") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.contentTab(
    vm: AccountEditVm,
    account: AccountEntity,
    onOpenPost: (Long) -> Unit,
) {
    item {
        SectionCard("Аккаунт") {
            Field(account.displayName, { v -> vm.edit { it.copy(displayName = v) } }, "Название")
            Text(
                "Threads: @${account.username.ifBlank { "не определён" }} · id ${account.threadsUserId.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownField(
                label = "Часовой пояс",
                value = account.timeZone,
                options = Time.commonZoneIds + Time.allZoneIds,
                onSelect = { index ->
                    val list = Time.commonZoneIds + Time.allZoneIds
                    vm.edit { it.copy(timeZone = list[index]) }
                },
            )
            SwitchRow(
                "Аккаунт активен",
                account.enabled,
                { v -> vm.edit { it.copy(enabled = v) } },
                subtitle = "Выключенный аккаунт не публикует и не ищет",
            )
            OutlinedButton(onClick = vm::sendTestPost, modifier = Modifier.fillMaxWidth()) {
                Text("Опубликовать тестовый пост")
            }
        }
    }
    item {
        SectionCard("Инструкция для нейросети") {
            Text(
                "Главный текст, по которому DeepSeek пишет посты для этого аккаунта: " +
                    "о чём он, для кого, какая структура поста, чего избегать.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                account.instructions, { v -> vm.edit { it.copy(instructions = v) } },
                "Инструкция аккаунта", singleLine = false, minLines = 6,
            )
            InstructionWizard(vm)
        }
    }
    item {
        SectionCard("Стиль") {
            Field(account.audience, { v -> vm.edit { it.copy(audience = v) } }, "Аудитория",
                hint = "Например: дизайнеры-фрилансеры, которые ищут клиентов")
            Field(account.tone, { v -> vm.edit { it.copy(tone = v) } }, "Тон")
            Field(account.language, { v -> vm.edit { it.copy(language = v) } }, "Язык")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(account.minChars, { v -> vm.edit { it.copy(minChars = v) } }, "Мин. символов", Modifier.weight(1f))
                NumberField(account.maxChars, { v -> vm.edit { it.copy(maxChars = v) } }, "Макс. символов", Modifier.weight(1f))
            }
            Text(
                "В Threads пост — до 500 символов. Если текст длиннее, он либо режется, " +
                    "либо уходит цепочкой — переключатель ниже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                "Длинный текст — цепочкой",
                account.splitLongPosts,
                { v -> vm.edit { it.copy(splitLongPosts = v) } },
                subtitle = "Продолжение уходит ответом к своему же посту, с нумерацией 1/2",
            )
            Text("Эмодзи", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Без эмодзи", "Умеренно", "Много").forEachIndexed { index, label ->
                    FilterChip(
                        selected = account.emojiLevel == index,
                        onClick = { vm.edit { it.copy(emojiLevel = index) } },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
    item {
        SectionCard("Обвязка поста") {
            Field(account.hashtags, { v -> vm.edit { it.copy(hashtags = v) } }, "Хэштеги",
                hint = "Пусто — модель подберёт сама")
            Field(account.ctaText, { v -> vm.edit { it.copy(ctaText = v) } }, "Призыв к действию", singleLine = false)
            Field(account.signature, { v -> vm.edit { it.copy(signature = v) } }, "Подпись", singleLine = false)
            Field(account.bannedTopics, { v -> vm.edit { it.copy(bannedTopics = v) } }, "Запрещённые темы", singleLine = false)
            Field(account.extraRules, { v -> vm.edit { it.copy(extraRules = v) } }, "Дополнительные правила",
                singleLine = false, minLines = 3)
        }
    }
    item {
        SectionCard("Модель и публикация") {
            DropdownField(
                label = "Модель DeepSeek",
                value = account.model.ifBlank { "как в настройках" },
                options = listOf("как в настройках") + DeepSeekModels.all,
                onSelect = { index ->
                    val list = listOf("") + DeepSeekModels.all
                    vm.edit { it.copy(model = list[index]) }
                },
            )
            Field(
                account.temperature.toString(),
                { v ->
                    vm.edit {
                        it.copy(
                            temperature = v.replace(',', '.').toFloatOrNull()?.coerceIn(0f, 2f) ?: it.temperature
                        )
                    }
                },
                "Температура (0–2)",
                hint = "Ниже — строже, выше — креативнее",
                keyboardType = KeyboardType.Decimal,
            )
            DropdownField(
                label = "Кто может отвечать на посты",
                value = when (account.replyControl) {
                    "accounts_you_follow" -> "только те, на кого вы подписаны"
                    "mentioned_only" -> "только упомянутые"
                    else -> "все"
                },
                options = listOf("все", "только те, на кого вы подписаны", "только упомянутые"),
                onSelect = { index ->
                    val list = listOf("everyone", "accounts_you_follow", "mentioned_only")
                    vm.edit { it.copy(replyControl = list[index]) }
                },
            )
            SwitchRow(
                "Модерация постов",
                account.moderation,
                { v -> vm.edit { it.copy(moderation = v) } },
                subtitle = "Пост уйдёт только после вашего подтверждения в очереди",
            )
            NumberField(
                account.generateAheadMinutes, { v -> vm.edit { it.copy(generateAheadMinutes = v) } },
                "Генерировать за N минут до публикации",
            )
            NumberField(
                account.avoidRepeatLastN, { v -> vm.edit { it.copy(avoidRepeatLastN = v) } },
                "Помнить последних тем",
                hint = "Модель получит список последних тем, чтобы не повторяться",
            )
        }
    }
    item {
        SectionCard("Действия") {
            ManualPostBlock(vm, onOpenPost)
            OutlinedButton(onClick = vm::analyze, modifier = Modifier.fillMaxWidth()) {
                Text("Проанализировать аккаунт через DeepSeek")
            }
        }
    }
}

private fun LazyListScope.scheduleTab(
    vm: AccountEditVm,
    account: AccountEntity,
    schedules: List<ScheduleEntity>,
) {
    item {
        SectionCard("Расписания") {
            Text(
                "Можно задать несколько расписаний. Время считается в часовом поясе аккаунта " +
                    "(${account.timeZone}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { vm.saveSchedule(ScheduleEntity(accountId = account.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить расписание") }
        }
    }
    items(schedules.size, key = { schedules[it].id }) { index ->
        ScheduleCard(vm, schedules[index], account.timeZone)
    }
    if (schedules.isEmpty()) {
        item {
            Text(
                "Расписаний нет — посты не будут создаваться автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun LazyListScope.rubricsTab(vm: AccountEditVm, rubrics: List<RubricEntity>) {
    item {
        SectionCard("Рубрики") {
            Text(
                "Рубрика — тематический блок со своей подсказкой. Перед каждым постом выбирается " +
                    "одна: чем больше вес и чем дольше она не выпадала, тем выше шанс.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { vm.saveRubric(RubricEntity(accountId = 0, title = "Новая рубрика")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить рубрику") }
        }
    }
    items(rubrics.size, key = { rubrics[it].id }) { index ->
        val rubric = rubrics[index]
        var title by remember(rubric.id) { mutableStateOf(rubric.title) }
        var prompt by remember(rubric.id) { mutableStateOf(rubric.prompt) }
        var weight by remember(rubric.id) { mutableStateOf(rubric.weight) }
        SectionCard {
            Field(title, { title = it }, "Название рубрики")
            Field(prompt, { prompt = it }, "Подсказка для модели", singleLine = false, minLines = 3)
            NumberField(weight, { weight = it }, "Вес")
            SwitchRow("Активна", rubric.enabled, { vm.saveRubric(rubric.copy(enabled = it)) })
            rubric.lastUsedAt?.let {
                Text(
                    "Последний раз: ${Time.format(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.saveRubric(rubric.copy(title = title, prompt = prompt, weight = weight)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
                OutlinedButton(onClick = { vm.deleteRubric(rubric) }, modifier = Modifier.weight(1f)) {
                    Text("Удалить")
                }
            }
        }
    }
}

private fun LazyListScope.repliesTab(vm: AccountEditVm, account: AccountEntity) {
    item {
        SectionCard("Ответы на комментарии под своими постами") {
            SwitchRow(
                "Отвечать на комментарии",
                account.autoReplyToMyThreads,
                { v -> vm.edit { it.copy(autoReplyToMyThreads = v) } },
                subtitle = "Приложение читает комментарии под вашими постами и готовит ответы",
            )
            SwitchRow(
                "Модерация ответов",
                account.moderateReplies,
                { v -> vm.edit { it.copy(moderateReplies = v) } },
                subtitle = "Ответ уйдёт только после подтверждения в разделе «Находки»",
            )
            Field(
                account.replyInstructions, { v -> vm.edit { it.copy(replyInstructions = v) } },
                "Как отвечать",
                hint = "Например: коротко, по делу, без продаж; на грубость не реагировать",
                singleLine = false, minLines = 4,
            )
        }
    }
    item {
        SectionCard("Про автоответы честно") {
            Text(
                "Ответы под чужими постами — рабочий способ находить аудиторию, но площадки " +
                    "не любят однотипную активность. Держите модерацию включённой хотя бы первое " +
                    "время, ставьте небольшие дневные лимиты и читайте, что именно уходит: " +
                    "отвечает от вашего имени модель, а отвечает за это ваш аккаунт.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.limitsTab(vm: AccountEditVm, account: AccountEntity) {
    item {
        SectionCard("Дневные лимиты") {
            Text(
                "Threads разрешает 250 публикаций в сутки на профиль. Свои лимиты стоит держать " +
                    "заметно ниже — так активность выглядит живой.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField(account.dailyPostLimit, { v -> vm.edit { it.copy(dailyPostLimit = v) } }, "Постов в сутки")
            NumberField(account.dailyReplyLimit, { v -> vm.edit { it.copy(dailyReplyLimit = v) } }, "Ответов в сутки")
            NumberField(
                account.minMinutesBetweenReplies, { v -> vm.edit { it.copy(minMinutesBetweenReplies = v) } },
                "Минимальная пауза между ответами, минут",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(vm: AccountEditVm, schedule: ScheduleEntity, timeZone: String) {
    var draft by remember(schedule.id) { mutableStateOf(schedule) }

    SectionCard(draft.label.ifBlank { "Расписание #${schedule.id}" }) {
        Field(draft.label, { draft = draft.copy(label = it) }, "Название (необязательно)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.mode == ScheduleMode.DAILY_TIMES,
                onClick = { draft = draft.copy(mode = ScheduleMode.DAILY_TIMES) },
                label = { Text("По времени") },
            )
            FilterChip(
                selected = draft.mode == ScheduleMode.INTERVAL,
                onClick = { draft = draft.copy(mode = ScheduleMode.INTERVAL) },
                label = { Text("С интервалом") },
            )
        }

        if (draft.mode == ScheduleMode.DAILY_TIMES) {
            Field(
                draft.timesCsv, { draft = draft.copy(timesCsv = it) },
                "Время публикаций", hint = "Через запятую: 09:00, 14:30, 20:00",
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(draft.windowStart, { draft = draft.copy(windowStart = it) }, "С", Modifier.weight(1f))
                Field(draft.windowEnd, { draft = draft.copy(windowEnd = it) }, "До", Modifier.weight(1f))
            }
            NumberField(draft.intervalMinutes, { draft = draft.copy(intervalMinutes = it) }, "Интервал, минут")
        }

        Text("Дни недели", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, day ->
                FilterChip(
                    selected = (draft.daysMask shr index) and 1 == 1,
                    onClick = { draft = draft.copy(daysMask = draft.daysMask xor (1 shl index)) },
                    label = { Text(day) },
                )
            }
        }

        NumberField(
            draft.jitterMinutes, { draft = draft.copy(jitterMinutes = it) },
            "Разброс ±минут", hint = "Небольшой разброс делает выходы естественнее",
        )
        SwitchRow("Включено", draft.enabled, { draft = draft.copy(enabled = it) })

        Text(SlotCalculator.describe(draft), style = MaterialTheme.typography.bodySmall)
        SlotCalculator.nextSlot(draft, timeZone, System.currentTimeMillis())?.let {
            Text(
                "Следующий слот: ${Time.format(it, timeZone)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AssistChip(onClick = {}, label = { Text("~${SlotCalculator.postsPerDay(draft)} постов в неделю") })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSchedule(draft) }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
            OutlinedButton(onClick = { vm.deleteSchedule(schedule) }, modifier = Modifier.weight(1f)) {
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun InstructionWizard(vm: AccountEditVm) {
    var open by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf("") }

    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Сгенерировать инструкцию через DeepSeek")
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("О чём аккаунт?") },
            text = {
                Field(
                    brief, { brief = it }, "Кратко опишите аккаунт",
                    hint = "Например: пишу про поиск клиентов на фрилансе, без инфоцыганства",
                    singleLine = false, minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.generateInstructions(brief); open = false }) { Text("Сгенерировать") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ManualPostBlock(vm: AccountEditVm, onOpenPost: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(10) }

    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Создать пост вручную")
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Разовый пост") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        brief, { brief = it }, "Задание для модели",
                        hint = "Пусто — модель возьмёт тему сама по инструкции аккаунта",
                        singleLine = false, minLines = 3,
                    )
                    NumberField(minutes, { minutes = it }, "Опубликовать через, минут")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createManualPost(brief, minutes) { onOpenPost(it) }
                    open = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
