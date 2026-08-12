package com.trueshine.healthcalendar.domain

import java.time.Duration

/** Веха восстановления организма после отказа от курения. */
data class Milestone(
    val after: Duration,
    val title: String,
    val description: String,
)

/** Прогресс по вехе для текущей серии без курения. */
data class MilestoneProgress(
    val milestone: Milestone,
    /** 0f..1f */
    val fraction: Float,
    val reached: Boolean,
)

/**
 * Ориентиры восстановления по данным ВОЗ и NHS о сроках, за которые организм
 * приходит в норму после последней сигареты.
 */
object Milestones {

    val all: List<Milestone> = listOf(
        Milestone(
            after = Duration.ofMinutes(20),
            title = "20 минут",
            description = "Пульс и давление возвращаются к обычным значениям.",
        ),
        Milestone(
            after = Duration.ofHours(8),
            title = "8 часов",
            description = "Уровень угарного газа в крови падает вдвое, кислорода становится больше.",
        ),
        Milestone(
            after = Duration.ofHours(24),
            title = "1 день",
            description = "Угарный газ выведен полностью, лёгкие начинают избавляться от слизи.",
        ),
        Milestone(
            after = Duration.ofHours(48),
            title = "2 дня",
            description = "Никотин выведен из организма, возвращаются вкус и обоняние.",
        ),
        Milestone(
            after = Duration.ofHours(72),
            title = "3 дня",
            description = "Бронхи расслабляются, дышать становится заметно легче, появляется энергия.",
        ),
        Milestone(
            after = Duration.ofDays(14),
            title = "2 недели",
            description = "Улучшается кровообращение, физические нагрузки даются проще.",
        ),
        Milestone(
            after = Duration.ofDays(30),
            title = "1 месяц",
            description = "Кожа выглядит лучше, уменьшаются одышка и утренний кашель.",
        ),
        Milestone(
            after = Duration.ofDays(90),
            title = "3 месяца",
            description = "Функция лёгких выросла примерно на 10%.",
        ),
        Milestone(
            after = Duration.ofDays(270),
            title = "9 месяцев",
            description = "Реснички в лёгких восстановились, инфекции цепляются реже.",
        ),
        Milestone(
            after = Duration.ofDays(365),
            title = "1 год",
            description = "Риск ишемической болезни сердца снизился вдвое по сравнению с курильщиком.",
        ),
        Milestone(
            after = Duration.ofDays(365 * 5),
            title = "5 лет",
            description = "Риск инсульта сравнялся с риском некурящего человека.",
        ),
        Milestone(
            after = Duration.ofDays(365 * 10),
            title = "10 лет",
            description = "Риск рака лёгкого вдвое ниже, чем у продолжающего курить.",
        ),
        Milestone(
            after = Duration.ofDays(365 * 15),
            title = "15 лет",
            description = "Риск сердечно-сосудистых заболеваний как у никогда не курившего.",
        ),
    )

    fun progressFor(streak: Duration): List<MilestoneProgress> = all.map { milestone ->
        val fraction = (streak.seconds.toDouble() / milestone.after.seconds)
            .coerceIn(0.0, 1.0)
            .toFloat()
        MilestoneProgress(
            milestone = milestone,
            fraction = fraction,
            reached = streak >= milestone.after,
        )
    }

    /** Ближайшая недостигнутая веха — её показываем на главном экране. */
    fun next(streak: Duration): MilestoneProgress? =
        progressFor(streak).firstOrNull { !it.reached }
}
