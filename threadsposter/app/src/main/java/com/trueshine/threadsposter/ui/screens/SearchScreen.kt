package com.trueshine.threadsposter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.data.db.QueryEntity
import com.trueshine.threadsposter.data.db.SearchType
import com.trueshine.threadsposter.ui.components.BusyIndicator
import com.trueshine.threadsposter.ui.components.Field
import com.trueshine.threadsposter.ui.components.NumberField
import com.trueshine.threadsposter.ui.components.SectionCard
import com.trueshine.threadsposter.ui.components.SwitchRow
import com.trueshine.threadsposter.ui.vm.SearchVm
import com.trueshine.threadsposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenLeads: () -> Unit) {
    val vm: SearchVm = rememberVm("search") { SearchVm(it) }
    val queries by vm.queries.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<QueryEntity?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск") },
                actions = { TextButton(onClick = onOpenLeads) { Text("Находки") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (accounts.isNotEmpty()) {
                FloatingActionButton(onClick = { vm.createFor(accounts.first().id) }) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить запрос")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BusyIndicator(busy) }

            item {
                SectionCard("Как это работает") {
                    Text(
                        "Приложение ищет в Threads посты по вашим ключевым словам, отдаёт находки " +
                            "DeepSeek на оценку релевантности и готовит ответ только там, где он " +
                            "уместен. Пока автоответ выключен — вы просто получаете список находок.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Threads разрешает 2200 поисковых запросов в сутки на аккаунт — " +
                            "интервал в 60 минут расходует меньше сотой доли этого лимита.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenLeads, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть находки")
                    }
                }
            }

            if (accounts.isEmpty()) {
                item {
                    Text(
                        "Сначала подключите аккаунт Threads — поиск идёт от его имени.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            items(queries, key = { it.id }) { query ->
                QueryCard(
                    vm = vm,
                    query = query,
                    accounts = accounts.map { it.id to it.displayName },
                    onDelete = { deleteTarget = query },
                )
            }

            if (queries.isEmpty() && accounts.isNotEmpty()) {
                item {
                    Text(
                        "Запросов пока нет. Нажмите «+» и опишите, что искать.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item { Column(Modifier.padding(bottom = 32.dp)) {} }
        }
    }

    deleteTarget?.let { query ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить запрос?") },
            text = { Text("«${query.query}» перестанет искаться. Уже найденные посты останутся.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(query); deleteTarget = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun QueryCard(
    vm: SearchVm,
    query: QueryEntity,
    accounts: List<Pair<Long, String>>,
    onDelete: () -> Unit,
) {
    var draft by remember(query.id) { mutableStateOf(query) }
    var expanded by remember(query.id) { mutableStateOf(query.query.isBlank()) }

    SectionCard(draft.query.ifBlank { "Новый запрос" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (draft.enabled) "Активен" else "Выключен",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "найдено ${draft.foundTotal} · ответов ${draft.repliedTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        draft.lastRunAt?.let {
            Text(
                "Последний прогон: ${Time.format(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        draft.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (!expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.weight(1f)) {
                    Text("Настроить")
                }
                OutlinedButton(onClick = { vm.runNow(draft) }, modifier = Modifier.weight(1f)) {
                    Text("Искать сейчас")
                }
            }
            return@SectionCard
        }

        Field(draft.query, { draft = draft.copy(query = it) }, "Что искать",
            hint = "Одно-два слова, как в поиске Threads. Например: поиск клиентов")
        DropdownField(
            label = "Аккаунт",
            value = accounts.firstOrNull { it.first == draft.accountId }?.second ?: "Не выбран",
            options = accounts.map { it.second },
            onSelect = { index -> draft = draft.copy(accountId = accounts[index].first) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.searchType == SearchType.RECENT,
                onClick = { draft = draft.copy(searchType = SearchType.RECENT) },
                label = { Text("Свежие") },
            )
            FilterChip(
                selected = draft.searchType == SearchType.TOP,
                onClick = { draft = draft.copy(searchType = SearchType.TOP) },
                label = { Text("Популярные") },
            )
        }
        NumberField(
            draft.checkIntervalMinutes, { draft = draft.copy(checkIntervalMinutes = it) },
            "Проверять раз в N минут", hint = "Минимум 15 — чаще автопилот всё равно не просыпается",
        )

        Field(
            draft.relevanceBrief, { draft = draft.copy(relevanceBrief = it) },
            "Что считать подходящим",
            hint = "Например: человек спрашивает, где брать клиентов, и ему реально нужен совет",
            singleLine = false, minLines = 3,
        )
        Field(
            draft.replyInstructions, { draft = draft.copy(replyInstructions = it) },
            "Как отвечать на такие посты",
            hint = "Например: один конкретный совет из опыта, без ссылок и без рекламы",
            singleLine = false, minLines = 3,
        )
        Field(
            draft.excludeKeywords, { draft = draft.copy(excludeKeywords = it) },
            "Стоп-слова", hint = "Через запятую: розыгрыш, подпишись, курс",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(draft.minTextLength, { draft = draft.copy(minTextLength = it) },
                "Мин. длина поста", Modifier.weight(1f))
            NumberField(draft.minScore, { draft = draft.copy(minScore = it.coerceIn(1, 10)) },
                "Порог 1–10", Modifier.weight(1f))
        }
        SwitchRow(
            "Пропускать комментарии",
            draft.skipReplies,
            { draft = draft.copy(skipReplies = it) },
            subtitle = "Отвечать только на самостоятельные посты, а не на ветки обсуждений",
        )
        SwitchRow(
            "Готовить ответы автоматически",
            draft.autoReply,
            { draft = draft.copy(autoReply = it) },
            subtitle = "Выключено — приложение только собирает находки, ответы вы запускаете сами",
        )
        NumberField(
            draft.maxRepliesPerRun, { draft = draft.copy(maxRepliesPerRun = it) },
            "Максимум ответов за прогон",
        )
        SwitchRow("Запрос активен", draft.enabled, { draft = draft.copy(enabled = it) })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save(draft); expanded = false }, modifier = Modifier.weight(1f)) {
                Text("Сохранить")
            }
            OutlinedButton(onClick = { vm.runNow(draft) }, modifier = Modifier.weight(1f)) {
                Text("Искать")
            }
        }
        TextButton(onClick = onDelete) {
            Text("Удалить запрос", color = MaterialTheme.colorScheme.error)
        }
    }
}
