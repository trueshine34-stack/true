package com.trueshine.threadsposter.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.threadsposter.data.remote.DeepSeekModels
import com.trueshine.threadsposter.ui.components.BusyIndicator
import com.trueshine.threadsposter.ui.components.Field
import com.trueshine.threadsposter.ui.components.NumberField
import com.trueshine.threadsposter.ui.components.SectionCard
import com.trueshine.threadsposter.ui.components.SwitchRow
import com.trueshine.threadsposter.ui.vm.SettingsVm
import com.trueshine.threadsposter.ui.vm.rememberVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAccounts: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val vm: SettingsVm = rememberVm("settings") { SettingsVm(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyDialog by remember { mutableStateOf(false) }
    var metaDialog by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var exportWithSecrets by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val content = pendingExport
        if (uri != null && content != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                }
                snackbar.showSnackbar("Конфигурация сохранена")
            }
        }
        pendingExport = null
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (content != null) vm.importConfig(content)
            }
        }
    }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BusyIndicator(busy) }

            item {
                SectionCard("DeepSeek") {
                    Text(
                        if (settings.hasDeepSeekKey) "Ключ сохранён на устройстве в зашифрованном виде"
                        else "Ключ не задан — генерация недоступна",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (settings.hasDeepSeekKey) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { keyDialog = true }, modifier = Modifier.weight(1f)) {
                            Text(if (settings.hasDeepSeekKey) "Заменить" else "Добавить ключ")
                        }
                        OutlinedButton(
                            onClick = vm::testKey,
                            enabled = settings.hasDeepSeekKey,
                            modifier = Modifier.weight(1f),
                        ) { Text("Проверить") }
                    }
                    if (settings.hasDeepSeekKey) {
                        TextButton(onClick = vm::clearKey) {
                            Text("Удалить ключ", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    DropdownField(
                        label = "Модель по умолчанию",
                        value = settings.deepSeekModel,
                        options = DeepSeekModels.all,
                        onSelect = { vm.setModel(DeepSeekModels.all[it]) },
                    )
                    Field(
                        settings.deepSeekBaseUrl, vm::setBaseUrl, "Базовый URL API",
                        hint = "По умолчанию https://api.deepseek.com",
                    )
                    NumberField(
                        settings.maxTokens, vm::setMaxTokens, "Лимит токенов на пост",
                        hint = "Пост в Threads короткий, 1200 хватает с запасом",
                    )
                }
            }

            item {
                SectionCard("Приложение Meta") {
                    Text(
                        "Нужно только для входа в одно нажатие. Если вы просто вставляете готовый " +
                            "долгоживущий токен на экране аккаунтов, эти поля можно не заполнять.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (settings.hasMetaSecret) "App ID: ${settings.metaAppId} · секрет сохранён"
                        else "Не настроено",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { metaDialog = true }, modifier = Modifier.weight(1f)) {
                            Text("Настроить")
                        }
                        if (settings.hasMetaSecret) {
                            OutlinedButton(onClick = vm::clearMetaApp, modifier = Modifier.weight(1f)) {
                                Text("Очистить")
                            }
                        }
                    }
                    Text(
                        "App Secret хранится в зашифрованном виде, но помните: секрет приложения " +
                            "Meta не предназначен для хранения на устройстве. Самый безопасный путь — " +
                            "получить токен вне телефона и вставить его в приложение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard("Автопилот") {
                    SwitchRow(
                        "Только по Wi-Fi",
                        settings.wifiOnly,
                        vm::setWifiOnly,
                        subtitle = "Генерация и публикация будут ждать безлимитную сеть",
                    )
                    SwitchRow("Уведомлять об ошибках", settings.notifyOnError, vm::setNotifyError)
                    SwitchRow("Уведомлять о том, что ждёт подтверждения", settings.notifyOnApproval, vm::setNotifyApproval)
                    Text(
                        "Android может усыплять фоновые задачи. Чтобы посты выходили вовремя, " +
                            "отключите оптимизацию батареи для этого приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { openBatterySettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Настройки батареи") }
                }
            }

            item {
                SectionCard("Хранение") {
                    NumberField(settings.keepPostsDays, vm::setKeepPosts, "Хранить посты, дней")
                    NumberField(settings.keepLeadsDays, vm::setKeepLeads, "Хранить находки, дней")
                    NumberField(settings.keepLogsDays, vm::setKeepLogs, "Хранить журнал, дней")
                    OutlinedButton(onClick = vm::cleanupNow, modifier = Modifier.fillMaxWidth()) {
                        Text("Очистить старые записи сейчас")
                    }
                }
            }

            item {
                SectionCard("Резервная копия") {
                    Text(
                        "Экспорт сохраняет аккаунты, инструкции, расписания, рубрики и поисковые " +
                            "запросы в JSON. Токены выгружаются только по вашему выбору — такой файл " +
                            "нужно хранить как пароль.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow("Включить ключи в файл", exportWithSecrets, { exportWithSecrets = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.exportConfig(exportWithSecrets) { content ->
                                    pendingExport = content
                                    exportLauncher.launch("threadsposter-backup.json")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Экспорт") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Импорт") }
                    }
                }
            }

            item {
                SectionCard("Прочее") {
                    OutlinedButton(onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth()) {
                        Text("Аккаунты Threads")
                    }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                        Text("Журнал событий")
                    }
                    Text(
                        "Threads Poster · автопостинг, поиск и ответы через Threads API и DeepSeek",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Column(Modifier.padding(bottom = 24.dp)) {} }
        }
    }

    if (keyDialog) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text("Ключ DeepSeek") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        key, { key = it }, "API-ключ",
                        hint = "Получить: platform.deepseek.com → API keys",
                        singleLine = false,
                    )
                    Text(
                        "Ключ шифруется ключом из Android Keystore и не покидает устройство.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveKey(key); keyDialog = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { keyDialog = false }) { Text("Отмена") } },
        )
    }

    if (metaDialog) {
        var appId by remember { mutableStateOf(settings.metaAppId) }
        var secret by remember { mutableStateOf("") }
        var redirect by remember { mutableStateOf(settings.metaRedirectUri) }
        AlertDialog(
            onDismissRequest = { metaDialog = false },
            title = { Text("Приложение Meta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(appId, { appId = it }, "Threads App ID")
                    Field(
                        secret, { secret = it }, "Threads App Secret",
                        hint = "Оставьте пустым, чтобы не менять сохранённый",
                        singleLine = false,
                    )
                    Field(
                        redirect, { redirect = it }, "Redirect URI",
                        hint = "Тот же адрес, что вписан в настройках приложения Meta. " +
                            "Страница по нему открываться не будет — приложение перехватит переход.",
                        singleLine = false,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveMetaApp(appId, secret, redirect); metaDialog = false }) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { metaDialog = false }) { Text("Отмена") } },
        )
    }
}

private fun openBatterySettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
