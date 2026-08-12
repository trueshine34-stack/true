package com.trueshine.threadsposter

import com.trueshine.threadsposter.data.db.ScheduleEntity
import com.trueshine.threadsposter.data.db.ScheduleMode
import com.trueshine.threadsposter.domain.SlotCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SlotCalculatorTest {

    private val zone = "Europe/Moscow"

    private fun millis(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `daily times produce one slot per listed time`() {
        val schedule = ScheduleEntity(
            id = 1,
            accountId = 1,
            mode = ScheduleMode.DAILY_TIMES,
            timesCsv = "09:00, 18:30",
            daysMask = 0b1111111,
        )
        val from = millis("2026-03-02T00:00:00")
        val to = millis("2026-03-03T00:00:00")

        val slots = SlotCalculator.slots(schedule, zone, from, to)

        assertEquals(listOf(millis("2026-03-02T09:00:00"), millis("2026-03-02T18:30:00")), slots)
    }

    @Test
    fun `disabled weekday is skipped`() {
        // 2 марта 2026 — понедельник, включены только выходные.
        val schedule = ScheduleEntity(
            id = 2,
            accountId = 1,
            timesCsv = "12:00",
            daysMask = 0b1100000,
        )
        val slots = SlotCalculator.slots(
            schedule, zone,
            millis("2026-03-02T00:00:00"),
            millis("2026-03-03T00:00:00"),
        )
        assertTrue(slots.isEmpty())
    }

    @Test
    fun `jitter is deterministic across recalculations`() {
        val schedule = ScheduleEntity(
            id = 7,
            accountId = 1,
            timesCsv = "10:00",
            jitterMinutes = 20,
        )
        val from = millis("2026-03-02T00:00:00")
        val to = millis("2026-03-05T00:00:00")

        val first = SlotCalculator.slots(schedule, zone, from, to)
        val second = SlotCalculator.slots(schedule, zone, from, to)

        assertEquals(first, second)
        assertEquals(3, first.size)
        first.forEach { slot ->
            val base = slot - (slot % 60_000)
            assertTrue(base > from && base < to)
        }
    }

    @Test
    fun `interval mode fills the window`() {
        val schedule = ScheduleEntity(
            id = 3,
            accountId = 1,
            mode = ScheduleMode.INTERVAL,
            windowStart = "09:00",
            windowEnd = "15:00",
            intervalMinutes = 180,
        )
        val slots = SlotCalculator.slots(
            schedule, zone,
            millis("2026-03-02T00:00:00"),
            millis("2026-03-03T00:00:00"),
        )
        assertEquals(
            listOf(
                millis("2026-03-02T09:00:00"),
                millis("2026-03-02T12:00:00"),
                millis("2026-03-02T15:00:00"),
            ),
            slots
        )
    }

    @Test
    fun `slots strictly after the start moment`() {
        val schedule = ScheduleEntity(id = 4, accountId = 1, timesCsv = "10:00")
        val from = millis("2026-03-02T10:00:00")
        val slots = SlotCalculator.slots(schedule, zone, from, millis("2026-03-03T11:00:00"))
        assertEquals(listOf(millis("2026-03-03T10:00:00")), slots)
    }

    @Test
    fun `weekly post count matches days and times`() {
        val schedule = ScheduleEntity(
            id = 5,
            accountId = 1,
            timesCsv = "09:00,15:00,21:00",
            daysMask = 0b0011111,
        )
        assertEquals(15, SlotCalculator.postsPerDay(schedule))
    }
}
