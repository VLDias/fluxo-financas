package app.fluxo.financas

import app.fluxo.financas.forecast.ForecastCalculator
import app.fluxo.financas.forecast.MonthlyAverages
import app.fluxo.financas.forecast.MonthlyTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ForecastTest {

    private val current = YearMonth.of(2026, 11)

    @Test
    fun `average uses last three complete months`() {
        val totals = listOf(
            MonthlyTotal(YearMonth.of(2026, 7), 500000, 400000),
            MonthlyTotal(YearMonth.of(2026, 8), 600000, 450000),
            MonthlyTotal(YearMonth.of(2026, 9), 700000, 500000),
            MonthlyTotal(YearMonth.of(2026, 10), 800000, 550000),
            MonthlyTotal(current, 999999, 999999)
        )
        val avg = requireNotNull(ForecastCalculator.average(totals, current))
        assertEquals(3, avg.monthsUsed)
        assertEquals(700000L, avg.incomeCents)
        assertEquals(500000L, avg.expenseCents)
        assertEquals(200000L, avg.monthlyNetCents)
    }

    @Test
    fun `falls back to any history when there is no complete month`() {
        val totals = listOf(MonthlyTotal(current, 500000, 450000))
        val avg = requireNotNull(ForecastCalculator.average(totals, current))
        assertEquals(1, avg.monthsUsed)
        assertEquals(500000L, avg.incomeCents)
    }

    @Test
    fun `no history returns null`() {
        assertNull(ForecastCalculator.average(emptyList(), current))
        assertNull(
            ForecastCalculator.average(
                listOf(MonthlyTotal(YearMonth.of(2026, 10), 0, 0)),
                current
            )
        )
    }

    @Test
    fun `flat projection accumulates monthly surplus`() {
        val result = ForecastCalculator.project(
            startBalanceCents = 100000,
            averages = MonthlyAverages(500000, 400000, 3),
            incomeGrowthBp = 0,
            expenseGrowthBp = 0,
            months = 3,
            fromMonth = current
        )
        assertEquals(3, result.points.size)
        assertEquals(400000L, result.finalCents)
        assertEquals(listOf(200000L, 300000L, 400000L), result.points.map { it.cumulativeCents })
        assertEquals(200000L, result.lowest?.cumulativeCents)
        assertEquals(YearMonth.of(2026, 12), result.points[1].month)
    }

    @Test
    fun `growth compounds`() {
        val result = ForecastCalculator.project(
            startBalanceCents = 0,
            averages = MonthlyAverages(100000, 50000, 3),
            incomeGrowthBp = 1000,
            expenseGrowthBp = 0,
            months = 2,
            fromMonth = current
        )
        // mês 1: 1000 - 500 = 500; mês 2: 1100 - 500 = 600
        assertEquals(50000L, result.points[0].netCents)
        assertEquals(60000L, result.points[1].netCents)
        assertEquals(110000L, result.finalCents)
    }

    @Test
    fun `deficit projection goes negative`() {
        val result = ForecastCalculator.project(
            startBalanceCents = 50000,
            averages = MonthlyAverages(300000, 400000, 3),
            incomeGrowthBp = 0,
            expenseGrowthBp = 0,
            months = 3,
            fromMonth = current
        )
        assertEquals(-250000L, result.finalCents)
        assertTrue(result.points.any { it.cumulativeCents < 0 })
        assertEquals(-250000L, result.lowest?.cumulativeCents)
    }

    @Test
    fun `zero months returns start balance`() {
        val result = ForecastCalculator.project(12345, MonthlyAverages(1, 1, 1), 0, 0, 0, current)
        assertEquals(12345L, result.finalCents)
        assertTrue(result.points.isEmpty())
        assertNull(result.lowest)
    }
}
