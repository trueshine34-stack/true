package com.plovault.sync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plovault.sync.data.FolderImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Папка загрузок: приложение само разбирает появляющиеся там выгрузки PokerCraft.
 * Этот путь не зависит ни от токена, ни от записанного рецепта.
 */
@Composable
fun FolderCard(state: AppState, onMessage: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val importer = remember { FolderImporter(state.context) }
    var folder by remember { mutableStateOf(importer.folderName()) }
    var lastScan by remember { mutableStateOf(state.prefs.lastFolderScanTs) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importer.useFolder(uri)
        folder = importer.folderName()
        scope.launch {
            state.busy = true
            val res = withContext(Dispatchers.IO) { importer.scan() }
            state.busy = false
            lastScan = state.prefs.lastFolderScanTs
            state.refresh()
            onMessage(res.message)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Папка загрузок", style = MaterialTheme.typography.titleSmall)
            Text(
                "Скачивайте историю рук в PokerCraft как обычно — хоть в клиенте GGPoker, " +
                    "хоть в браузере. Приложение само найдёт новый файл в этой папке и разберёт его. " +
                    "Токен и рецепт для этого не нужны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatLine("Папка", folder ?: "не выбрана")
            StatLine("Последняя проверка", ago(lastScan))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(null) }) {
                    Text(if (folder == null) "Выбрать папку" else "Сменить папку")
                }
                OutlinedButton(
                    enabled = folder != null && !state.busy,
                    onClick = {
                        scope.launch {
                            state.busy = true
                            val res = withContext(Dispatchers.IO) { importer.scan() }
                            state.busy = false
                            lastScan = state.prefs.lastFolderScanTs
                            state.refresh()
                            onMessage(res.message)
                        }
                    }
                ) { Text("Проверить сейчас") }
            }
            if (folder == null) {
                Text(
                    "Обычно это папка Download во внутренней памяти телефона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
