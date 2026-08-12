package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.data.db.ScheduleEntity
import com.trueshine.threadsposter.data.db.ScheduleMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Переводит расписание в конкретные моменты публикации.
 *
 * Джиттер детерминированный (сид зависит от расписания и слота), иначе при
 * каждом пересчёте один и тот же слот получал бы новое время и в очереди
 * появлялись бы дубли.
 */
object SlotCalculator {

    fun slots(
        schedule: ScheduleEntity,
        zoneId: String,
        fromMillis: Long,
        toMillis: Long,
    ): List<Long> {
        if (!schedule.enabled || toMillis <= fromMillis) return emptyList()
        val zone = Time.zone(zoneId)
        val startDate = dateAt(fromMillis, zone)
        val endDate = dateAt(toMillis, zone)

        val result = mutableListOf<Long>()
        var date = startDate.minusDays(1) // запас на сдвиг джиттером через полночь
        while (!date.isAfter(endDate)) {
            if (isDayEnabled(schedule.daysMask, date)) {
                for (time in timesForDay(schedule)) {
                    val base = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    val shifted = base + jitterMillis(schedule, base)
                    if (shifted in (fromMillis + 1)..toMillis) result += shifted
                }
            }
            date = date.plusDays(1)
        }
        return result.distinct().sorted()
    }

    /** Ближайший слот после указанного момента — для превью в интерфейсе. */
    fun nextSlot(schedule: ScheduleEntity, zoneId: String, fromMillis: Long): Long? {
        val horizon = fromMillis + 31L * 24 * 3600 * 1000
        return slots(schedule, zoneId, fromMillis, horizon).firstOrNull()
    }

    fun timesForDay(schedule: ScheduleEntity): List<LocalTime> = when (schedule.mode) {
        ScheduleMode.INTERVAL -> intervalTimes(schedule)
        else -> parseTimes(schedule.timesCsv)
    }

    private fun intervalTimes(schedule: ScheduleEntity): List<LocalTime> {
        val step = schedule.intervalMinutes.coerceAtLeast(5)
        val start = Time.parseTime(schedule.windowStart) ?: LocalTime.of(9, 0)
        val end = Time.parseTime(schedule.windowEnd) ?: LocalTime.of(22, 0)
        if (end <= start) return listOf(start)
        val times = mutableListOf<LocalTime>()
        var cursor = start
        while (cursor <= end && times.size < MAX_SLOTS_PER_DAY) {
            times += cursor
            cursor = cursor.plusMinutes(step.toLong())
            if (cursor.isBefore(start)) break // защита от перехода через полночь
        }
        return times
    }

    fun parseTimes(csv: String): List<LocalTime> = csv
        .split(',', ';', '\n')
        .mapNotNull { Time.parseTime(it) }
        .distinct()
        .sorted()
        .take(MAX_SLOTS_PER_DAY)

    /** Бит 0 — понедельник, бит 6 — воскресенье. */
    fun isDayEnabled(mask: Int, date: LocalDate): Boolean {
        val bit = date.dayOfWeek.value - 1
        return (mask shr bit) and 1 == 1
    }

    private fun jitterMillis(schedule: ScheduleEntity, baseMillis: Long): Long {
        val jitter = schedule.jitterMinutes
        if (jitter <= 0) return 0
        val seed = schedule.id * 1_000_003L + baseMillis / 60_000L
        val offset = Random(seed).nextInt(-jitter, jitter + 1)
        return offset * 60_000L
    }

    fun daysMaskToText(mask: Int): String {
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        if (mask and 0b1111111 == 0b1111111) return "Каждый день"
        if (mask and 0b1111111 == 0b0011111) return "Будни"
        if (mask and 0b1111111 == 0b1100000) return "Выходные"
        val active = names.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
        return if (active.isEmpty()) "Дни не выбраны" else active.joinToString(", ")
    }

    fun describe(schedule: ScheduleEntity): String {
        val days = daysMaskToText(schedule.daysMask)
        return when (schedule.mode) {
            ScheduleMode.INTERVAL ->
                "$days, каждые ${schedule.intervalMinutes} мин с ${schedule.windowStart} до ${schedule.windowEnd}"
            else -> {
                val times = parseTimes(schedule.timesCsv).joinToString(", ") {
                    "%02d:%02d".format(it.hour, it.minute)
                }
                "$days в ${times.ifBlank { "—" }}"
            }
        } + if (schedule.jitterMinutes > 0) " ±${schedule.jitterMinutes} мин" else ""
    }

    fun postsPerDay(schedule: ScheduleEntity): Int {
        val perDay = timesForDay(schedule).size
        val days = (0..6).count { (schedule.daysMask shr it) and 1 == 1 }
        return perDay * days
    }

    /** LocalDate.ofInstant появился только в Java 9, на Android его нет. */
    private fun dateAt(millis: Long, zone: ZoneId): LocalDate =
        java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).toLocalDate()

    private const val MAX_SLOTS_PER_DAY = 48
}
