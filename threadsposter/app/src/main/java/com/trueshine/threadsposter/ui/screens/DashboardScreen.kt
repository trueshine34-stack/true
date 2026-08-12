package com.trueshine.threadsposter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.ui.components.BusyIndicator
import com.trueshine.threadsposter.ui.components.InfoTile
import com.trueshine.threadsposter.ui.components.SectionCard
import com.trueshine.threadsposter.ui.components.SwitchRow
import com.trueshine.threadsposter.ui.vm.DashboardVm
import com.trueshine.threadsposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenAccounts: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLeads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val vm: DashboardVm = rememberVm("dashboard") { DashboardVm(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Threads Poster") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BusyIndicator(busy) }

            item {
                SectionCard {
                    SwitchRow(
                        title = "Автопилот",
                        subtitle = if (state.settings.autopilot) {
                            "Посты генерируются и публикуются по расписанию"
                        } else {
                            "Всё остановлено, ничего не публикуется"
                        },
                        checked = state.settings.autopilot,
                        onCheckedChange = vm::setAutopilot,
                    )
                    SwitchRow(
                        title = "Поиск и ответы",
                        subtitle = "Поиск чужих постов по запросам и ответы на комментарии",
                        checked = state.settings.searchEnabled,
                        onCheckedChange = vm::setSearch,
                        enabled = state.settings.autopilot,
                    )
                    if (!state.settings.hasDeepSeekKey) {
                        Text(
                            "Не задан ключ DeepSeek — генерация работать не будет",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Добавить ключ")
                        }
                    }
                    if (state.accounts == 0) {
                        OutlinedButton(onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth()) {
                            Text("Подключить аккаунт Threads")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::runNow, modifier = Modifier.weight(1f)) {
                            Text("Прогнать очередь")
                        }
                        OutlinedButton(onClick = vm::searchNow, modifier = Modifier.weight(1f)) {
                            Text("Искать сейчас")
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("Аккаунтов активно", "${state.accountsActive}/${state.accounts}", Modifier.weight(1f))
                    InfoTile("Опубликовано за сутки", state.publishedToday.toString(), Modifier.weight(1f))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("В очереди", state.queued.toString(), Modifier.weight(1f))
                    InfoTile("Ошибок за сутки", state.failedToday.toString(), Modifier.weight(1f))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("Постов на модерации", state.awaitingApproval.toString(), Modifier.weight(1f))
                    InfoTile("Ответов ждут решения", state.leadsDrafted.toString(), Modifier.weight(1f))
                }
            }

            if (state.leadsDrafted > 0) {
                item {
                    SectionCard("Есть что подтвердить") {
                        Text(
                            "Черновиков ответов: ${state.leadsDrafted}. Пока вы их не подтвердите, " +
                                "в Threads ничего не уйдёт.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onOpenLeads, modifier = Modifier.fillMaxWidth()) {
                            Text("Открыть находки")
                        }
                    }
                }
            }

            item {
                SectionCard("Ближайшая публикация") {
                    val next = state.nextPost
                    if (next == null) {
                        Text(
                            "Пока ничего не запланировано. Подключите аккаунт, напишите инструкцию " +
                                "и задайте расписание.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(state.nextAccountName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Time.format(next.scheduledAt)} · " +
                                Time.humanDelta(System.currentTimeMillis(), next.scheduledAt),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (next.topic.isNotBlank()) {
                            Text(next.topic, style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth()) {
                            Text("Открыть очередь")
                        }
                    }
                }
            }

            item {
                SectionCard("Разделы") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth()) {
                            Text("Аккаунты и инструкции")
                        }
                        OutlinedButton(onClick = onOpenLeads, modifier = Modifier.fillMaxWidth()) {
                            Text("Находки и ответы")
                        }
                        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                            Text("Журнал событий")
                        }
                    }
                }
            }

            item { Column(Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}
