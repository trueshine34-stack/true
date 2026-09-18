package ae.dressrent.studio

import ae.dressrent.studio.data.ActionKind
import ae.dressrent.studio.data.DashboardState
import ae.dressrent.studio.data.RevenueAction
import ae.dressrent.studio.data.Settings
import ae.dressrent.studio.work.BriefComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BriefComposerTest {

    private val settings = Settings(dailyGoal = 18_400)
    private val date = LocalDate.of(2026, 5, 14)

    private fun action(kind: ActionKind, potential: Long = 0) = RevenueAction(
        id = "a-${kind.name}-$potential",
        kind = kind,
        title = "Мария — заявка без оплаты",
        subtitle = "",
        message = "",
        phone = "971500000000",
        potential = potential
    )

    @Test
    fun `an empty day names the gap, not a vague reminder`() {
        val (title, body) = BriefComposer.compose(
            DashboardState(date = date, settings = settings, earnedToday = 0)
        )
        assertEquals("До плана дня — 184 AED", title)
        assertTrue(body.contains("Задач нет"))
    }

    @Test
    fun `a closed day is reported as closed`() {
        val (title, _) = BriefComposer.compose(
            DashboardState(date = date, settings = settings, earnedToday = 20_000)
        )
        assertTrue(title.startsWith("План дня закрыт"))
    }

    @Test
    fun `the brief sums what the listed tasks are worth`() {
        val state = DashboardState(
            date = date,
            settings = settings,
            earnedToday = 5_000,
            actions = listOf(
                action(ActionKind.LEAD_FOLLOW_UP, 30_000),
                action(ActionKind.COLLECT, 12_000)
            )
        )
        val (title, body) = BriefComposer.compose(state)
        assertEquals("До плана дня — 134 AED", title)
        assertTrue(body.contains("420 AED"))
        assertTrue(body.contains("Заявок ждут ответа: 1."))
    }

    @Test
    fun `only the three most urgent tasks reach the notification`() {
        val state = DashboardState(
            date = date,
            settings = settings,
            actions = List(6) { action(ActionKind.REVIEW, it * 1_000L) }
        )
        val (_, body) = BriefComposer.compose(state)
        assertEquals(3, body.lines().count { it.startsWith("• ") })
    }
}
