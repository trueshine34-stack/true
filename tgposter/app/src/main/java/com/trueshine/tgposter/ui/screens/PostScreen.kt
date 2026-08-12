package com.trueshine.tgposter.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.tgposter.core.TelegramText
import com.trueshine.tgposter.core.Time
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.ui.components.Field
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.components.StatusChip
import com.trueshine.tgposter.ui.vm.PostVm
import com.trueshine.tgposter.ui.vm.rememberVm
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(postId: Long, onBack: () -> Unit) {
    val vm: PostVm = rememberVm("post_$postId") { PostVm(it, postId) }
    val post by vm.post.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var regenerateOpen by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(post?.updatedAt) {
        post?.let { if (it.text.isNotBlank() && text.isNullOrBlank()) vm.refreshText() }
        imageUrl = post?.imageUrl.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Пост") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = post
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Column(Modifier.padding(top = 12.dp)) {} }

            if (current == null) {
                item { Text("Пост не найден") }
                return@LazyColumn
            }

            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(channel?.title ?: "Канал", style = MaterialTheme.typography.titleMedium)
                        StatusChip(current.status)
                    }
                    Text(
                        "Публикация: ${Time.formatFull(current.scheduledAt, channel?.timeZone)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (current.topic.isNotBlank()) {
                        Text("Тема: ${current.topic}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (current.model.isNotBlank()) {
                        Text(
                            "Модель: ${current.model} · токенов: ${current.tokensUsed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current.lastError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(
                        onClick = { pickDateTime(context, current.scheduledAt) { vm.setScheduledAt(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Изменить время публикации") }
                }
            }

            item {
                SectionCard("Текст поста") {
                    Field(
                        text.orEmpty(),
                        vm::setText,
                        "Текст",
                        singleLine = false,
                        minLines = 10,
                        hint = "Разметка: ${channel?.parseMode ?: "HTML"}. Символов: " +
                            "${TelegramText.visibleLength(text.orEmpty())}",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::saveText, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                        OutlinedButton(onClick = { regenerateOpen = true }, modifier = Modifier.weight(1f)) {
                            Text("Перегенерировать")
                        }
                    }
                }
            }

            item {
                SectionCard("Картинка") {
                    Field(
                        imageUrl, { imageUrl = it },
                        "URL картинки",
                        hint = "Telegram сам загрузит картинку по прямой ссылке. Оставьте пустым для текстового поста.",
                    )
                    OutlinedButton(onClick = { vm.setImageUrl(imageUrl) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Сохранить картинку")
                    }
                }
            }

            item {
                SectionCard("Публикация") {
                    if (current.status == PostStatus.PUBLISHED) {
                        Text(
                            "Опубликован ${current.publishedAt?.let { Time.formatFull(it, channel?.timeZone) } ?: ""}" +
                                (current.tgMessageId?.let { ", message_id $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Button(
                            onClick = vm::publishNow,
                            enabled = !busy && !text.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Опубликовать сейчас") }
                    }
                }
            }

            if (current.promptSnapshot.isNotBlank()) {
                item {
                    SectionCard("Запрос к модели") {
                        Text(
                            current.promptSnapshot,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Column(Modifier.padding(bottom = 32.dp)) {} }
        }
    }

    if (regenerateOpen) {
        AlertDialog(
            onDismissRequest = { regenerateOpen = false },
            title = { Text("Перегенерировать пост") },
            text = {
                Field(
                    brief, { brief = it }, "Уточнение (необязательно)",
                    hint = "Например: сделай короче и без вопросов в конце",
                    singleLine = false, minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.regenerate(brief.takeIf { it.isNotBlank() })
                    regenerateOpen = false
                }) { Text("Запустить") }
            },
            dismissButton = { TextButton(onClick = { regenerateOpen = false }) { Text("Отмена") } },
        )
    }
}

private fun pickDateTime(
    context: android.content.Context,
    initialMillis: Long,
    onPicked: (Long) -> Unit,
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initialMillis }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    calendar.set(year, month, day, hour, minute, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    onPicked(calendar.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true,
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).show()
}
