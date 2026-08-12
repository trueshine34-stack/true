package com.trueshine.tgposter.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.tgposter.core.Time
import com.trueshine.tgposter.data.db.AccountEntity
import com.trueshine.tgposter.ui.components.BusyIndicator
import com.trueshine.tgposter.ui.components.Field
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.components.SwitchRow
import com.trueshine.tgposter.ui.vm.AccountsVm
import com.trueshine.tgposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: () -> Unit) {
    val vm: AccountsVm = rememberVm("accounts") { AccountsVm(it) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showAdd by remember { mutableStateOf(false) }
    var tokenTarget by remember { mutableStateOf<AccountEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountEntity?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Телеграм-аккаунты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить бота")
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
                        "Каждый «аккаунт» — это отдельный бот Telegram. Создайте бота у @BotFather, " +
                            "получите токен вида 123456:AA..., добавьте бота администратором в свой канал " +
                            "с правом «Публикация сообщений» — и вставьте токен сюда.\n\n" +
                            "Один бот может вести сколько угодно каналов. Разные боты нужны, когда каналы " +
                            "принадлежат разным проектам или клиентам.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(accounts, key = { it.id }) { account ->
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                account.botUsername?.let { "@$it" } ?: "бот не проверен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val status = when (account.lastCheckOk) {
                        true -> "Проверен ${account.lastCheckAt?.let { Time.format(it) } ?: ""}"
                        false -> "Ошибка: ${account.lastError ?: "неизвестно"}"
                        else -> "Ещё не проверялся"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (account.lastCheckOk == false) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!vm.hasToken(account.id)) {
                        Text(
                            "Токен не сохранён",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SwitchRow(
                        title = "Активен",
                        checked = account.enabled,
                        onCheckedChange = { vm.setEnabled(account, it) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.verify(account) }, modifier = Modifier.weight(1f)) {
                            Text("Проверить")
                        }
                        OutlinedButton(onClick = { tokenTarget = account }, modifier = Modifier.weight(1f)) {
                            Text("Токен")
                        }
                    }
                    TextButton(onClick = { deleteTarget = account }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (accounts.isEmpty()) {
                item {
                    Text(
                        "Пока нет ни одного бота. Нажмите «+», чтобы добавить.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (showAdd) {
        TokenDialog(
            title = "Новый бот",
            withName = true,
            onDismiss = { showAdd = false },
            onConfirm = { name, token -> vm.addAccount(name, token); showAdd = false },
        )
    }

    tokenTarget?.let { account ->
        TokenDialog(
            title = "Токен для «${account.name}»",
            withName = false,
            onDismiss = { tokenTarget = null },
            onConfirm = { _, token -> vm.updateToken(account, token); tokenTarget = null },
        )
    }

    deleteTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить аккаунт?") },
            text = { Text("Вместе с ботом «${account.name}» будут удалены все его каналы, расписания и посты.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(account); deleteTarget = null }) {
                    Text("Удалить", color = Color(0xFFD64545))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun TokenDialog(
    title: String,
    withName: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, token: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (withName) {
                    Field(name, { name = it }, "Название (для себя)", hint = "Например: Основной бот")
                }
                Field(
                    token, { token = it }, "Токен бота",
                    hint = "Из @BotFather, вида 1234567890:AA...",
                    singleLine = false,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, token) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
