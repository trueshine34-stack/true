package com.trueshine.healthcalendar.ui.screens

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Выбор даты отказа. Будущие даты запрещены — счётчик не может идти вперёд.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuitDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    // DatePicker работает в UTC, поэтому конвертируем даты через ZoneOffset.UTC.
    val todayUtcMillis = remember {
        LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val selectableDates = remember(todayUtcMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                utcTimeMillis < todayUtcMillis

            override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
        }
    }

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
        selectableDates = selectableDates,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        onConfirm(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    } else {
                        onDismiss()
                    }
                },
                enabled = pickerState.selectedDateMillis != null,
            ) {
                Text("Готово")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    ) {
        DatePicker(state = pickerState, showModeToggle = false)
    }
}

/** Полночь выбранного дня в часовом поясе устройства. */
fun LocalDate.atLocalStartOfDay(): Instant =
    atStartOfDay(ZoneId.systemDefault()).toInstant()
