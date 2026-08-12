package com.trueshine.tgposter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.tgposter.core.Time
import com.trueshine.tgposter.data.db.PostStatus
import com.trueshine.tgposter.ui.components.SectionCard
import com.trueshine.tgposter.ui.components.StatusChip
import com.trueshine.tgposter.ui.vm.QueueVm
import com.trueshine.tgposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onOpenPost: (Long) -> Unit) {
    val vm: QueueVm = rememberVm("queue") { QueueVm(it) }
    val posts by vm.posts.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Очередь постов") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueVm.Filter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { vm.setFilter(option) },
                        label = { Text(option.title) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Column(Modifier.padding(top = 12.dp)) {} }

                items(posts, key = { it.id }) { post ->
                    val channelTitle = channels.firstOrNull { it.id == post.channelId }?.title ?: "—"
                    SectionCard(modifier = Modifier.clickable { onOpenPost(post.id) }) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(channelTitle, style = MaterialTheme.typography.titleSmall)
                            StatusChip(post.status)
                        }
                        Text(
                            Time.format(post.scheduledAt) + " · " +
                                Time.humanDelta(System.currentTimeMillis(), post.scheduledAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (post.topic.isNotBlank()) {
                            Text(post.topic, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (post.text.isNotBlank()) {
                            Text(
                                post.text.take(220),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        post.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            when (post.status) {
                                PostStatus.QUEUED, PostStatus.FAILED ->
                                    TextButton(onClick = { vm.generateNow(post) }) { Text("Сгенерировать") }
                                PostStatus.NEEDS_APPROVAL ->
                                    TextButton(onClick = { vm.approve(post) }) { Text("Подтвердить") }
                                else -> Unit
                            }
                            if (post.text.isNotBlank() && post.status != PostStatus.PUBLISHED) {
                                TextButton(onClick = { vm.publishNow(post) }) { Text("Опубликовать") }
                            }
                            if (post.status == PostStatus.FAILED || post.status == PostStatus.SKIPPED) {
                                TextButton(onClick = { vm.retry(post) }) { Text("Повторить") }
                            }
                            TextButton(onClick = { onOpenPost(post.id) }) { Text("Открыть") }
                            if (post.status != PostStatus.PUBLISHED) {
                                TextButton(onClick = { vm.cancel(post) }) { Text("Отменить") }
                            }
                            TextButton(onClick = { vm.delete(post) }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (posts.isEmpty()) {
                    item {
                        Text(
                            "Здесь пусто. Посты появятся, когда сработает расписание " +
                                "или вы создадите пост вручную.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { Column(Modifier.padding(bottom = 24.dp)) {} }
            }
        }
    }
}
