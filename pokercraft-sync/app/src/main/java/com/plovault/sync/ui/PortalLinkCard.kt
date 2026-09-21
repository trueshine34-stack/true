package com.plovault.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plovault.sync.data.Prefs

/**
 * Ввод персональной ссылки PokerCraft (той, что открывается из клиента GGPoker).
 * Токен из неё сохраняется локально и подставляется в автоматические выгрузки.
 */
@Composable
fun PortalLinkCard(
    state: AppState,
    onOpenPortal: (() -> Unit)? = null,
    onSaved: (String) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf(state.prefs.portalUrl) }
    var token by remember { mutableStateOf(state.prefs.authToken) }
    var savedAt by remember { mutableStateOf(state.prefs.tokenSavedAt) }

    fun save(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        link = trimmed
        state.prefs.portalUrl = trimmed
        token = state.prefs.authToken
        savedAt = state.prefs.tokenSavedAt
        onSaved(
            if (Prefs.extractToken(trimmed) != null) "Ссылка сохранена, токен обновлён"
            else "Ссылка сохранена (токена в ней нет)"
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ссылка PokerCraft", style = MaterialTheme.typography.titleSmall)
            Text(
                "Токен в ссылке живёт недолго, поэтому открывать её надо сразу после копирования. " +
                    "Самый быстрый путь: в клиенте GGPoker у PokerCraft выберите «Поделиться» или " +
                    "«Открыть в браузере» и в списке приложений укажите PLO Vault — ссылка придёт " +
                    "сюда сама. Либо скопируйте адрес вида my.pokercraft.com/?token=… и нажмите " +
                    "«Вставить и открыть». Ссылка остаётся только на телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Адрес с токеном") },
                singleLine = false,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenPortal != null) {
                    Button(onClick = {
                        val text = clipboard.getText()?.text?.trim()
                        if (!text.isNullOrBlank()) {
                            save(text)
                            onOpenPortal()
                        } else {
                            onSaved("Буфер обмена пуст")
                        }
                    }) { Text("Вставить и открыть") }
                }
                OutlinedButton(onClick = {
                    val text = clipboard.getText()?.text?.trim()
                    if (!text.isNullOrBlank()) save(text) else onSaved("Буфер обмена пуст")
                }) { Text("Вставить") }
                OutlinedButton(onClick = { save(link) }) { Text("Сохранить") }
            }
            StatLine("Токен", Prefs.maskToken(token))
            StatLine("Обновлён", ago(savedAt))
        }
    }
}
