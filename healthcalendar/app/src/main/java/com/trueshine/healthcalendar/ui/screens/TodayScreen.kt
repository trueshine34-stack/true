package com.trueshine.healthcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SmokeFree
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trueshine.healthcalendar.domain.Milestones
import com.trueshine.healthcalendar.ui.HealthUiState
import com.trueshine.healthcalendar.ui.components.StatCard
import com.trueshine.healthcalendar.ui.components.StreakRing
import com.trueshine.healthcalendar.ui.daysWord
import com.trueshine.healthcalendar.ui.formatClock
import com.trueshine.healthcalendar.ui.formatCount
import com.trueshine.healthcalendar.ui.formatDate
import com.trueshine.healthcalendar.ui.formatDaysWithWord
import com.trueshine.healthcalendar.ui.formatLifeRegained
import com.trueshine.healthcalendar.ui.formatMoney
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TodayScreen(
    ui: HealthUiState,
    onToggleTodayRelapse: (LocalDate) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val stats = ui.stats
    val state = ui.state
    val today = LocalDate.now()
    val isTodayRelapse = today in state.relapseDays
    val nextMilestone = Milestones.next(stats.currentStreak)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        StreakRing(
            days = stats.currentStreak.toDays(),
            daysLabel = "${daysWord(stats.currentStreak.toDays())} без курения",
            caption = formatClock(stats.currentStreak),
            progress = nextMilestone?.fraction ?: 1f,
        )

        Spacer(Modifier.height(16.dp))

        if (nextMilestone != null) {
            NextMilestoneCard(
                title = nextMilestone.milestone.title,
                description = nextMilestone.milestone.description,
                fraction = nextMilestone.fraction,
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                icon = Icons.Filled.SmokeFree,
                value = formatCount(stats.cigarettesAvoided),
                label = "Не выкурено",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Filled.Savings,
                value = formatMoney(stats.moneySaved, state.currency),
                label = "Сэкономлено",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                icon = Icons.Filled.Favorite,
                value = formatLifeRegained(stats.lifeRegained),
                label = "Времени жизни",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Filled.Timeline,
                value = formatDaysWithWord(stats.bestStreakDays.toLong()),
                label = "Лучшая серия",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        SummaryCard(
            quitDate = state.quitAt
                ?.atZone(ZoneId.systemDefault())
                ?.toLocalDate()
                ?.let(::formatDate)
                .orEmpty(),
            totalDays = stats.totalDays,
            smokeFreeDays = stats.smokeFreeDays,
            relapseCount = stats.relapseCount,
        )

        Spacer(Modifier.height(16.dp))

        if (isTodayRelapse) {
            Button(
                onClick = { onToggleTodayRelapse(today) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Отменить срыв за сегодня")
            }
        } else {
            OutlinedButton(
                onClick = { onToggleTodayRelapse(today) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Отметить срыв сегодня")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Срыв — не провал, а один день из статистики. Счётчик серии начнётся заново, " +
                "но общий прогресс сохранится.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NextMilestoneCard(title: String, description: String, fraction: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Следующая веха: $title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    quitDate: String,
    totalDays: Int,
    smokeFreeDays: Int,
    relapseCount: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SummaryRow("Дата отказа", quitDate)
            Spacer(Modifier.height(8.dp))
            SummaryRow("Всего дней в трекере", formatDaysWithWord(totalDays.toLong()))
            Spacer(Modifier.height(8.dp))
            SummaryRow("Из них без курения", formatDaysWithWord(smokeFreeDays.toLong()))
            Spacer(Modifier.height(8.dp))
            SummaryRow("Отмечено срывов", relapseCount.toString())
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
