package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.BookingStatus
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppCard
import ae.dressrent.studio.ui.components.EmptyState
import ae.dressrent.studio.ui.components.Pill
import ae.dressrent.studio.util.Intents
import ae.dressrent.studio.util.Messages
import ae.dressrent.studio.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(vm: StudioViewModel, nav: NavHostController) {
    val clients by vm.clients.collectAsState()
    val bookings by vm.bookings.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    // Spend per client: who is actually worth a personal message.
    val spend = remember(bookings) {
        bookings
            .filter { it.booking.status != BookingStatus.CANCELLED }
            .groupBy { it.booking.clientId }
            .mapValues { (_, cards) -> cards.sumOf { it.collected } to cards.size }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Клиенты") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("client/edit/0") }) {
                Icon(Icons.Default.Add, "Добавить клиентку")
            }
        }
    ) { padding ->
        if (clients.isEmpty()) {
            EmptyState(
                icon = Icons.Default.People,
                title = "Клиентов пока нет",
                text = "Постоянные клиентки — самый дешёвый источник броней. Записывайте каждую, " +
                    "даже если она пока только спросила цену.",
                actionLabel = "Добавить клиентку",
                onAction = { nav.navigate("client/edit/0") },
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        val sorted = remember(clients, spend) {
            clients.sortedByDescending { spend[it.id]?.first ?: 0L }
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sorted, key = { it.id }) { client ->
                val (total, count) = spend[client.id] ?: (0L to 0)
                AppCard(
                    Modifier.padding(horizontal = 16.dp),
                    onClick = { nav.navigate("client/edit/${client.id}") }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(client.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    client.phone.takeIf { it.isNotBlank() },
                                    client.source.label
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (client.phone.isNotBlank()) {
                            IconButton(onClick = {
                                Intents.openWhatsApp(
                                    context,
                                    client.phone,
                                    Messages.winBack(client, settings, settings.dormantDays.toLong()),
                                    settings.countryCode
                                )
                                vm.touchClient(client.id)
                            }) { Icon(Icons.Default.Chat, "WhatsApp") }
                        }
                    }
                    if (count > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("$count бронь(-и)")
                            Pill(
                                "принесла ${Money.formatShort(total, settings.currency)}",
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
