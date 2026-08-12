package com.trueshine.tgposter.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.trueshine.tgposter.core.Time
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.RubricEntity
import com.trueshine.tgposter.data.db.ScheduleEntity
import com.trueshine.tgposter.data.db.ScheduleMode
import com.trueshine.tgposter.data.db.SourceEntity
import com.trueshine.tgposter.domain.SlotCalculator
import com.trueshine.tgposter.data.remote.DeepSeekModels
import com.trueshine.tgposter.ui.components.BusyIndicator
import com.trueshine.tgposter.ui.components.Field
import com.trueshine.tgposter.ui.components.NumberField
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.components.SwitchRow
import com.trueshine.tgposter.ui.vm.ChannelEditVm
import com.trueshine.tgposter.ui.vm.rememberVm

private val tabTitles = listOf("Основное", "Контент", "Расписание", "Рубрики", "Источники", "Публикация")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditScreen(
    channelId: Long,
    onBack: () -> Unit,
    onOpenPost: (Long) -> Unit,
) {
    val vm: ChannelEditVm = rememberVm("channel_$channelId") { ChannelEditVm(it, channelId) }
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
                title = { Text(if (channelId > 0) "Настройка канала" else "Новый канал") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = { TextButton(onClick = { vm.save() }) { Text("Сохранить") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val channel = draft
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            BusyIndicator(busy)
            if (channel == null) {
                Text("Загрузка…", Modifier.padding(16.dp))
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Column(Modifier.padding(top = 12.dp)) {} }
                when (tab) {
                    0 -> basicTab(vm, channel, state.accounts.map { it.id to it.name })
                    1 -> contentTab(vm, channel)
                    2 -> scheduleTab(vm, channel, state.schedules)
                    3 -> rubricsTab(vm, state.rubrics)
                    4 -> sourcesTab(vm, state.sources)
                    5 -> publishTab(vm, channel, onOpenPost)
                }
                item { Column(Modifier.padding(bottom = 32.dp)) {} }
            }
        }
    }

    analysis?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearAnalysis,
            title = { Text("Анализ канала") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::clearAnalysis) { Text("Закрыть") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.basicTab(
    vm: ChannelEditVm,
    channel: ChannelEntity,
    accounts: List<Pair<Long, String>>,
) {
    item {
        SectionCard("Канал") {
            Field(channel.title, { v -> vm.edit { it.copy(title = v) } }, "Название канала")
            Field(
                channel.chatId, { v -> vm.edit { it.copy(chatId = v.trim()) } },
                "@username или chat_id",
                hint = "Публичный канал: @mychannel. Приватный: -1001234567890 (id можно узнать, переслав пост боту @userinfobot)",
            )
            DropdownField(
                label = "Бот-аккаунт",
                value = accounts.firstOrNull { it.first == channel.accountId }?.second ?: "Не выбран",
                options = accounts.map { it.second },
                onSelect = { index -> vm.edit { it.copy(accountId = accounts[index].first) } },
            )
            DropdownField(
                label = "Часовой пояс",
                value = channel.timeZone,
                options = Time.commonZoneIds + Time.allZoneIds,
                onSelect = { index ->
                    val list = Time.commonZoneIds + Time.allZoneIds
                    vm.edit { it.copy(timeZone = list[index]) }
                },
            )
            SwitchRow(
                "Канал активен",
                channel.enabled,
                { v -> vm.edit { it.copy(enabled = v) } },
                subtitle = "Выключенный канал не генерирует и не публикует посты",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testChannel, modifier = Modifier.weight(1f)) {
                    Text("Проверить доступ")
                }
                OutlinedButton(onClick = vm::sendTestPost, modifier = Modifier.weight(1f)) {
                    Text("Тест-пост")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.contentTab(
    vm: ChannelEditVm,
    channel: ChannelEntity,
) {
    item {
        SectionCard("Инструкция для нейросети") {
            Text(
                "Главный текст, по которому DeepSeek пишет посты именно для этого канала: " +
                    "о чём канал, какие форматы, какая структура поста, чего избегать.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                channel.instructions, { v -> vm.edit { it.copy(instructions = v) } },
                "Инструкция канала",
                singleLine = false, minLines = 6,
            )
            InstructionWizard(vm)
        }
    }
    item {
        SectionCard("Стиль") {
            Field(channel.audience, { v -> vm.edit { it.copy(audience = v) } }, "Аудитория",
                hint = "Например: предприниматели 25–40, без технического бэкграунда")
            Field(channel.tone, { v -> vm.edit { it.copy(tone = v) } }, "Тон")
            Field(channel.language, { v -> vm.edit { it.copy(language = v) } }, "Язык")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(channel.minChars, { v -> vm.edit { it.copy(minChars = v) } }, "Мин. символов", Modifier.weight(1f))
                NumberField(channel.maxChars, { v -> vm.edit { it.copy(maxChars = v) } }, "Макс. символов", Modifier.weight(1f))
            }
            Text("Эмодзи", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Без эмодзи", "Умеренно", "Много").forEachIndexed { index, label ->
                    FilterChip(
                        selected = channel.emojiLevel == index,
                        onClick = { vm.edit { it.copy(emojiLevel = index) } },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
    item {
        SectionCard("Обвязка поста") {
            Field(channel.hashtags, { v -> vm.edit { it.copy(hashtags = v) } }, "Хэштеги",
                hint = "Оставьте пустым, чтобы модель подбирала их сама")
            Field(channel.ctaText, { v -> vm.edit { it.copy(ctaText = v) } }, "Призыв к действию", singleLine = false)
            Field(channel.signature, { v -> vm.edit { it.copy(signature = v) } }, "Подпись под постом", singleLine = false)
            Field(channel.bannedTopics, { v -> vm.edit { it.copy(bannedTopics = v) } }, "Запрещённые темы", singleLine = false)
            Field(channel.extraRules, { v -> vm.edit { it.copy(extraRules = v) } }, "Дополнительные правила",
                singleLine = false, minLines = 3)
        }
    }
    item {
        SectionCard("Модель") {
            DropdownField(
                label = "Модель DeepSeek",
                value = channel.model.ifBlank { "как в настройках" },
                options = listOf("как в настройках") + DeepSeekModels.all,
                onSelect = { index ->
                    val list = listOf("") + DeepSeekModels.all
                    vm.edit { it.copy(model = list[index]) }
                },
            )
            Field(
                channel.temperature.toString(),
                { v -> vm.edit { it.copy(temperature = v.replace(',', '.').toFloatOrNull()?.coerceIn(0f, 2f) ?: it.temperature) } },
                "Температура (0–2)",
                hint = "Ниже — строже и суше, выше — креативнее",
                keyboardType = KeyboardType.Decimal,
            )
            NumberField(
                channel.avoidRepeatLastN, { v -> vm.edit { it.copy(avoidRepeatLastN = v) } },
                "Помнить последних тем",
                hint = "Модель получит список последних тем, чтобы не повторяться",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.scheduleTab(
    vm: ChannelEditVm,
    channel: ChannelEntity,
    schedules: List<ScheduleEntity>,
) {
    item {
        SectionCard("Расписания") {
            Text(
                "Можно задать несколько расписаний — например, будни в 10:00 и 19:00 плюс выходные в 12:00. " +
                    "Время считается в часовом поясе канала (${channel.timeZone}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { vm.saveSchedule(ScheduleEntity(channelId = channel.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить расписание") }
        }
    }
    items(schedules.size, key = { schedules[it].id }) { index ->
        ScheduleCard(vm, schedules[index], channel.timeZone)
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

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.rubricsTab(
    vm: ChannelEditVm,
    rubrics: List<RubricEntity>,
) {
    item {
        SectionCard("Рубрики") {
            Text(
                "Рубрика — тематический блок с собственной подсказкой. Перед каждым постом " +
                    "выбирается одна рубрика: чем больше вес и чем дольше она не выпадала, тем выше шанс.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { vm.saveRubric(RubricEntity(channelId = 0, title = "Новая рубрика")) },
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

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.sourcesTab(
    vm: ChannelEditVm,
    sources: List<SourceEntity>,
) {
    item {
        SectionCard("Источники (RSS)") {
            Text(
                "Перед генерацией приложение скачивает свежие записи из этих лент и отдаёт их модели " +
                    "как фактуру. Полезно для новостных каналов и дайджестов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { vm.saveSource(SourceEntity(channelId = 0, url = "https://")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить источник") }
        }
    }
    items(sources.size, key = { sources[it].id }) { index ->
        val source = sources[index]
        var url by remember(source.id) { mutableStateOf(source.url) }
        var title by remember(source.id) { mutableStateOf(source.title) }
        SectionCard {
            Field(title, { title = it }, "Название")
            Field(url, { url = it }, "URL ленты", hint = "Например, https://example.com/feed.xml")
            SwitchRow("Активен", source.enabled, { vm.saveSource(source.copy(enabled = it)) })
            source.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.saveSource(source.copy(url = url.trim(), title = title)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
                OutlinedButton(
                    onClick = { vm.testSource(source.copy(url = url.trim())) },
                    modifier = Modifier.weight(1f),
                ) { Text("Проверить") }
                OutlinedButton(onClick = { vm.deleteSource(source) }) { Text("×") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.publishTab(
    vm: ChannelEditVm,
    channel: ChannelEntity,
    onOpenPost: (Long) -> Unit,
) {
    item {
        SectionCard("Как публиковать") {
            DropdownField(
                label = "Разметка",
                value = channel.parseMode,
                options = listOf("HTML", "MARKDOWNV2", "NONE"),
                onSelect = { index ->
                    val list = listOf("HTML", "MARKDOWNV2", "NONE")
                    vm.edit { it.copy(parseMode = list[index]) }
                },
            )
            SwitchRow("Скрывать превью ссылок", channel.disablePreview, { v -> vm.edit { it.copy(disablePreview = v) } })
            SwitchRow("Без звука", channel.silent, { v -> vm.edit { it.copy(silent = v) } })
            SwitchRow("Запретить пересылку", channel.protectContent, { v -> vm.edit { it.copy(protectContent = v) } })
            SwitchRow("Закреплять пост", channel.pinAfterPost, { v -> vm.edit { it.copy(pinAfterPost = v) } })
            SwitchRow(
                "Модерация",
                channel.moderation,
                { v -> vm.edit { it.copy(moderation = v) } },
                subtitle = "Пост уйдёт в канал только после вашего подтверждения в очереди",
            )
            NumberField(
                channel.generateAheadMinutes, { v -> vm.edit { it.copy(generateAheadMinutes = v) } },
                "Генерировать за N минут до публикации",
                hint = "Текст готовится заранее — в момент публикации сеть уже не нужна",
            )
        }
    }
    item {
        SectionCard("Действия") {
            ManualPostBlock(vm, onOpenPost)
            OutlinedButton(onClick = vm::analyze, modifier = Modifier.fillMaxWidth()) {
                Text("Проанализировать канал через DeepSeek")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(vm: ChannelEditVm, schedule: ScheduleEntity, timeZone: String) {
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
                "Время публикаций",
                hint = "Через запятую: 09:00, 14:30, 20:00",
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
                val enabled = (draft.daysMask shr index) and 1 == 1
                FilterChip(
                    selected = enabled,
                    onClick = { draft = draft.copy(daysMask = draft.daysMask xor (1 shl index)) },
                    label = { Text(day) },
                )
            }
        }

        NumberField(
            draft.jitterMinutes, { draft = draft.copy(jitterMinutes = it) },
            "Разброс ±минут",
            hint = "Небольшой разброс делает выходы естественнее",
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
        AssistChip(
            onClick = {},
            label = { Text("~${SlotCalculator.postsPerDay(draft)} постов в неделю") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSchedule(draft) }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
            OutlinedButton(onClick = { vm.deleteSchedule(schedule) }, modifier = Modifier.weight(1f)) {
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun InstructionWizard(vm: ChannelEditVm) {
    var open by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf("") }

    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Сгенерировать инструкцию через DeepSeek")
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("О чём канал?") },
            text = {
                Field(
                    brief, { brief = it },
                    "Кратко опишите канал",
                    hint = "Например: канал про личные финансы для новичков, разборы инструментов, без хайпа",
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
private fun ManualPostBlock(vm: ChannelEditVm, onOpenPost: (Long) -> Unit) {
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
                        hint = "Оставьте пустым — модель возьмёт тему сама по инструкции канала",
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
