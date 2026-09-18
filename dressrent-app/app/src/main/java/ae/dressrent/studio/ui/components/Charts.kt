package ae.dressrent.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ae.dressrent.studio.data.DayRevenue
import ae.dressrent.studio.data.Minor
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Money
import java.time.LocalDate
import kotlin.math.max

/**
 * Seven days of collected revenue against the daily goal. One series, one colour;
 * the goal is a recessive dashed rule, and only the best day carries a number so the
 * eye lands on the shape, not on a wall of labels.
 */
@Composable
fun WeekRevenueChart(
    week: List<DayRevenue>,
    goal: Minor,
    currency: String,
    modifier: Modifier = Modifier
) {
    if (week.isEmpty()) return

    val bar = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val today = LocalDate.now().toEpochDay()
    val best = week.maxByOrNull { it.total }
    val ceiling = max(week.maxOf { it.total }, goal).coerceAtLeast(1L) * 1.18

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            val count = week.size
            val gap = 6.dp.toPx()
            val barWidth = (size.width - gap * (count - 1)) / count
            val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

            week.forEachIndexed { index, day ->
                val x = index * (barWidth + gap)
                val fraction = (day.total / ceiling).toFloat().coerceIn(0f, 1f)
                val barHeight = max(size.height * fraction, if (day.total > 0) 4.dp.toPx() else 2.dp.toPx())
                val isToday = day.date == today

                drawRoundRect(
                    color = if (day.total > 0) bar.copy(alpha = if (isToday) 1f else 0.55f) else track,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius
                )
            }

            if (goal > 0) {
                val y = size.height * (1f - (goal / ceiling).toFloat()).coerceIn(0f, 1f)
                drawLine(
                    color = muted.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { day ->
                val date = LocalDate.ofEpochDay(day.date)
                Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            Dates.weekday(date),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day.date == today) MaterialTheme.colorScheme.onSurface else muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (best != null && day.date == best.date && best.total > 0) {
                            Text(
                                Money.formatShort(day.total, currency),
                                style = MaterialTheme.typography.labelSmall,
                                color = muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Пунктир — план дня ${Money.formatShort(goal, currency)}",
            style = MaterialTheme.typography.labelSmall,
            color = muted
        )
    }
}

