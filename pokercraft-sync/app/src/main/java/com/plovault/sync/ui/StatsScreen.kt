package com.plovault.sync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plovault.sync.data.Agg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatsScreen(state: AppState, modifier: Modifier = Modifier) {
    var stake by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf(Agg()) }
    var curve by remember { mutableStateOf<List<Double>>(emptyList()) }
    var byStake by remember { mutableStateOf<List<Pair<String, Agg>>>(emptyList()) }
    var byPos by remember { mutableStateOf<List<Pair<String, Agg>>>(emptyList()) }
    var byMonth by remember { mutableStateOf<List<Pair<String, Agg>>>(emptyList()) }

    LaunchedEffect(state.refreshTick, stake, state.filter) {
        val f = state.filter.copy(stake = stake)
        withContext(Dispatchers.IO) {
            total = state.repo.aggregate(f)
            curve = state.repo.equityCurve(f)
            byStake = state.repo.groupBy(f.copy(stake = null), "stake")
            byPos = state.repo.groupBy(f, "position").sortedBy { positionOrder(it.first) }
            byMonth = state.repo.groupBy(f, "month").sortedBy { it.first }
        }
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = stake == null, onClick = { stake = null }, label = { Text("Все лимиты") })
            state.stakes.forEach { s ->
                FilterChip(selected = stake == s, onClick = { stake = s }, label = { Text(s) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (state.prefs.onlyPlo4Rush) "PLO4 Rush & Cash" else "Все игры",
                    style = MaterialTheme.typography.titleMedium
                )
                StatLine("Раздач", total.hands.toString())
                StatLine("Результат", money(total.net))
                StatLine("В блайндах", "${bb(total.netBb)} bb")
                StatLine("Винрейт", "${rate(total.bb100)} bb/100")
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                StatLine("VPIP", pct(total.vpipPct))
                StatLine("PFR", pct(total.pfrPct))
                StatLine("3-бет", pct(total.threeBetPct))
                StatLine("Видел флоп", pct(total.sawFlopPct))
                StatLine("WTSD", pct(total.wtsdPct))
                StatLine("W\$SD", pct(total.wsdPct))
                StatLine("Выигрышей после флопа", pct(total.wwsfPct))
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                StatLine("Рейк", money(total.rake))
                StatLine("Джекпот/сборы", money(total.fees))
                StatLine("Ва-банк раздач", total.allIn.toString())
                StatLine("Лучшая рука", money(total.bestHand))
                StatLine("Худшая рука", money(total.worstHand))
                StatLine("Период", "${dateOnly(total.firstTs)} — ${dateOnly(total.lastTs)}")
            }
        }

        if (curve.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("График в bb", style = MaterialTheme.typography.titleSmall)
                    EquityCurve(curve, Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp))
                }
            }
        }

        StatsTable("По лимитам", byStake)
        StatsTable("По позициям", byPos)
        StatsTable("По месяцам", byMonth)
    }
}

private fun positionOrder(p: String): Int =
    listOf("UTG", "UTG1", "UTG2", "MP", "MP1", "CO", "BTN", "BTN/SB", "SB", "BB")
        .indexOf(p).let { if (it < 0) 99 else it }

@Composable
private fun StatsTable(title: String, rows: List<Pair<String, Agg>>) {
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                HeaderCell("", 2.2f)
                HeaderCell("рук", 1f)
                HeaderCell("$", 1.2f)
                HeaderCell("bb/100", 1.2f)
            }
            HorizontalDivider()
            rows.forEach { (label, a) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    BodyCell(label, 2.2f)
                    BodyCell(a.hands.toString(), 1f)
                    BodyCell(money(a.net), 1.2f, a.net)
                    BodyCell(rate(a.bb100), 1.2f, a.bb100)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BodyCell(text: String, weight: Float, value: Double? = null) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = when {
            value == null -> MaterialTheme.colorScheme.onSurface
            value >= 0 -> PositiveColor
            else -> NegativeColor
        }
    )
}

@Composable
private fun EquityCurve(points: List<Double>, modifier: Modifier) {
    val positive = PositiveColor
    val negative = NegativeColor
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val min = minOf(points.min(), 0.0)
        val max = maxOf(points.max(), 0.0)
        val range = (max - min).takeIf { it > 0.0001 } ?: 1.0
        val dx = size.width / (points.size - 1)
        fun y(v: Double) = (size.height - ((v - min) / range * size.height)).toFloat()

        drawLine(grid.copy(alpha = 0.3f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)

        val path = Path().apply {
            moveTo(0f, y(points[0]))
            points.forEachIndexed { i, v -> lineTo(i * dx, y(v)) }
        }
        drawPath(path, color = if (points.last() >= 0) positive else negative, style = Stroke(width = 3f))
    }
}
