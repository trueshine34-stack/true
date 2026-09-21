package com.plovault.sync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plovault.sync.sync.SyncEngine
import com.plovault.sync.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState,
    onOpenPortal: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var autoSync by remember { mutableStateOf(state.prefs.autoSync) }
    var status by remember { mutableStateOf(state.prefs.lastSyncStatus) }
    val hasRecipe = state.prefs.recipeJson != null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            state.busy = true
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = state.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin"
                    state.pipeline.importBytes(bytes, name, "manual").message
                }.getOrElse { "Ошибка импорта: ${it.message}" }
            }
            state.busy = false
            state.refresh()
            status = msg
            snackbar.showSnackbar(msg)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PLO Vault", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Выгрузка рук из PokerCraft (GGPoker) и своя база для анализа",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatLine("Раздач в базе", state.totalHands.toString())
                StatLine("Последняя рука", dateTime(state.lastHandTs))
                StatLine("Последняя синхронизация", ago(state.prefs.lastSyncTs))
                StatLine("Рецепт выгрузки", if (hasRecipe) "записан ✓" else "не записан")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!hasRecipe) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Как включить автовыгрузку", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Нажмите «Открыть PokerCraft» и войдите в аккаунт GGPoker.\n" +
                            "2. Режим «Обучение» уже включён — зайдите в историю рук, выберите " +
                            "Rush & Cash / PLO и скачайте выгрузку как обычно.\n" +
                            "3. Приложение запомнит этот запрос и дальше будет повторять его само " +
                            "с новыми датами.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Button(onClick = onOpenPortal, Modifier.fillMaxWidth()) { Text("Открыть PokerCraft") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = hasRecipe && !state.busy,
                onClick = {
                    scope.launch {
                        state.busy = true
                        val outcome = withContext(Dispatchers.IO) { SyncEngine(state.context).sync() }
                        state.busy = false
                        state.refresh()
                        status = outcome.message
                        snackbar.showSnackbar(outcome.message)
                    }
                }
            ) { Text("Синхронизировать") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !state.busy,
                onClick = { picker.launch(arrayOf("*/*")) }
            ) { Text("Импорт файла") }
        }

        if (state.busy) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Работаю…")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Автосинхронизация", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "каждые ${state.prefs.syncIntervalHours} ч, окно ${state.prefs.syncWindowDays} дн",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoSync,
                        enabled = hasRecipe,
                        onCheckedChange = {
                            autoSync = it
                            state.prefs.autoSync = it
                            SyncWorker.schedule(state.context)
                        }
                    )
                }
                if (!hasRecipe) {
                    Text(
                        "Доступно после записи рецепта выгрузки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
