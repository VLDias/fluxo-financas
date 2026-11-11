package app.fluxo.financas.forecast

import java.time.YearMonth
import kotlin.math.roundToLong

data class MonthlyTotal(
    val month: YearMonth,
    val incomeCents: Long,
    val expenseCents: Long
)

data class MonthlyAverages(
    val incomeCents: Long,
    val expenseCents: Long,
    val monthsUsed: Int
) {
    val monthlyNetCents: Long get() = incomeCents - expenseCents
}

data class ForecastPoint(
    val month: YearMonth,
    val incomeCents: Long,
    val expenseCents: Long,
    val netCents: Long,
    val cumulativeCents: Long
)

data class ForecastResult(
    val points: List<ForecastPoint>,
    val finalCents: Long,
    val lowest: ForecastPoint?
)

object ForecastCalculator {

    /**
     * Média dos últimos [window] meses completos com movimento.
     * Ignora o mês corrente (parcial). Se não houver histórico anterior, usa o que existir.
     */
    fun average(totals: List<MonthlyTotal>, currentMonth: YearMonth, window: Int = 3): MonthlyAverages? {
        val complete = totals
            .filter { it.month < currentMonth && (it.incomeCents > 0 || it.expenseCents > 0) }
            .sortedBy { it.month }
        val source = if (complete.isNotEmpty()) complete else totals
            .filter { it.incomeCents > 0 || it.expenseCents > 0 }
            .sortedBy { it.month }
        if (source.isEmpty()) return null
        val selected = source.takeLast(window.coerceAtLeast(1))
        return MonthlyAverages(
            incomeCents = selected.sumOf { it.incomeCents } / selected.size,
            expenseCents = selected.sumOf { it.expenseCents } / selected.size,
            monthsUsed = selected.size
        )
    }

    fun project(
        startBalanceCents: Long,
        averages: MonthlyAverages,
        incomeGrowthBp: Int,
        expenseGrowthBp: Int,
        months: Int,
        fromMonth: YearMonth
    ): ForecastResult {
        if (months <= 0) return ForecastResult(emptyList(), startBalanceCents, null)
        var cumulative = startBalanceCents
        var income = averages.incomeCents.toDouble()
        var expense = averages.expenseCents.toDouble()
        val points = mutableListOf<ForecastPoint>()
        for (i in 0 until months) {
            val monthIncome = income.roundToLong()
            val monthExpense = expense.roundToLong()
            val net = monthIncome - monthExpense
            cumulative += net
            points += ForecastPoint(
                month = fromMonth.plusMonths(i.toLong()),
                incomeCents = monthIncome,
                expenseCents = monthExpense,
                netCents = net,
                cumulativeCents = cumulative
            )
            income *= (1.0 + incomeGrowthBp / 10_000.0)
            expense *= (1.0 + expenseGrowthBp / 10_000.0)
        }
        return ForecastResult(
            points = points,
            finalCents = cumulative,
            lowest = points.minByOrNull { it.cumulativeCents }
        )
    }
}
