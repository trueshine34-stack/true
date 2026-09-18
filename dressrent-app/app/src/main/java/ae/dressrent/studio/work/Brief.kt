package ae.dressrent.studio.work

import ae.dressrent.studio.data.ActionKind
import ae.dressrent.studio.data.DashboardState
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Money

/**
 * Turns the dashboard into the two lines of the morning notification. Kept free of Android
 * types so the wording can be covered by plain unit tests.
 */
object BriefComposer {

    fun compose(state: DashboardState): Pair<String, String> {
        val s = state.settings
        val title = if (state.goalReached) {
            "План дня закрыт: ${Money.formatShort(state.earnedToday, s.currency)}"
        } else {
            "До плана дня — ${Money.formatShort(state.gap, s.currency)}"
        }

        val lines = mutableListOf<String>()
        lines += "${Dates.human(state.date)}, ${Dates.weekday(state.date)}. " +
            "Заработано: ${Money.format(state.earnedToday, s.currency)} из ${Money.format(state.goal, s.currency)}."

        if (state.pickups.isNotEmpty()) lines += "Выдачи сегодня: ${state.pickups.size}."
        if (state.returnsDue.isNotEmpty()) lines += "Возвраты сегодня: ${state.returnsDue.size}."
        if (state.overdue.isNotEmpty()) lines += "⚠️ Просрочено возвратов: ${state.overdue.size}."

        val top = state.actions.take(3)
        if (top.isEmpty()) {
            lines += "Задач нет. Хороший момент выложить свободные даты в сторис."
        } else {
            lines += ""
            top.forEach { lines += "• ${it.kind.label}: ${it.title}" }
            val potential = state.actions.sumOf { it.potential }
            if (potential > 0) {
                lines += ""
                lines += "Если закрыть эти задачи — ${Money.format(potential, s.currency)} " +
                    "(${Money.usd(potential, s.aedPerUsd)})."
            }
        }

        val leadCount = state.actions.count { it.kind == ActionKind.LEAD_FOLLOW_UP }
        if (leadCount > 0) lines += "Заявок ждут ответа: $leadCount."

        return title to lines.joinToString("\n")
    }
}
