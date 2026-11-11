package app.fluxo.financas

import app.fluxo.financas.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parses brazilian formats`() {
        assertEquals(123456L, Money.parse("R$ 1.234,56"))
        assertEquals(123456L, Money.parse("1234,56"))
        assertEquals(123456L, Money.parse("1234.56"))
        assertEquals(2490L, Money.parse("24,90"))
        assertEquals(1250L, Money.parse("12,5"))
        assertEquals(123400L, Money.parse("1.234"))
        assertEquals(1200L, Money.parse("R$ 12"))
        assertEquals(-3000L, Money.parse("-30,00"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(null))
    }

    @Test
    fun `formats brl`() {
        val formatted = Money.format(123456L)
        assertEquals(true, formatted.contains("1.234,56"))
        assertEquals(true, formatted.contains("R$"))
        assertEquals("1.234,56", Money.formatPlain(123456L))
    }
}
