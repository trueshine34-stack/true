package com.plovault.sync.ui

import android.content.Intent
import android.webkit.CookieManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.FileProvider
import com.plovault.sync.data.Prefs
import com.plovault.sync.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun SettingsScreen(state: AppState, modifier: Modifier = Modifier, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val prefs = state.prefs
    var hero by remember { mutableStateOf(prefs.heroName) }
    var desktopUa by remember { mutableStateOf(prefs.desktopUa) }
    var customUa by remember { mutableStateOf(prefs.customUa ?: "") }
    var onlyPlo4 by remember { mutableStateOf(prefs.onlyPlo4Rush) }
    var interval by remember { mutableStateOf(prefs.syncIntervalHours.toString()) }
    var window by remember { mutableStateOf(prefs.syncWindowDays.toString()) }
    var showRecipe by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PortalLinkCard(state) { msg -> scope.launch { snackbar.showSnackbar(msg) } }

        FolderCard(state) { msg -> scope.launch { snackbar.showSnackbar(msg) } }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Подключение", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = hero,
                    onValueChange = { hero = it; prefs.heroName = it },
                    label = { Text("Имя героя в истории рук (обычно Hero)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchRow("Десктопный User-Agent", desktopUa) {
                    desktopUa = it; prefs.desktopUa = it
                }
                OutlinedTextField(
                    value = customUa,
                    onValueChange = { customUa = it; prefs.customUa = it },
                    label = { Text("Свой User-Agent (необязательно)") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    scope.launch { snackbar.showSnackbar("Куки очищены — откройте PokerCraft по ссылке заново") }
                }) { Text("Очистить куки") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Синхронизация", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = {
                            interval = it.filter { c -> c.isDigit() }
                            interval.toIntOrNull()?.let { v -> prefs.syncIntervalHours = v }
                            SyncWorker.schedule(state.context)
                        },
                        label = { Text("Интервал, ч") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = window,
                        onValueChange = {
                            window = it.filter { c -> c.isDigit() }
                            window.toIntOrNull()?.let { v -> prefs.syncWindowDays = v }
                        },
                        label = { Text("Окно, дней") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Окно — за сколько дней назад запрашивать выгрузку при каждой синхронизации. " +
                        "Дубликаты отсеиваются по номеру раздачи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow("Показывать только PLO4 Rush & Cash", onlyPlo4) {
                    onlyPlo4 = it
                    prefs.onlyPlo4Rush = it
                    state.applyPrefsFilter()
                    state.refresh()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showRecipe = true }) { Text("Рецепт выгрузки") }
                    OutlinedButton(onClick = {
                        prefs.recipeJson = null
                        prefs.autoSync = false
                        SyncWorker.schedule(state.context)
                        scope.launch { snackbar.showSnackbar("Рецепт удалён — запишите заново в режиме обучения") }
                    }) { Text("Сбросить рецепт") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Данные", style = MaterialTheme.typography.titleSmall)
                StatLine("Раздач в базе", state.totalHands.toString())
                StatLine(
                    "Сырых файлов",
                    (state.pipeline.rawDir().listFiles()?.size ?: 0).toString()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            state.busy = true
                            val file = withContext(Dispatchers.IO) { exportCsv(state) }
                            state.busy = false
                            if (file != null) share(state, file, "text/csv")
                            else snackbar.showSnackbar("Не удалось выгрузить CSV")
                        }
                    }) { Text("CSV") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            state.busy = true
                            val file = withContext(Dispatchers.IO) { exportRawZip(state) }
                            state.busy = false
                            if (file != null) share(state, file, "application/zip")
                            else snackbar.showSnackbar("Сырых файлов нет")
                        }
                    }) { Text("Сырые HH (zip)") }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        state.busy = true
                        val msg = withContext(Dispatchers.IO) { state.pipeline.reimportRaw().message }
                        state.busy = false
                        state.refresh()
                        snackbar.showSnackbar(msg)
                    }
                }) { Text("Переразобрать сырые файлы") }
                HorizontalDivider()
                TextButton(onClick = { confirmClear = true }) { Text("Очистить базу раздач") }
            }
        }

        Text(
            "Приложение только автоматизирует вашу собственную выгрузку истории рук из PokerCraft " +
                "и хранит её локально на телефоне. Оно не вмешивается в игровой клиент.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showRecipe) {
        AlertDialog(
            onDismissRequest = { showRecipe = false },
            confirmButton = { TextButton(onClick = { showRecipe = false }) { Text("Закрыть") } },
            title = { Text("Рецепт выгрузки") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        prefs.recipeJson ?: "Не записан. Откройте PokerCraft, включите «Обучение» " +
                            "и один раз скачайте историю рук вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить базу?") },
            text = { Text("Будут удалены все разобранные раздачи. Сырые файлы выгрузки останутся, из них можно всё восстановить.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    state.repo.clearAll()
                    state.refresh()
                    scope.launch { snackbar.showSnackbar("База очищена") }
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun exportCsv(state: AppState): File? = runCatching {
    val dir = File(state.context.filesDir, "exports").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val file = File(dir, "plovault-hands-$stamp.csv")
    file.bufferedWriter().use { w -> state.repo.exportCsv(state.filter, w) }
    file
}.getOrNull()

private fun exportRawZip(state: AppState): File? = runCatching {
    val files = state.pipeline.rawDir().listFiles()?.toList().orEmpty()
    if (files.isEmpty()) return null
    val dir = File(state.context.filesDir, "exports").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val out = File(dir, "plovault-raw-$stamp.zip")
    ZipOutputStream(out.outputStream().buffered()).use { zos ->
        files.forEach { f ->
            zos.putNextEntry(ZipEntry(f.name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
    out
}.getOrNull()

private fun share(state: AppState, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(
        state.context, "${state.context.packageName}.fileprovider", file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    state.context.startActivity(Intent.createChooser(intent, "Отправить ${file.name}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
