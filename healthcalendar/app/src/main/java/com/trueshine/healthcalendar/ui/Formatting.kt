package com.trueshine.healthcalendar.ui

import java.text.NumberFormat
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val ru = Locale("ru", "RU")

/**
 * Русское склонение по числу: 1 день, 2 дня, 5 дней.
 */
fun plural(count: Long, one: String, few: String, many: String): String {
    val n = abs(count) % 100
    val n1 = n % 10
    return when {
        n in 11..19 -> many
        n1 == 1L -> one
        n1 in 2..4 -> few
        else -> many
    }
}

fun daysWord(count: Long): String = plural(count, "день", "дня", "дней")

fun formatDaysWithWord(days: Long): String = "$days ${daysWord(days)}"

/** «12 дней 4 ч 37 мин» — подпись под главным счётчиком. */
fun formatDurationLong(duration: Duration): String {
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return buildString {
        if (days > 0) append("$days ${daysWord(days)} ")
        append("$hours ч $minutes мин")
    }
}

/** «4:37:12» — живой отсчёт внутри текущих суток. */
fun formatClock(duration: Duration): String {
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    val seconds = duration.seconds % 60
    return String.format(ru, "%02d:%02d:%02d", hours, minutes, seconds)
}

/** Время жизни: «3 дня 5 ч» или «47 мин». */
fun formatLifeRegained(duration: Duration): String {
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return when {
        days > 0 -> "$days ${daysWord(days)} $hours ч"
        duration.toHours() > 0 -> "${duration.toHours()} ч $minutes мин"
        else -> "$minutes мин"
    }
}

fun formatMoney(amount: Double, currency: String): String {
    val rounded = amount.roundToLong()
    val formatted = NumberFormat.getIntegerInstance(ru).format(rounded)
    return "$formatted $currency"
}

/** С копейками — для мелких сумм вроде цены одной сигареты. */
fun formatPreciseMoney(amount: Double, currency: String): String {
    val formatted = NumberFormat.getNumberInstance(ru).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(amount)
    return "$formatted $currency"
}

fun formatCount(value: Int): String = NumberFormat.getIntegerInstance(ru).format(value)

private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
private val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", ru)

fun formatDate(date: LocalDate): String = date.format(dateFormatter)

fun formatMonthTitle(date: LocalDate): String =
    date.format(monthFormatter).replaceFirstChar { it.titlecase(ru) }
