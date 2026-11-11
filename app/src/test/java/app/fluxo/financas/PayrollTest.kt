package app.fluxo.financas

import app.fluxo.financas.salary.PayrollCalculator2026
import app.fluxo.financas.salary.PayrollDiscount
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.salary.VacationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollTest {

    @Test
    fun `inss 2026 progressive`() {
        assertEquals(7500L, PayrollCalculator2026.inss(100000))
        assertEquals(12158L, PayrollCalculator2026.inss(162100))
        assertEquals(50151L, PayrollCalculator2026.inss(500000))
        assertEquals(64151L, PayrollCalculator2026.inss(600000))
        assertEquals(98809L, PayrollCalculator2026.inss(847555))
        assertEquals(98809L, PayrollCalculator2026.inss(1500000))
    }

    @Test
    fun `irrf 2026 exemption up to 5000`() {
        val inss = PayrollCalculator2026.inss(500000)
        val irrf = PayrollCalculator2026.irrf(500000, inss, 0)
        assertEquals(0L, irrf.tax)
        assertEquals(31289L, irrf.reduction)
        assertTrue(irrf.usedSimplified)
    }

    @Test
    fun `irrf between 5000 and 7350`() {
        val inss = PayrollCalculator2026.inss(600000)
        val irrf = PayrollCalculator2026.irrf(600000, inss, 0)
        assertEquals(17975L, irrf.reduction)
        assertEquals(38510L, irrf.tax)
    }

    @Test
    fun `irrf above 7350 has no reduction`() {
        val inss = PayrollCalculator2026.inss(800000)
        val irrf = PayrollCalculator2026.irrf(800000, inss, 0)
        assertEquals(0L, irrf.reduction)
        assertTrue(irrf.tax > 100000L)
    }

    @Test
    fun `monthly split 30 70 with irrf on first`() {
        val cfg = SalaryConfig(grossCents = 600000, dependents = 0)
        val m = PayrollCalculator2026.computeMonth(cfg, 11, VacationConfig())
        assertEquals(600000L, m.salaryBase)
        assertEquals(64151L, m.inss)
        assertEquals(38510L, m.irrf.tax)
        assertEquals(180000L - 38510L, m.firstInstallment)
        assertEquals(420000L - 64151L, m.secondInstallment)
        assertEquals(497339L, m.netSalary)
    }

    @Test
    fun `fixed and percent discounts`() {
        val cfg = SalaryConfig(
            grossCents = 600000,
            discounts = listOf(
                PayrollDiscount("1", "Plano de saúde", value = 30000),
                PayrollDiscount("2", "Sindicato", percent = true, value = 100)
            )
        )
        val m = PayrollCalculator2026.computeMonth(cfg, 11, VacationConfig())
        assertEquals(36000L, m.discounts)
        assertEquals(497339L - 36000L, m.netSalary)
    }

    @Test
    fun `thirteenth two installments`() {
        val cfg = SalaryConfig(grossCents = 600000)
        val m = PayrollCalculator2026.computeMonth(cfg, 11, VacationConfig())
        val t = requireNotNull(m.thirteenth)
        assertEquals(600000L, t.grossCents)
        assertEquals(64151L, t.inss)
        assertEquals(38510L, t.irrf)
        assertEquals(300000L, t.firstInstallment)
        assertEquals(600000L - 300000L - 64151L - 38510L, t.secondInstallment)
        assertEquals(300000L, m.thirteenthThisMonth)
        assertEquals(m.netSalary + 300000L, m.totalNet)
    }

    @Test
    fun `vacation 30 days with third and no abono`() {
        val cfg = SalaryConfig(grossCents = 600000)
        val m = PayrollCalculator2026.computeMonth(cfg, 5, VacationConfig(days = 30))
        assertEquals(0L, m.salaryBase)
        assertEquals(600000L, m.vacationBase)
        assertEquals(200000L, m.vacationThird)
        assertEquals(800000L, m.grossTaxable)
        assertEquals(64151L, m.inss)
        assertEquals(0L, m.abonoBase)
        assertEquals(800000L - 64151L - m.irrf.tax, m.netSalary)
    }

    @Test
    fun `vacation with abono is exempt`() {
        val cfg = SalaryConfig(grossCents = 600000)
        val m = PayrollCalculator2026.computeMonth(cfg, 5, VacationConfig(days = 20, sellTenDays = true))
        assertEquals(0L, m.salaryBase)
        assertEquals(400000L, m.vacationBase)
        assertEquals(133333L, m.vacationThird)
        assertEquals(200000L, m.abonoBase)
        assertEquals(66666L, m.abonoThird)
        assertTrue(m.netSalary > 500000L)
    }

    @Test
    fun `second payment date falls back to last day`() {
        val cfg = SalaryConfig()
        assertEquals(java.time.LocalDate.of(2027, 2, 28), PayrollCalculator2026.secondPaymentDate(cfg, java.time.YearMonth.of(2027, 2)))
        assertEquals(java.time.LocalDate.of(2027, 1, 21), PayrollCalculator2026.secondPaymentDate(cfg.copy(secondDay = 21), java.time.YearMonth.of(2027, 1)))
        assertEquals(java.time.LocalDate.of(2027, 1, 31), PayrollCalculator2026.secondPaymentDate(cfg.copy(secondDay = 31), java.time.YearMonth.of(2027, 1)))
    }

    @Test
    fun `second payment on fifth business day of next month`() {
        val cfg = SalaryConfig(secondNextMonth = true, secondBusinessDay = true, secondBusinessDayNumber = 5)
        assertEquals(
            java.time.LocalDate.of(2026, 12, 7),
            PayrollCalculator2026.secondPaymentDate(cfg, java.time.YearMonth.of(2026, 11))
        )
    }

    @Test
    fun `holidays shift business day`() {
        // Nov/2026: dia 2 é feriado e 1º é domingo; o 5º dia útil cai em 09/11.
        assertEquals(
            java.time.LocalDate.of(2026, 11, 9),
            app.fluxo.financas.salary.BrazilianHolidays.nthBusinessDay(java.time.YearMonth.of(2026, 11), 5)
        )
        // Sexta-feira Santa de 2026: 03/04.
        assertEquals(
            java.time.LocalDate.of(2026, 4, 3),
            app.fluxo.financas.salary.BrazilianHolidays.easterSunday(2026).minusDays(2)
        )
    }

    @Test
    fun `payments carry actual dates and sum to total`() {
        val cfg = SalaryConfig(
            grossCents = 600000,
            secondNextMonth = true,
            secondBusinessDay = true,
            secondBusinessDayNumber = 5,
            thirteenthFirstDay = 30,
            thirteenthSecondDay = 20
        )
        val payroll = PayrollCalculator2026.computeMonth(cfg, 11, VacationConfig())
        val payments = PayrollCalculator2026.payments(cfg, java.time.YearMonth.of(2026, 11), payroll)
        assertEquals(3, payments.size)
        assertEquals(java.time.LocalDate.of(2026, 11, 19), payments[0].date)
        assertEquals(java.time.LocalDate.of(2026, 12, 7), payments[1].date)
        assertEquals(java.time.LocalDate.of(2026, 11, 30), payments[2].date)
        assertEquals(payroll.totalNet, payments.sumOf { it.amountCents })
    }

    @Test
    fun `calendar day in next month`() {
        val cfg = SalaryConfig(secondNextMonth = true, secondDay = 5)
        assertEquals(
            java.time.LocalDate.of(2027, 1, 5),
            PayrollCalculator2026.secondPaymentDate(cfg, java.time.YearMonth.of(2026, 12))
        )
    }
}
