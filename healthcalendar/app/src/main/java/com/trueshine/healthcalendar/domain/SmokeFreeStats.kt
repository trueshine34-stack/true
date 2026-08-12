package com.trueshine.healthcalendar.domain

import com.trueshine.healthcalendar.data.SmokeFreeState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** Готовые к показу цифры трекера на конкретный момент времени. */
data class SmokeFreeStats(
    /** Прошло с момента отказа. */
    val sinceQuit: Duration,
    /** Прошло с последнего срыва (или с отказа, если срывов не было). */
    val currentStreak: Duration,
    /** Календарных дней с даты отказа, включая сегодня. */
    val totalDays: Int,
    /** Дней без единой сигареты. */
    val smokeFreeDays: Int,
    /** Отмеченных срывов. */
    val relapseCount: Int,
    /** Самая длинная серия дней подряд без курения. */
    val bestStreakDays: Int,
    val cigarettesAvoided: Int,
    val moneySaved: Double,
    /** Не потраченное на курение время жизни. */
    val lifeRegained: Duration,
) {
    val currentStreakDays: Int get() = currentStreak.toDays().toInt()

    companion object {
        /** Минут жизни на одну невыкуренную сигарету (усреднённая оценка). */
        const val MINUTES_PER_CIGARETTE = 11L

        val EMPTY = SmokeFreeStats(
            sinceQuit = Duration.ZERO,
            currentStreak = Duration.ZERO,
            totalDays = 0,
            smokeFreeDays = 0,
            relapseCount = 0,
            bestStreakDays = 0,
            cigarettesAvoided = 0,
            moneySaved = 0.0,
            lifeRegained = Duration.ZERO,
        )
    }
}

/**
 * Считает статистику на момент [now].
 *
 * Срыв учитывается как целый день курения: он не входит в дни без курения,
 * обрывает серию и вычитается из сэкономленных сигарет и денег.
 */
fun SmokeFreeState.statsAt(
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): SmokeFreeStats {
    val quitAt = quitAt ?: return SmokeFreeStats.EMPTY

    val sinceQuit = Duration.between(quitAt, now).coerceAtLeast(Duration.ZERO)
    val quitDay = quitAt.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    if (today < quitDay) return SmokeFreeStats.EMPTY

    val totalDays = (ChronoUnit.DAYS.between(quitDay, today) + 1).toInt()
    val relapses = relapseDays.filter { it in quitDay..today }.sorted()
    val smokeFreeDays = totalDays - relapses.size

    // Текущая серия идёт от полуночи следующего за срывом дня.
    val lastRelapse = relapses.lastOrNull()
    val streakStart = if (lastRelapse == null) {
        quitAt
    } else {
        lastRelapse.plusDays(1).atStartOfDay(zone).toInstant()
    }
    val currentStreak = Duration.between(streakStart, now).coerceAtLeast(Duration.ZERO)

    // Экономия считается непрерывным временем, за вычетом целых дней срывов.
    val elapsedDays = sinceQuit.toMillis() / MILLIS_PER_DAY.toDouble()
    val effectiveDays = max(0.0, elapsedDays - relapses.size)
    val cigarettesAvoided = (cigarettesPerDay * effectiveDays).toInt()

    return SmokeFreeStats(
        sinceQuit = sinceQuit,
        currentStreak = currentStreak,
        totalDays = totalDays,
        smokeFreeDays = smokeFreeDays,
        relapseCount = relapses.size,
        bestStreakDays = bestStreakDays(quitDay, today, relapses),
        cigarettesAvoided = cigarettesAvoided,
        moneySaved = cigarettesAvoided * pricePerCigarette,
        lifeRegained = Duration.ofMinutes(cigarettesAvoided * SmokeFreeStats.MINUTES_PER_CIGARETTE),
    )
}

/** Самый длинный промежуток между срывами в днях. */
private fun bestStreakDays(
    quitDay: LocalDate,
    today: LocalDate,
    relapses: List<LocalDate>,
): Int {
    var best = 0
    var segmentStart = quitDay
    for (relapse in relapses) {
        best = max(best, ChronoUnit.DAYS.between(segmentStart, relapse).toInt())
        segmentStart = relapse.plusDays(1)
    }
    best = max(best, (ChronoUnit.DAYS.between(segmentStart, today) + 1).toInt())
    return max(best, 0)
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
