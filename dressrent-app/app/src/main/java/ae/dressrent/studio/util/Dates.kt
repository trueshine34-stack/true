package ae.dressrent.studio.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val MONTHS_GEN = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря"
)

private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

object Dates {
    fun today(): LocalDate = LocalDate.now()

    fun epochDay(date: LocalDate): Long = date.toEpochDay()

    /** "14 мая" — the form used inside sentences and message templates. */
    fun human(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]}"

    fun humanWithYear(date: LocalDate): String = "${human(date)} ${date.year}"

    fun short(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM"))

    fun weekday(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

    fun range(start: LocalDate, end: LocalDate): String =
        if (start == end) human(start) else "${human(start)} — ${human(end)}"

    fun relative(date: LocalDate, from: LocalDate = today()): String = when (
        ChronoUnit.DAYS.between(from, date)
    ) {
        0L -> "сегодня"
        1L -> "завтра"
        2L -> "послезавтра"
        -1L -> "вчера"
        else -> human(date)
    }

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

    fun plural(n: Long, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
