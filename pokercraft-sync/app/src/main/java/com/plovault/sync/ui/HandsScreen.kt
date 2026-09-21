package com.plovault.sync.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plovault.sync.data.HandRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HandsScreen(state: AppState, modifier: Modifier = Modifier) {
    var rows by remember { mutableStateOf<List<HandRow>>(emptyList()) }
    var limit by remember { mutableStateOf(200) }
    var stake by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.refreshTick, limit, stake, state.filter) {
        rows = withContext(Dispatchers.IO) {
            state.repo.hands(state.filter.copy(stake = stake), limit = limit)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = stake == null, onClick = { stake = null }, label = { Text("Все лимиты") })
            state.stakes.forEach { s ->
                FilterChip(selected = stake == s, onClick = { stake = s }, label = { Text(s) })
            }
        }

        if (rows.isEmpty()) {
            Text(
                "Раздач пока нет. Выгрузите историю из PokerCraft на вкладке «Синхро».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.handId }) { h -> HandCard(h) }
            item {
                if (rows.size >= limit) {
                    OutlinedButton(
                        onClick = { limit += 200 },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) { Text("Показать ещё") }
                }
            }
        }
    }
}

@Composable
private fun HandCard(h: HandRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${h.position}  ${h.cards}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    money(h.net, h.currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (h.net >= 0) PositiveColor else NegativeColor
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${h.stake} · ${dateTime(h.ts)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${bb(h.netBb)} bb",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (h.board.isNotBlank()) {
                Text(
                    "борд ${h.board}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
