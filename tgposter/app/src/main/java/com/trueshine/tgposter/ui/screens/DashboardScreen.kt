package com.trueshine.tgposter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.trueshine.tgposter.core.Time
import com.trueshine.tgposter.ui.components.BusyIndicator
import com.trueshine.tgposter.ui.components.InfoTile
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.components.SwitchRow
import com.trueshine.tgposter.ui.vm.DashboardVm
import com.trueshine.tgposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenChannels: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val vm: DashboardVm = rememberVm("dashboard") { DashboardVm(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TG Poster") }) },
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
                            Text("Добавить телеграм-бота")
                        }
                    } else if (state.channelsTotal == 0) {
                        OutlinedButton(onClick = onOpenChannels, modifier = Modifier.fillMaxWidth()) {
                            Text("Добавить канал")
                        }
                    }
                    Button(onClick = vm::runNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("  Пересчитать очередь сейчас")
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("Каналов активно", "${state.channelsActive}/${state.channelsTotal}", Modifier.weight(1f))
                    InfoTile("Ботов", state.accounts.toString(), Modifier.weight(1f))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("Опубликовано за сутки", state.publishedToday.toString(), Modifier.weight(1f))
                    InfoTile("Ошибок за сутки", state.failedToday.toString(), Modifier.weight(1f))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("В очереди", state.queued.toString(), Modifier.weight(1f))
                    InfoTile("На модерации", state.awaitingApproval.toString(), Modifier.weight(1f))
                }
            }

            item {
                SectionCard("Ближайшая публикация") {
                    val next = state.nextPost
                    if (next == null) {
                        Text(
                            "Пока ничего не запланировано. Добавьте канал и расписание.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(state.nextChannelTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Time.format(next.scheduledAt)} · ${Time.humanDelta(System.currentTimeMillis(), next.scheduledAt)}",
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
                        OutlinedButton(onClick = onOpenChannels, modifier = Modifier.fillMaxWidth()) {
                            Text("Каналы и инструкции")
                        }
                        OutlinedButton(onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth()) {
                            Text("Телеграм-аккаунты")
                        }
                        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                            Text("Журнал событий")
                        }
                    }
                }
            }
        }
    }
}
