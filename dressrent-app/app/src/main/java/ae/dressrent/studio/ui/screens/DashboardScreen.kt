package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.ActionKind
import ae.dressrent.studio.data.DashboardState
import ae.dressrent.studio.data.RevenueAction
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppCard
import ae.dressrent.studio.ui.components.GoalBar
import ae.dressrent.studio.ui.components.Pill
import ae.dressrent.studio.ui.components.SectionHeader
import ae.dressrent.studio.ui.components.StatTile
import ae.dressrent.studio.ui.components.WeekRevenueChart
import ae.dressrent.studio.ui.theme.Danger
import ae.dressrent.studio.ui.theme.Success
import ae.dressrent.studio.ui.theme.Warning
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Intents
import ae.dressrent.studio.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: StudioViewModel, nav: NavHostController) {
    val state by vm.dashboard.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Сегодня", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${Dates.humanWithYear(state.date)} · ${state.settings.businessName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val (title, body) = vm.previewBrief()
                        Intents.toast(context, title)
                        ae.dressrent.studio.work.Notifications.showBrief(context, title, body)
                    }) { Icon(Icons.Default.NotificationsActive, "Сводка") }
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("booking/edit/0") },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Бронь") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { GoalCard(state) }
            item { StatsRow(state) }
            item {
                AppCard(Modifier.padding(horizontal = 16.dp)) {
                    Text("Выручка за 7 дней", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    WeekRevenueChart(
                        week = state.week,
                        goal = state.goal,
                        currency = state.settings.currency
                    )
                }
            }

            item {
                SectionHeader(
                    title = "План действий",
                    subtitle = if (state.actions.isEmpty()) "Задач нет"
                    else "${state.actions.size} ${Dates.plural(state.actions.size.toLong(), "задача", "задачи", "задач")} · " +
                        "потенциал ${Money.format(state.potentialToday, state.settings.currency)}"
                )
            }

            if (state.actions.isEmpty()) {
                item {
                    AppCard(Modifier.padding(horizontal = 16.dp)) {
                        Text("Пока задач нет", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Добавьте гардероб и первые брони — приложение само начнёт " +
                                "подсказывать, кому написать сегодня, чтобы закрыть план.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.navigate("booking/edit/0") }) { Text("Новая бронь") }
                            TextButton(onClick = { nav.navigate("wardrobe") }) { Text("Гардероб") }
                        }
                    }
                }
            }

            items(state.actions, key = { it.id }) { action ->
                ActionCard(
                    action = action,
                    currency = state.settings.currency,
                    onWhatsApp = {
                        if (action.phone.isNullOrBlank()) {
                            Intents.share(context, action.message)
                        } else {
                            Intents.openWhatsApp(
                                context, action.phone, action.message, state.settings.countryCode
                            )
                        }
                        if (action.kind == ActionKind.REVIEW) {
                            action.bookingId?.let(vm::markReviewRequested)
                        }
                    },
                    onShare = { Intents.share(context, action.message) },
                    onOpen = action.bookingId?.let { id -> { nav.navigate("booking/$id") } }
                )
            }

            if (state.pickups.isNotEmpty() || state.returnsDue.isNotEmpty()) {
                item { SectionHeader("Расписание дня") }
                items(state.pickups, key = { "p-${it.booking.id}" }) { card ->
                    ScheduleRow(
                        label = "Выдача",
                        title = "${card.clientName} · ${card.itemTitle}",
                        detail = if (card.outstanding > 0)
                            "К оплате ${Money.format(card.outstanding, state.settings.currency)}"
                        else "Оплачено",
                        color = Warning,
                        onClick = { nav.navigate("booking/${card.booking.id}") }
                    )
                }
                items(state.returnsDue, key = { "r-${it.booking.id}" }) { card ->
                    ScheduleRow(
                        label = "Возврат",
                        title = "${card.clientName} · ${card.itemTitle}",
                        detail = "Залог ${Money.format(card.booking.deposit, state.settings.currency)}",
                        color = Success,
                        onClick = { nav.navigate("booking/${card.booking.id}") }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalCard(state: DashboardState) {
    val s = state.settings
    AppCard(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.goalReached) "План дня закрыт" else "План дня",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Pill(
                text = if (state.goalReached) "Готово" else "Осталось ${Money.formatShort(state.gap, s.currency)}",
                color = if (state.goalReached) Success.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.secondaryContainer,
                textColor = if (state.goalReached) Success else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Money.format(state.earnedToday, s.currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "  из ${Money.format(state.goal, s.currency)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "${Money.usd(state.earnedToday, s.aedPerUsd)} из ${Money.usd(state.goal, s.aedPerUsd)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        GoalBar(progress = state.progress, reached = state.goalReached)
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                state.goalReached -> "Дальше — всё сверх плана. Подтвердите ближайшие заявки, чтобы завтра не начинать с нуля."
                state.potentialToday > 0 -> "В работе ${Money.format(state.potentialToday, s.currency)}. " +
                    "Этого хватает, чтобы закрыть план — нужно только написать."
                else -> "Денег в работе нет. Ниже — что сделать, чтобы день не остался пустым."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatsRow(state: DashboardState) {
    val s = state.settings
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatTile(
            label = "Неделя",
            value = Money.formatShort(state.weekTotal, s.currency),
            hint = Money.usd(state.weekTotal, s.aedPerUsd),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "Месяц",
            value = Money.formatShort(state.monthTotal, s.currency),
            hint = Money.usd(state.monthTotal, s.aedPerUsd),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "Забронировано",
            value = Money.formatShort(state.bookedAhead, s.currency),
            hint = "на 14 дней",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionCard(
    action: RevenueAction,
    currency: String,
    onWhatsApp: () -> Unit,
    onShare: () -> Unit,
    onOpen: (() -> Unit)?
) {
    val accent = when (action.kind) {
        ActionKind.OVERDUE -> Danger
        ActionKind.COLLECT, ActionKind.HAND_OVER -> Warning
        ActionKind.RETURN_DUE -> Success
        else -> MaterialTheme.colorScheme.secondary
    }

    AppCard(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bolt, null, Modifier.height(18.dp), tint = accent)
            Spacer(Modifier.height(0.dp))
            Pill(
                text = action.kind.label,
                color = accent.copy(alpha = 0.14f),
                textColor = accent,
                modifier = Modifier.padding(start = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            if (action.potential > 0) {
                Text(
                    "+${Money.formatShort(action.potential, currency)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(action.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(3.dp))
        Text(
            action.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onWhatsApp) {
                Icon(Icons.Default.Chat, null, Modifier.height(17.dp))
                Text("  Написать", maxLines = 1)
            }
            FilledTonalButton(onClick = onShare) {
                Icon(Icons.Default.Share, null, Modifier.height(17.dp))
            }
            if (onOpen != null) {
                TextButton(onClick = onOpen) { Text("Открыть") }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    label: String,
    title: String,
    detail: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    AppCard(Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(label, color.copy(alpha = 0.14f), color)
            Spacer(Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

