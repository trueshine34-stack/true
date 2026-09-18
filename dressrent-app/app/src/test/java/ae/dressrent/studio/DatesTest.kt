package ae.dressrent.studio

import ae.dressrent.studio.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DatesTest {

    @Test
    fun `russian plural forms follow the 11-14 exception`() {
        assertEquals("день", Dates.plural(1, "день", "дня", "дней"))
        assertEquals("дня", Dates.plural(3, "день", "дня", "дней"))
        assertEquals("дней", Dates.plural(5, "день", "дня", "дней"))
        assertEquals("дней", Dates.plural(11, "день", "дня", "дней"))
        assertEquals("дней", Dates.plural(14, "день", "дня", "дней"))
        assertEquals("день", Dates.plural(21, "день", "дня", "дней"))
        assertEquals("дня", Dates.plural(102, "день", "дня", "дней"))
    }

    @Test
    fun `dates read the way a message should`() {
        val date = LocalDate.of(2026, 5, 14)
        assertEquals("14 мая", Dates.human(date))
        assertEquals("14 мая 2026", Dates.humanWithYear(date))
        assertEquals("чт", Dates.weekday(date))
    }

    @Test
    fun `relative dates stay conversational near today`() {
        val today = LocalDate.of(2026, 5, 14)
        assertEquals("сегодня", Dates.relative(today, today))
        assertEquals("завтра", Dates.relative(today.plusDays(1), today))
        assertEquals("вчера", Dates.relative(today.minusDays(1), today))
        assertEquals("20 мая", Dates.relative(today.plusDays(6), today))
    }

    @Test
    fun `a single day range is not printed twice`() {
        val day = LocalDate.of(2026, 5, 14)
        assertEquals("14 мая", Dates.range(day, day))
        assertEquals("14 мая — 16 мая", Dates.range(day, day.plusDays(2)))
    }
}
