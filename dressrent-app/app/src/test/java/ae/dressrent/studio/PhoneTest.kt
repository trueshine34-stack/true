package ae.dressrent.studio

import ae.dressrent.studio.util.Intents
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneTest {

    @Test
    fun `local UAE numbers get the country code`() {
        assertEquals("971501234567", Intents.normalizePhone("0501234567", "971"))
        assertEquals("971501234567", Intents.normalizePhone("50 123 45 67", "971"))
    }

    @Test
    fun `numbers already in international form are left alone`() {
        assertEquals("971501234567", Intents.normalizePhone("+971 50 123 45 67", "971"))
        assertEquals("971501234567", Intents.normalizePhone("971501234567", "971"))
    }

    @Test
    fun `a foreign number keeps its own country code`() {
        assertEquals("79161234567", Intents.normalizePhone("+7 916 123 45 67", "971"))
    }

    @Test
    fun `an empty field does not produce a bogus number`() {
        assertEquals("", Intents.normalizePhone("", "971"))
        assertEquals("", Intents.normalizePhone("—", "971"))
    }
}
