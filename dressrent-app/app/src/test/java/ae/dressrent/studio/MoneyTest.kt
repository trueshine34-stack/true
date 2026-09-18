package ae.dressrent.studio

import ae.dressrent.studio.util.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `formats whole amounts without decimals`() {
        assertEquals("350 AED", Money.format(35_000))
        assertEquals("1 250 AED", Money.format(125_000))
        assertEquals("0 AED", Money.format(0))
    }

    @Test
    fun `keeps fils when they matter`() {
        assertEquals("350,50 AED", Money.format(35_050))
        assertEquals("-120 AED", Money.format(-12_000))
    }

    @Test
    fun `parses what a phone keyboard produces`() {
        assertEquals(35_000L, Money.parse("350"))
        assertEquals(35_050L, Money.parse("350.5"))
        assertEquals(35_050L, Money.parse("350,50"))
        assertEquals(0L, Money.parse(""))
        assertEquals(0L, Money.parse("не число"))
    }

    @Test
    fun `round trips through the edit field`() {
        listOf(0L, 35_000L, 35_050L, 1_234_567L).forEach { minor ->
            val text = Money.toInput(minor)
            if (minor != 0L) assertEquals(minor, Money.parse(text))
        }
    }

    @Test
    fun `fifty dollars is the pegged AED goal`() {
        // 184 AED is the default daily goal; at the peg it must read back as about $50.
        assertEquals("$50", Money.usd(18_400))
    }
}
