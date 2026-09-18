package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.BookingCard
import ae.dressrent.studio.data.BookingStatus
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppCard
import ae.dressrent.studio.ui.components.EmptyState
import ae.dressrent.studio.ui.components.Pill
import ae.dressrent.studio.ui.theme.Danger
import ae.dressrent.studio.ui.theme.Success
import ae.dressrent.studio.ui.theme.Warning
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingsScreen(vm: StudioViewModel, nav: NavHostController) {
    val bookings by vm.bookings.collectAsState()
    val settings by vm.settings.collectAsState()
    var filter by remember { mutableStateOf<BookingStatus?>(null) }

    val visible = remember(bookings, filter) {
        if (filter == null) bookings else bookings.filter { it.booking.status == filter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Брони") },
                actions = {
                    Text(
                        Money.formatShort(
                            bookings.filter { it.booking.status.blocksCalendar }.sumOf { it.booking.price },
                            settings.currency
                        ) + " в календаре",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("booking/edit/0") }) {
                Icon(Icons.Default.Add, "Новая бронь")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text("Все") }
                )
                BookingStatus.entries.forEach { status ->
                    FilterChip(
                        selected = filter == status,
                        onClick = { filter = status },
                        label = { Text(status.label) }
                    )
                }
            }

            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = if (bookings.isEmpty()) "Броней пока нет" else "В этом статусе пусто",
                    text = if (bookings.isEmpty())
                        "Занесите сюда каждую заявку — даже ту, что ещё не оплатили. Именно из них приложение делает задачи на день."
                    else "Смените фильтр или создайте новую бронь.",
                    actionLabel = "Создать бронь",
                    onAction = { nav.navigate("booking/edit/0") }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.booking.id }) { card ->
                        BookingRow(card, settings.currency) { nav.navigate("booking/${card.booking.id}") }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingRow(card: BookingCard, currency: String, onClick: () -> Unit) {
    val status = card.booking.status
    val color = when (status) {
        BookingStatus.LEAD -> Warning
        BookingStatus.CONFIRMED -> MaterialTheme.colorScheme.secondary
        BookingStatus.OUT -> MaterialTheme.colorScheme.tertiary
        BookingStatus.RETURNED -> Success
        BookingStatus.CANCELLED -> Danger
    }

    AppCard(Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(status.label, color.copy(alpha = 0.14f), color)
            Spacer(Modifier.weight(1f))
            Text(
                Money.format(card.booking.price, currency),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(card.clientName, style = MaterialTheme.typography.titleMedium)
        Text(
            card.itemTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Dates.range(card.booking.start, card.booking.end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (card.outstanding > 0 && status != BookingStatus.CANCELLED) {
                Text(
                    "долг ${Money.formatShort(card.outstanding, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning
                )
            }
        }
    }
}
