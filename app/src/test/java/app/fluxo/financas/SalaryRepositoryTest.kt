package app.fluxo.financas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.fluxo.financas.data.Repo
import app.fluxo.financas.salary.PayrollCalculator2026
import app.fluxo.financas.salary.PayrollDiscount
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.salary.VacationConfig
import app.fluxo.financas.util.Dates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FluxoApp::class)
class SalaryRepositoryTest {

    @Test
    fun `salary config and vacation round trip`(): Unit = runBlocking {
        val repo = Repo.get(ApplicationProvider.getApplicationContext<Context>())
        repo.wipe()
        repo.clearSalaryHistory()
        val config = SalaryConfig(
            grossCents = 600000,
            dependents = 1,
            firstPercent = 30,
            firstDay = 19,
            secondDay = 0,
            secondNextMonth = true,
            secondBusinessDay = true,
            secondBusinessDayNumber = 5,
            thirteenthFirstDay = 15,
            thirteenthSecondDay = 10,
            discounts = listOf(
                PayrollDiscount("a", "Plano de saúde", value = 30000),
                PayrollDiscount("b", "Sindicato", percent = true, value = 100)
            )
        )
        val ym = Dates.currentMonth()
        repo.saveSalaryConfig(config, ym)
        val loaded = repo.salaryConfig(ym)
        assertEquals(600000L, loaded.grossCents)
        assertEquals(1, loaded.dependents)
        assertEquals(2, loaded.discounts.size)
        assertEquals(100L, loaded.discounts[1].value)
        assertTrue(loaded.secondNextMonth)
        assertTrue(loaded.secondBusinessDay)
        assertEquals(5, loaded.secondBusinessDayNumber)
        assertEquals(15, loaded.thirteenthFirstDay)
        assertEquals(10, loaded.thirteenthSecondDay)

        repo.saveVacation(ym, VacationConfig(days = 20, sellTenDays = true))
        val vacation = repo.vacation(ym)
        assertEquals(20, vacation.days)
        assertTrue(vacation.sellTenDays)

        val payroll = PayrollCalculator2026.computeMonth(loaded, ym.monthValue, vacation)
        val created = repo.launchSalary(ym, loaded, payroll)
        assertTrue("created=$created", created >= 2)
        val salaryTxns = repo.transactions(ym, "Salário") + repo.transactions(ym.plusMonths(1), "Salário")
        assertTrue(salaryTxns.any { it.merchant.contains("1ª parcela") })
        assertTrue(salaryTxns.all { !it.isExpense })
        assertEquals(payroll.totalNet, salaryTxns.sumOf { it.amountCents })
        assertEquals(0, repo.launchSalary(ym, loaded, payroll))
        repo.wipe()
        repo.clearSalaryHistory()
    }

    @Test
    fun `second installment launches on fifth business day of next month`(): Unit = runBlocking {
        val repo = Repo.get(ApplicationProvider.getApplicationContext<Context>())
        repo.wipe()
        repo.clearSalaryHistory()
        val ref = java.time.YearMonth.of(2026, 11)
        val config = SalaryConfig(
            grossCents = 600000,
            secondNextMonth = true,
            secondBusinessDay = true,
            secondBusinessDayNumber = 5
        )
        repo.saveSalaryConfig(config, ref)
        val payroll = PayrollCalculator2026.computeMonth(config, ref.monthValue, VacationConfig())
        repo.launchSalary(ref, config, payroll)

        val december = repo.transactions(java.time.YearMonth.of(2026, 12))
        val second = requireNotNull(december.firstOrNull { it.merchant.contains("2ª parcela") })
        val date = java.time.Instant.ofEpochMilli(second.date)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 12, 7), date)

        val nov30 = java.time.LocalDate.of(2026, 11, 30).atTime(23, 59)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(
            payroll.firstInstallment + payroll.thirteenthThisMonth,
            repo.balanceUntil(nov30)
        )
        val dec31 = java.time.LocalDate.of(2026, 12, 31).atTime(23, 59)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(payroll.totalNet, repo.balanceUntil(dec31))
        repo.wipe()
        repo.clearSalaryHistory()
    }

    @Test
    fun `thirteenth launches on configured day`(): Unit = runBlocking {
        val repo = Repo.get(ApplicationProvider.getApplicationContext<Context>())
        repo.wipe()
        repo.clearSalaryHistory()
        val ref = java.time.YearMonth.of(2026, 11)
        val config = SalaryConfig(
            grossCents = 600000,
            thirteenthFirstDay = 15,
            thirteenthSecondDay = 10
        )
        repo.saveSalaryConfig(config, ref)
        val payroll = PayrollCalculator2026.computeMonth(config, ref.monthValue, VacationConfig())
        repo.launchSalary(ref, config, payroll)

        val nov = repo.transactions(ref)
        val thirteenth = requireNotNull(nov.firstOrNull { it.merchant.contains("13º") })
        val date = java.time.Instant.ofEpochMilli(thirteenth.date)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 11, 15), date)
        repo.wipe()
        repo.clearSalaryHistory()
    }

    @Test
    fun `salary changes do not rewrite past months`(): Unit = runBlocking {
        val repo = Repo.get(ApplicationProvider.getApplicationContext<Context>())
        repo.clearSalaryHistory()
        val jan = java.time.YearMonth.of(2026, 1)
        val jun = java.time.YearMonth.of(2026, 6)
        repo.saveSalaryConfig(SalaryConfig(grossCents = 500000), jan)
        repo.saveSalaryConfig(SalaryConfig(grossCents = 700000), jun)

        assertEquals(500000L, repo.salaryConfig(java.time.YearMonth.of(2026, 2)).grossCents)
        assertEquals(500000L, repo.salaryConfig(java.time.YearMonth.of(2026, 5)).grossCents)
        assertEquals(700000L, repo.salaryConfig(java.time.YearMonth.of(2026, 6)).grossCents)
        assertEquals(700000L, repo.salaryConfig(java.time.YearMonth.of(2026, 12)).grossCents)
        assertEquals(0L, repo.salaryConfig(java.time.YearMonth.of(2025, 12)).grossCents)
        assertEquals(jan, repo.salaryVersion(java.time.YearMonth.of(2026, 3)).effective)
        repo.clearSalaryHistory()
    }
}
