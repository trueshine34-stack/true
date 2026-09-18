package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.Item
import ae.dressrent.studio.data.ItemStat
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppCard
import ae.dressrent.studio.ui.components.EmptyState
import ae.dressrent.studio.ui.components.Pill
import ae.dressrent.studio.ui.theme.Success
import ae.dressrent.studio.util.Money
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardrobeScreen(vm: StudioViewModel, nav: NavHostController) {
    val items by vm.items.collectAsState()
    val stats by vm.itemStats.collectAsState()
    val settings by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Гардероб") },
                actions = {
                    Text(
                        "${items.count { it.active }} в работе",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("item/edit/0") }) {
                Icon(Icons.Default.Add, "Добавить образ")
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Checkroom,
                title = "Гардероб пуст",
                text = "Добавьте образы с ценой аренды и залогом. Приложение будет считать, " +
                    "сколько каждое платье уже заработало и когда оно окупится.",
                actionLabel = "Добавить образ",
                onAction = { nav.navigate("item/edit/0") },
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { item ->
                ItemRow(
                    item = item,
                    stat = stats.firstOrNull { it.id == item.id },
                    currency = settings.currency,
                    onClick = { nav.navigate("item/edit/${item.id}") }
                )
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, stat: ItemStat?, currency: String, onClick: () -> Unit) {
    AppCard(Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (item.photoUri.isNullOrBlank()) {
                    Icon(Icons.Default.Checkroom, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AsyncImage(
                        model = item.photoUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!item.active) Pill("В архиве")
                }
                Text(
                    listOfNotNull(
                        item.brand.takeIf { it.isNotBlank() },
                        item.category.label,
                        item.size.takeIf { it.isNotBlank() }?.let { "размер $it" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Money.format(item.pricePerDay, currency)} / день",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (stat != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Сдано ${stat.rentals} раз · заработано ${Money.formatShort(stat.revenue, currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (stat.purchaseCost > 0) {
                        val payback = (stat.revenue.toFloat() / stat.purchaseCost).coerceIn(0f, 1f)
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = { payback },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50)),
                            color = if (payback >= 1f) Success else MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (payback >= 1f) "Окупилось"
                            else "Окупаемость ${(payback * 100).toInt()}% от ${Money.formatShort(stat.purchaseCost, currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
