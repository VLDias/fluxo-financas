package app.fluxo.financas.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Prefs
import app.fluxo.financas.data.Repo
import app.fluxo.financas.forecast.ForecastCalculator
import app.fluxo.financas.forecast.MonthlyAverages
import app.fluxo.financas.forecast.MonthlyTotal
import app.fluxo.financas.salary.MonthPayroll
import app.fluxo.financas.salary.PayrollCalculator2026
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.salary.SalaryPaymentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import app.fluxo.financas.data.Txn
import app.fluxo.financas.databinding.FragmentDashboardBinding
import app.fluxo.financas.databinding.ItemCategoryBudgetBinding
import app.fluxo.financas.databinding.ItemLegendBinding
import app.fluxo.financas.databinding.ItemTransactionBinding
import app.fluxo.financas.ui.adapters.bindBudget
import app.fluxo.financas.ui.view.DonutChartView
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.dpi
import app.fluxo.financas.util.visibleIf
import kotlinx.coroutines.launch
import java.time.YearMonth

class DashboardFragment : Fragment(R.layout.fragment_dashboard), Refreshable {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private var month: YearMonth = Dates.currentMonth()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDashboardBinding.bind(view)
        savedInstanceState?.getString(KEY_MONTH)?.let { Dates.parseKey(it)?.let { m -> month = m } }

        binding.prevMonth.setOnClickListener { shiftMonth(-1) }
        binding.nextMonth.setOnClickListener { shiftMonth(1) }
        binding.seeAll.setOnClickListener { (activity as? MainActivity)?.selectTab(1) }
        binding.manageBudgets.setOnClickListener { (activity as? MainActivity)?.selectTab(2) }
        binding.reviewCard.setOnClickListener {
            ReviewSheet().show(parentFragmentManager, "review")
        }
        binding.forecastCard.setOnClickListener {
            ForecastSheet().show(parentFragmentManager, "forecast")
        }
        binding.salaryCard.setOnClickListener {
            (activity as? MainActivity)?.selectTab(2)
        }

        parentFragmentManager.setFragmentResultListener(DATA_CHANGED, viewLifecycleOwner) { _, _ ->
            refreshData()
        }
        refreshData()
    }

    private fun shiftMonth(delta: Long) {
        val target = month.plusMonths(delta)
        if (target > Dates.currentMonth()) return
        month = target
        refreshData()
    }

    override fun refreshData() {
        if (_binding == null || !isAdded) return
        binding.monthLabel.text = Dates.monthLabel(month)
        binding.nextMonth.alpha = if (month >= Dates.currentMonth()) 0.25f else 1f

        viewLifecycleOwner.lifecycleScope.launch {
            val summary = repo.summary(month)
            val budgets = repo.budgets(month)
            val pending = repo.pendingCount()
            val recent = repo.recent(5)
            val series = repo.monthlyExpenses(6)
            val totals = repo.monthlyTotals(15)
            val now = System.currentTimeMillis()
            val available = repo.balanceUntil(now)
            val salaryConfig = repo.salaryVersion(month).config
            val payroll = PayrollCalculator2026.computeMonth(
                salaryConfig,
                month.monthValue,
                repo.vacation(month)
            )
            if (_binding == null) return@launch
            bindSummary(available, summary.balanceCents, summary.incomeCents, summary.expenseCents)
            bindSalaryOverview(salaryConfig, payroll, now)
            bindReview(pending)
            bindCategories(summary.byCategory.map { it.first to it.second }, summary.expenseCents)
            bindBudgets(budgets.filter { it.limitCents > 0 })
            bindBars(series)
            bindForecast(totals, available)
            bindRecent(recent)
        }
    }

    private fun bindForecast(totals: List<MonthlyTotal>, totalBalance: Long) {
        val prefs = Prefs(requireContext())
        val averages: MonthlyAverages? = ForecastCalculator.average(totals, Dates.currentMonth(), 3)
        if (averages == null) {
            binding.forecastValue.text = "—"
            binding.forecastValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
            binding.forecastHint.text = getString(R.string.forecast_no_history)
            binding.forecastChart.visibleIf(false)
            return
        }
        val start = prefs.forecastStartOverride.takeIf { it != Long.MIN_VALUE } ?: totalBalance
        val result = ForecastCalculator.project(
            startBalanceCents = start,
            averages = averages,
            incomeGrowthBp = prefs.forecastIncomeGrowthBp,
            expenseGrowthBp = prefs.forecastExpenseGrowthBp,
            months = 6,
            fromMonth = Dates.currentMonth()
        )
        binding.forecastValue.text = Money.format(result.finalCents)
        binding.forecastValue.setTextColor(
            ContextCompat.getColor(requireContext(), if (result.finalCents < 0) R.color.expense else R.color.income)
        )
        binding.forecastHint.text = getString(R.string.forecast_in_months, 6) + " · " + getString(
            R.string.forecast_basis,
            averages.monthsUsed,
            Money.format(averages.incomeCents),
            Money.format(averages.expenseCents)
        )
        binding.forecastChart.visibleIf(true)
        binding.forecastChart.setData(
            result.points.map { it.cumulativeCents },
            result.points.map { Dates.monthShort(it.month) }
        )
    }

    private fun bindSummary(available: Long, monthBalance: Long, income: Long, expense: Long) {
        binding.balanceValue.text = Money.format(available)
        val prefix = when {
            monthBalance > 0 -> "+ "
            monthBalance < 0 -> "− "
            else -> ""
        }
        binding.monthResult.text = getString(
            R.string.month_result,
            prefix + Money.format(abs(monthBalance))
        )
        binding.incomeValue.text = Money.format(income)
        binding.expenseValue.text = Money.format(expense)
    }

    private fun bindSalaryOverview(config: SalaryConfig, payroll: MonthPayroll, now: Long) {
        if (config.grossCents <= 0 || payroll.totalNet <= 0) {
            binding.salaryCard.visibleIf(false)
            return
        }
        val payments = PayrollCalculator2026.payments(config, month, payroll)
        if (payments.isEmpty()) {
            binding.salaryCard.visibleIf(false)
            return
        }
        binding.salaryCard.visibleIf(true)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val pending = payments.filter { it.date > today }
        val pendingAmount = pending.sumOf { it.amountCents }
        val expected = payments.sumOf { it.amountCents }
        if (pendingAmount > 0) {
            binding.salaryValue.text = getString(R.string.salary_overview_pending, Money.format(pendingAmount))
            binding.salaryValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.income))
            val next = pending.minByOrNull { it.date } ?: pending.first()
            binding.salaryHint.text = getString(
                R.string.salary_overview_pending_hint,
                Money.format(expected),
                paymentLabel(next.kind),
                shortDate(next.date)
            )
        } else {
            binding.salaryValue.text = Money.format(expected)
            binding.salaryValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
            binding.salaryHint.setText(R.string.salary_overview_done_hint)
        }
    }

    private fun paymentLabel(kind: SalaryPaymentKind): String = when (kind) {
        SalaryPaymentKind.FIRST -> getString(R.string.salary_payment_first)
        SalaryPaymentKind.SECOND -> getString(R.string.salary_payment_second)
        SalaryPaymentKind.THIRTEENTH -> getString(R.string.salary_payment_thirteenth)
    }

    private fun shortDate(date: LocalDate): String = Dates.shortDate(
        Dates.epochFromDate(date.year, date.monthValue - 1, date.dayOfMonth)
    )

    private fun bindReview(count: Int) {
        binding.reviewCard.visibleIf(count > 0)
        binding.reviewCount.text = count.toString()
        binding.reviewSubtitle.text = getString(R.string.review_count, count)
    }

    private fun bindCategories(items: List<Pair<app.fluxo.financas.data.Category, Long>>, total: Long) {
        binding.legend.removeAllViews()
        if (items.isEmpty() || total <= 0) {
            binding.donut.setData(emptyList(), 0)
            binding.emptyCategories.visibleIf(true)
            return
        }
        binding.donut.visibleIf(true)
        binding.emptyCategories.visibleIf(false)

        val top = items.take(5).toMutableList()
        val rest = items.drop(5).sumOf { it.second }
        if (rest > 0) {
            val otherColor = ContextCompat.getColor(requireContext(), R.color.ink_soft)
            top += app.fluxo.financas.data.Category(0, "Outros", "•", otherColor, "expense") to rest
        }
        val slices = top.map { DonutChartView.Slice(it.second, it.first.color, it.first.name) }
        binding.donut.setData(slices, total)

        top.forEach { (category, value) ->
            val row = ItemLegendBinding.inflate(layoutInflater, binding.legend, false)
            row.dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(category.color)
            }
            row.name.text = category.name
            row.value.text = Money.format(value)
            row.percent.text = getString(R.string.percent, (value * 100 / total).toInt())
            binding.legend.addView(row.root)
        }
    }

    private fun bindBudgets(budgets: List<app.fluxo.financas.data.BudgetRow>) {
        binding.budgetPreview.removeAllViews()
        binding.budgetEmpty.visibleIf(budgets.isEmpty())
        budgets.take(3).forEach { row ->
            val item = ItemCategoryBudgetBinding.inflate(layoutInflater, binding.budgetPreview, false)
            bindBudget(item, row)
            item.root.setOnClickListener {
                BudgetEditorSheet.new(row.category.id, Dates.key(month), row.limitCents)
                    .show(parentFragmentManager, "budget_editor")
            }
            binding.budgetPreview.addView(item.root)
        }
    }

    private fun bindBars(series: List<Pair<YearMonth, Long>>) {
        binding.bars.setData(
            series.map { it.second },
            series.map { Dates.monthShort(it.first) }
        )
    }

    private fun bindRecent(txns: List<Txn>) {
        binding.recentList.removeAllViews()
        binding.recentEmpty.visibleIf(txns.isEmpty())
        txns.forEach { txn ->
            val item = ItemTransactionBinding.inflate(layoutInflater, binding.recentList, false)
            item.emoji.text = txn.categoryEmoji
            item.merchant.text = txn.merchant
            item.subtitle.text = "${txn.categoryName} · ${Dates.shortDate(txn.date)}"
            val sign = if (txn.isExpense) "− " else "+ "
            item.amount.text = sign + Money.format(txn.amountCents)
            item.amount.setTextColor(
                ContextCompat.getColor(requireContext(), if (txn.isExpense) R.color.expense else R.color.income)
            )
            item.root.setOnClickListener {
                TransactionEditorSheet.forTxn(txn.id).show(parentFragmentManager, "txn_editor")
            }
            binding.recentList.addView(item.root)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_MONTH, Dates.key(month))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_MONTH = "month"
        private const val DATA_CHANGED = "fluxo_data"
    }
}
