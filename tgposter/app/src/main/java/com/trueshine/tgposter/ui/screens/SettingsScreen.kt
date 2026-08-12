package com.trueshine.tgposter.ui.screens

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
import com.trueshine.tgposter.data.remote.DeepSeekModels
import com.trueshine.tgposter.ui.components.BusyIndicator
import com.trueshine.tgposter.ui.components.Field
import com.trueshine.tgposter.ui.components.NumberField
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.components.SwitchRow
import com.trueshine.tgposter.ui.vm.SettingsVm
import com.trueshine.tgposter.ui.vm.rememberVm
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
                        hint = "По умолчанию https://api.deepseek.com. Меняйте только если используете прокси.",
                    )
                    NumberField(
                        settings.maxTokens, vm::setMaxTokens, "Лимит токенов на пост",
                        hint = "1600 хватает на пост до ~2500 символов",
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
                    SwitchRow("Уведомлять о постах на модерации", settings.notifyOnApproval, vm::setNotifyApproval)
                    Text(
                        "Android может усыплять фоновые задачи. Чтобы посты выходили точно в срок, " +
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
                    NumberField(settings.keepLogsDays, vm::setKeepLogs, "Хранить журнал, дней")
                    OutlinedButton(onClick = vm::cleanupNow, modifier = Modifier.fillMaxWidth()) {
                        Text("Очистить старые записи сейчас")
                    }
                }
            }

            item {
                SectionCard("Резервная копия") {
                    Text(
                        "Экспорт сохраняет аккаунты, каналы, инструкции, расписания, рубрики и источники " +
                            "в JSON-файл. Ключи выгружаются только по вашему выбору — файл после этого " +
                            "нужно хранить как пароль.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow(
                        "Включить ключи в файл",
                        exportWithSecrets,
                        { exportWithSecrets = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.exportConfig(exportWithSecrets) { content ->
                                    pendingExport = content
                                    exportLauncher.launch("tgposter-backup.json")
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
                        Text("Телеграм-аккаунты")
                    }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                        Text("Журнал событий")
                    }
                    Text(
                        "TG Poster · автопостинг в Telegram с генерацией через DeepSeek",
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
            confirmButton = {
                TextButton(onClick = { vm.saveKey(key); keyDialog = false }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { keyDialog = false }) { Text("Отмена") } },
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
