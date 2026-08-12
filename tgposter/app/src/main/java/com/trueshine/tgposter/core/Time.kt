package com.trueshine.tgposter.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Time {

    private val dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))
    private val fullFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale("ru"))
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))

    fun zone(id: String?): ZoneId =
        runCatching { ZoneId.of(id ?: "") }.getOrElse { ZoneId.systemDefault() }

    fun format(epochMillis: Long, zoneId: String? = null): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone(zoneId)).format(dateTimeFmt)

    fun formatFull(epochMillis: Long, zoneId: String? = null): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone(zoneId)).format(fullFmt)

    fun formatTime(epochMillis: Long, zoneId: String? = null): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone(zoneId)).format(timeFmt)

    /** "09:05" -> LocalTime, некорректные значения игнорируются. */
    fun parseTime(value: String): LocalTime? = runCatching {
        val parts = value.trim().split(":")
        LocalTime.of(parts[0].trim().toInt(), parts.getOrNull(1)?.trim()?.toInt() ?: 0)
    }.getOrNull()

    fun humanDelta(fromMillis: Long, toMillis: Long): String {
        val diff = toMillis - fromMillis
        val abs = kotlin.math.abs(diff)
        val minutes = abs / 60_000
        val text = when {
            minutes < 1 -> "меньше минуты"
            minutes < 60 -> "$minutes мин"
            minutes < 60 * 24 -> "${minutes / 60} ч ${minutes % 60} мин"
            else -> "${minutes / (60 * 24)} дн ${(minutes % (60 * 24)) / 60} ч"
        }
        return if (diff >= 0) "через $text" else "$text назад"
    }

    val allZoneIds: List<String> by lazy {
        ZoneId.getAvailableZoneIds().sorted()
    }

    /** Часто используемые зоны наверх списка. */
    val commonZoneIds = listOf(
        "Europe/Moscow", "Europe/Kaliningrad", "Europe/Samara", "Europe/Kyiv",
        "Europe/Minsk", "Europe/Warsaw", "Europe/Berlin", "Europe/London", "Europe/Lisbon",
        "Asia/Almaty", "Asia/Tashkent", "Asia/Tbilisi", "Asia/Yerevan", "Asia/Baku",
        "Asia/Dubai", "Asia/Bangkok", "Asia/Yekaterinburg", "Asia/Novosibirsk",
        "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Vladivostok",
        "America/New_York", "America/Los_Angeles", "UTC"
    )
}
