package com.trueshine.healthcalendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trueshine.healthcalendar.ui.HealthUiState
import com.trueshine.healthcalendar.ui.components.SectionTitle
import com.trueshine.healthcalendar.ui.formatDaysWithWord
import com.trueshine.healthcalendar.ui.formatMonthTitle
import com.trueshine.healthcalendar.ui.theme.RelapseRed
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val weekdayLabels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/** Как приложение красит конкретный день месяца. */
private enum class DayKind { OUTSIDE_TRACKING, SMOKE_FREE, RELAPSE, FUTURE }

@Composable
fun CalendarScreen(
    ui: HealthUiState,
    onToggleRelapse: (LocalDate) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val quitDay = ui.state.quitAt?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: today
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        MonthHeader(
            month = visibleMonth,
            canGoBack = visibleMonth.isAfter(YearMonth.from(quitDay)),
            canGoForward = visibleMonth.isBefore(YearMonth.from(today)),
            onPrevious = { visibleMonth = visibleMonth.minusMonths(1) },
            onNext = { visibleMonth = visibleMonth.plusMonths(1) },
        )

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                WeekdayRow()
                Spacer(Modifier.height(8.dp))
                MonthGrid(
                    month = visibleMonth,
                    quitDay = quitDay,
                    today = today,
                    relapseDays = ui.state.relapseDays,
                    onDayClick = { day ->
                        if (day in quitDay..today) onToggleRelapse(day)
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Legend()

        Spacer(Modifier.height(16.dp))
        SectionTitle("Итого за всё время")
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = formatDaysWithWord(ui.stats.smokeFreeDays.toLong()) + " без курения",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "из ${formatDaysWithWord(ui.stats.totalDays.toLong())} с даты отказа",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Нажмите на день, чтобы отметить или снять срыв.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц")
        }
        Text(
            text = formatMonthTitle(month.atDay(1)),
            style = MaterialTheme.typography.titleLarge,
        )
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц")
        }
    }
}

@Composable
private fun WeekdayRow() {
    Row(Modifier.fillMaxWidth()) {
        weekdayLabels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    quitDay: LocalDate,
    today: LocalDate,
    relapseDays: Set<LocalDate>,
    onDayClick: (LocalDate) -> Unit,
) {
    val firstDay = month.atDay(1)
    // DayOfWeek.MONDAY.value == 1, поэтому пустых ячеек ровно value - 1.
    val leadingBlanks = firstDay.dayOfWeek.value - 1
    val cells = leadingBlanks + month.lengthOfMonth()
    val rows = (cells + 6) / 7

    Column {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val cellIndex = row * 7 + column
                    val dayOfMonth = cellIndex - leadingBlanks + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayOfMonth in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayOfMonth)
                            DayCell(
                                date = date,
                                kind = kindOf(date, quitDay, today, relapseDays),
                                isToday = date == today,
                                onClick = { onDayClick(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun kindOf(
    date: LocalDate,
    quitDay: LocalDate,
    today: LocalDate,
    relapseDays: Set<LocalDate>,
): DayKind = when {
    date > today -> DayKind.FUTURE
    date < quitDay -> DayKind.OUTSIDE_TRACKING
    date in relapseDays -> DayKind.RELAPSE
    else -> DayKind.SMOKE_FREE
}

@Composable
private fun DayCell(
    date: LocalDate,
    kind: DayKind,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val background = when (kind) {
        DayKind.SMOKE_FREE -> MaterialTheme.colorScheme.primary
        DayKind.RELAPSE -> RelapseRed
        DayKind.OUTSIDE_TRACKING, DayKind.FUTURE -> Color.Transparent
    }
    val contentColor = when (kind) {
        DayKind.SMOKE_FREE -> MaterialTheme.colorScheme.onPrimary
        DayKind.RELAPSE -> Color.White
        DayKind.OUTSIDE_TRACKING, DayKind.FUTURE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val clickable = kind == DayKind.SMOKE_FREE || kind == DayKind.RELAPSE

    Box(
        modifier = Modifier
            .size(38.dp)
            .background(background, CircleShape)
            .then(
                if (isToday) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                }
            )
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
        )
    }
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LegendItem(MaterialTheme.colorScheme.primary, "Без курения")
        LegendItem(RelapseRed, "Срыв")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
