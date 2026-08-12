package com.trueshine.threadsposter.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.data.db.LeadStatus
import com.trueshine.threadsposter.ui.components.BusyIndicator
import com.trueshine.threadsposter.ui.components.Field
import com.trueshine.threadsposter.ui.components.SectionCard
import com.trueshine.threadsposter.ui.vm.LeadsVm
import com.trueshine.threadsposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadsScreen(onBack: () -> Unit) {
    val vm: LeadsVm = rememberVm("leads") { LeadsVm(it) }
    val leads by vm.leads.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Находки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LeadsVm.Filter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { vm.setFilter(option) },
                        label = { Text(option.title) },
                    )
                }
            }

            BusyIndicator(busy)

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Column(Modifier.padding(top = 12.dp)) {} }

                items(leads, key = { it.id }) { lead ->
                    var replyText by remember(lead.id, lead.updatedAt) { mutableStateOf(lead.replyText) }

                    SectionCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("@${lead.authorUsername}", style = MaterialTheme.typography.titleSmall)
                            ScoreBadge(lead.score)
                        }
                        Text(
                            (if (lead.queryId == 0L) "Комментарий под нашим постом" else "Найдено поиском") +
                                (lead.postedAt?.let { " · ${Time.format(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(lead.text, style = MaterialTheme.typography.bodyMedium)
                        if (lead.reason.isNotBlank()) {
                            Text(
                                "Оценка: ${lead.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        lead.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            LeadStatus.title(lead.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        if (lead.status == LeadStatus.DRAFTED || lead.status == LeadStatus.NEW) {
                            Field(
                                replyText, { replyText = it },
                                "Наш ответ",
                                hint = "До 400 символов. Пусто — сначала нажмите «Черновик».",
                                singleLine = false, minLines = 3,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.approve(lead, replyText) },
                                    enabled = replyText.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Отправить") }
                                OutlinedButton(onClick = { vm.draft(lead) }, modifier = Modifier.weight(1f)) {
                                    Text(if (lead.replyText.isBlank()) "Черновик" else "Заново")
                                }
                            }
                        } else if (lead.replyText.isNotBlank()) {
                            Text(
                                "Ответ: ${lead.replyText}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (lead.permalink.isNotBlank()) {
                                TextButton(onClick = { openLink(context, lead.permalink) }) {
                                    Text("Открыть в Threads")
                                }
                            }
                            if (lead.status != LeadStatus.SKIPPED && lead.status != LeadStatus.REPLIED) {
                                TextButton(onClick = { vm.skip(lead) }) { Text("Пропустить") }
                            }
                            TextButton(onClick = { vm.delete(lead) }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (leads.isEmpty()) {
                    item {
                        Text(
                            "Здесь пусто. Добавьте поисковый запрос во вкладке «Поиск» " +
                                "или включите ответы на комментарии в настройках аккаунта.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { Column(Modifier.padding(bottom = 32.dp)) {} }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int) {
    val color = when {
        score >= 8 -> MaterialTheme.colorScheme.primary
        score >= 6 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("$score/10", style = MaterialTheme.typography.bodySmall, color = color)
    }
}
