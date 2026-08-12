package com.trueshine.tgposter.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.ui.components.BusyIndicator
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.vm.ChannelsVm
import com.trueshine.tgposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onOpenChannel: (Long) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    val vm: ChannelsVm = rememberVm("channels") { ChannelsVm(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<ChannelEntity?>(null) }
    var needAccount by remember { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Каналы") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (accounts.isEmpty()) needAccount = true else onOpenChannel(0L)
            }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить канал")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BusyIndicator(busy) }

            items(rows, key = { it.channel.id }) { row ->
                SectionCard(modifier = Modifier.clickable { onOpenChannel(row.channel.id) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.channel.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${row.channel.chatId} · бот: ${row.accountName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = row.channel.enabled,
                            onCheckedChange = { vm.setEnabled(row.channel, it) },
                        )
                    }
                    Text(row.schedulesText, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Часовой пояс: ${row.channel.timeZone}" +
                            if (row.channel.moderation) " · с модерацией" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpenChannel(row.channel.id) }) { Text("Настроить") }
                        TextButton(onClick = { vm.duplicate(row.channel) }) { Text("Копия") }
                        TextButton(onClick = { deleteTarget = row.channel }) {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item {
                    SectionCard("Каналов пока нет") {
                        Text(
                            "Добавьте канал: укажите @username или chat_id, напишите инструкцию для " +
                                "нейросети и задайте расписание. Дальше приложение всё сделает само.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { channel ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить канал?") },
            text = { Text("«${channel.title}» будет удалён вместе с расписаниями, рубриками и постами.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(channel); deleteTarget = null }) {
                    Text("Удалить", color = Color(0xFFD64545))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }

    if (needAccount) {
        AlertDialog(
            onDismissRequest = { needAccount = false },
            title = { Text("Сначала добавьте бота") },
            text = { Text("Канал привязывается к боту Telegram. Добавьте хотя бы одного бота с токеном от @BotFather.") },
            confirmButton = {
                TextButton(onClick = { needAccount = false; onOpenAccounts() }) { Text("К аккаунтам") }
            },
            dismissButton = { TextButton(onClick = { needAccount = false }) { Text("Позже") } },
        )
    }
}
