package com.trueshine.healthcalendar.domain

import com.trueshine.healthcalendar.data.SmokeFreeState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SmokeFreeStatsTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun instantAt(date: String, time: String = "00:00") =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()

    private fun state(
        quitDate: String,
        relapses: Set<String> = emptySet(),
        cigarettesPerDay: Int = 20,
    ) = SmokeFreeState(
        quitAt = instantAt(quitDate),
        cigarettesPerDay = cigarettesPerDay,
        pricePerPack = 200.0,
        cigarettesPerPack = 20,
        relapseDays = relapses.map { LocalDate.parse(it) }.toSet(),
    )

    @Test
    fun `без срывов серия равна времени с отказа`() {
        val stats = state("2026-01-01").statsAt(instantAt("2026-01-11", "12:00"), zone)

        assertEquals(10, stats.currentStreak.toDays())
        assertEquals(11, stats.totalDays)
        assertEquals(11, stats.smokeFreeDays)
        assertEquals(0, stats.relapseCount)
    }

    @Test
    fun `срыв обрывает серию и не входит в дни без курения`() {
        val stats = state("2026-01-01", relapses = setOf("2026-01-05"))
            .statsAt(instantAt("2026-01-11", "12:00"), zone)

        // Серия идёт с полуночи 6 января.
        assertEquals(5, stats.currentStreak.toDays())
        assertEquals(11, stats.totalDays)
        assertEquals(10, stats.smokeFreeDays)
        assertEquals(1, stats.relapseCount)
    }

    @Test
    fun `лучшая серия учитывает промежуток до срыва`() {
        val stats = state("2026-01-01", relapses = setOf("2026-01-09"))
            .statsAt(instantAt("2026-01-11", "12:00"), zone)

        // 1-8 января — 8 дней, после срыва только 10 и 11 января.
        assertEquals(8, stats.bestStreakDays)
    }

    @Test
    fun `экономия считается за вычетом дней срывов`() {
        val stats = state("2026-01-01", relapses = setOf("2026-01-05"))
            .statsAt(instantAt("2026-01-11"), zone)

        // 10 суток минус один день срыва = 9 дней по 20 сигарет.
        assertEquals(180, stats.cigarettesAvoided)
        assertEquals(1800.0, stats.moneySaved, 0.01)
    }

    @Test
    fun `без даты отказа статистика пустая`() {
        val stats = SmokeFreeState().statsAt(instantAt("2026-01-11"), zone)

        assertEquals(SmokeFreeStats.EMPTY, stats)
    }

    @Test
    fun `вехи отмечаются достигнутыми по текущей серии`() {
        val stats = state("2026-01-01").statsAt(instantAt("2026-01-04"), zone)
        val reached = Milestones.progressFor(stats.currentStreak).count { it.reached }

        // 72 часа: пройдены все вехи вплоть до «3 дня».
        assertEquals(5, reached)
    }
}
