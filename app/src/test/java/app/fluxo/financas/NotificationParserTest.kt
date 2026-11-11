package app.fluxo.financas

import app.fluxo.financas.notify.NotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    private fun parse(body: String, title: String = "") =
        NotificationParser.parse(title, body)

    @Test
    fun `nubank debit purchase`() {
        val r = requireNotNull(parse("Compra aprovada no crédito de R$ 29,90 em iFood"))
        assertEquals(2990L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertTrue(r.directionKnown)
        assertEquals("iFood", r.merchant)
        assertEquals("Alimentação", r.category)
        assertEquals("Crédito", r.method)
        assertTrue("confidence ${r.confidence}", r.confidence >= 0.7f)
    }

    @Test
    fun `pix sent`() {
        val r = requireNotNull(parse("Você fez um Pix de R$ 150,00 para Maria Souza"))
        assertEquals(15000L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertTrue(r.directionKnown)
        assertEquals("Maria Souza", r.merchant)
        assertEquals("Pix", r.method)
    }

    @Test
    fun `pix received from company`() {
        val r = requireNotNull(parse("Você recebeu uma transferência de R$ 1.200,00 de Empresa XPTO"))
        assertEquals(120000L, r!!.amountCents)
        assertFalse(r.isExpense)
        assertEquals("Empresa XPTO", r.merchant)
        assertEquals("Outras receitas", r.category)
    }

    @Test
    fun `debit purchase at bakery`() {
        val r = requireNotNull(parse("Compra no débito de R$ 12,50 na Padaria Estrela"))
        assertEquals(1250L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertEquals("Padaria Estrela", r.merchant)
        assertEquals("Alimentação", r.category)
    }

    @Test
    fun `credit card invoice payment is an expense`() {
        val r = requireNotNull(parse("Pagamento de fatura recebido de R$ 1.850,00"))
        assertEquals(185000L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertTrue(r.directionKnown)
    }

    @Test
    fun `promotion is ignored`() {
        assertNull(parse("Ganhe R$ 50,00 de cashback na sua próxima compra"))
    }

    @Test
    fun `closed invoice is ignored`() {
        assertNull(parse("Sua fatura fechou em R$ 2.340,00"))
    }

    @Test
    fun `itau fuel purchase`() {
        val r = requireNotNull(parse("Compra aprovada: R$ 35,90 em POSTO IPIRANGA"))
        assertEquals(3590L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertEquals("Transporte", r.category)
        assertTrue(r.merchant.uppercase().contains("IPIRANGA") || r.merchant == "Posto")
    }

    @Test
    fun `bradesco pix sent`() {
        val r = requireNotNull(parse("Pix enviado de R$ 200,00 para Carlos"))
        assertEquals(20000L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertEquals("Carlos", r.merchant)
    }

    @Test
    fun `inter pix received`() {
        val r = requireNotNull(parse("Você recebeu um Pix de R$ 75,50 de Ana Paula"))
        assertEquals(7550L, r!!.amountCents)
        assertFalse(r.isExpense)
        assertEquals("Ana Paula", r.merchant)
    }

    @Test
    fun `c6 installment purchase`() {
        val r = requireNotNull(parse("Compra aprovada no crédito de R$ 1.299,00 em 3x na Centauro"))
        assertEquals(129900L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertEquals("Compras", r.category)
        assertEquals("Centauro", r.merchant)
    }

    @Test
    fun `subscription`() {
        val r = requireNotNull(parse("Assinatura Netflix R$ 55,90"))
        assertEquals(5590L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertEquals("Assinaturas", r.category)
        assertEquals("Netflix", r.merchant)
    }

    @Test
    fun `cashback is income`() {
        val r = requireNotNull(parse("Você recebeu R$ 5,00 de cashback"))
        assertEquals(500L, r!!.amountCents)
        assertFalse(r.isExpense)
        assertEquals("Outras receitas", r.category)
    }

    @Test
    fun `withdrawal without merchant`() {
        val r = requireNotNull(parse("Saque de R$ 100,00 realizado"))
        assertEquals(10000L, r!!.amountCents)
        assertTrue(r.isExpense)
        assertEquals("Saque", r.merchant)
    }

    @Test
    fun `notification without money is ignored`() {
        assertNull(parse("Bem-vindo ao seu novo cartão"))
        assertNull(parse("Seu código de verificação é 482913"))
    }

    @Test
    fun `ambiguous notification has low confidence`() {
        val r = requireNotNull(parse("Transferência de R$ 100,00 realizada"))
        assertFalse(r!!.directionKnown)
        assertTrue("confidence ${r.confidence}", r.confidence < 0.7f)
    }

    @Test
    fun `whole reais amount`() {
        val r = requireNotNull(parse("Compra aprovada no débito de R$ 1.234 em Mercado Livre"))
        assertEquals(123400L, r!!.amountCents)
        assertEquals("Compras", r.category)
    }

    @Test
    fun `looking like money`() {
        assertTrue(NotificationParser.looksLikeMoney("Compra de R$ 10,00"))
        assertFalse(NotificationParser.looksLikeMoney("Novo limite disponível"))
    }
}
