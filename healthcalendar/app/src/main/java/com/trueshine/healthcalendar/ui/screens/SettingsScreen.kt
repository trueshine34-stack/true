package com.trueshine.healthcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trueshine.healthcalendar.ui.HealthUiState
import com.trueshine.healthcalendar.ui.components.SectionTitle
import com.trueshine.healthcalendar.ui.formatDate
import com.trueshine.healthcalendar.ui.formatPreciseMoney
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SettingsScreen(
    ui: HealthUiState,
    onQuitDateChange: (Instant) -> Unit,
    onCigarettesPerDayChange: (Int) -> Unit,
    onPricePerPackChange: (Double) -> Unit,
    onCigarettesPerPackChange: (Int) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onReset: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state = ui.state
    var showDatePicker by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("Дата отказа")
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = state.quitAt
                        ?.atZone(ZoneId.systemDefault())
                        ?.toLocalDate()
                        ?.let(::formatDate)
                        ?: "Не выбрана",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "От неё считаются все дни и вехи. Срывы раньше этой даты будут удалены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text("Изменить дату")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Привычка и расходы")
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                NumberField(
                    label = "Сигарет в день",
                    value = state.cigarettesPerDay.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.let(onCigarettesPerDayChange)
                    },
                )
                Spacer(Modifier.height(12.dp))
                NumberField(
                    label = "Сигарет в пачке",
                    value = state.cigarettesPerPack.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.let(onCigarettesPerPackChange)
                    },
                )
                Spacer(Modifier.height(12.dp))
                NumberField(
                    label = "Цена пачки",
                    value = formatPrice(state.pricePerPack),
                    decimal = true,
                    onValueChange = { text ->
                        text.replace(',', '.').toDoubleOrNull()?.let(onPricePerPackChange)
                    },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.currency,
                    onValueChange = onCurrencyChange,
                    label = { Text("Символ валюты") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Одна сигарета ≈ ${formatPreciseMoney(state.pricePerCigarette, state.currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Данные")
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Всё хранится только на устройстве. Приложение не выходит в сеть.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showResetDialog = true }) {
                    Text("Сбросить трекер")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Календарь здоровья 1.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDatePicker) {
        QuitDatePickerDialog(
            initialDate = state.quitAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
                ?: LocalDate.now(),
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                showDatePicker = false
                onQuitDateChange(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Сбросить трекер?") },
            text = { Text("Дата отказа, настройки и все отметки срывов будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset()
                }) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    decimal: Boolean = false,
) {
    // Держим локальный текст, чтобы поле не «дёргалось», пока пользователь стирает цифры.
    var text by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatPrice(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
