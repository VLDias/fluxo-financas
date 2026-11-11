package app.fluxo.financas.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Prefs
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.ItemForecastMonthBinding
import app.fluxo.financas.databinding.SheetForecastBinding
import app.fluxo.financas.forecast.ForecastCalculator
import app.fluxo.financas.forecast.ForecastResult
import app.fluxo.financas.forecast.MonthlyAverages
import app.fluxo.financas.forecast.MonthlyTotal
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.applyBottomInset
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.time.YearMonth

class ForecastSheet : BottomSheetDialogFragment() {

    private var _binding: SheetForecastBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private val prefs by lazy { Prefs(requireContext()) }

    private var totals: List<MonthlyTotal> = emptyList()
    private var appBalance = 0L
    private var horizon = 12
    private var loaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetForecastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyBottomInset(binding.root)

        binding.horizonGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || !loaded) return@addOnButtonCheckedListener
            horizon = when (checkedId) {
                R.id.horizon6 -> 6
                R.id.horizon24 -> 24
                else -> 12
            }
            prefs.forecastMonths = horizon
            render()
        }
        binding.updateButton.setOnClickListener { applyInputsAndRender() }
        binding.useAppBalance.setOnClickListener {
            binding.startBalance.setText(Money.formatPlain(appBalance))
            prefs.forecastStartOverride = Long.MIN_VALUE
            render()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            totals = repo.monthlyTotals(15)
            appBalance = repo.balanceUntil(System.currentTimeMillis())
            horizon = prefs.forecastMonths.takeIf { it in listOf(6, 12, 24) } ?: 12
            val start = prefs.forecastStartOverride.takeIf { it != Long.MIN_VALUE } ?: appBalance
            binding.startBalance.setText(Money.formatPlain(start))
            binding.growthIncome.setText(formatPercent(prefs.forecastIncomeGrowthBp))
            binding.growthExpense.setText(formatPercent(prefs.forecastExpenseGrowthBp))
            loaded = true
            binding.horizonGroup.check(
                when (horizon) {
                    6 -> R.id.horizon6
                    24 -> R.id.horizon24
                    else -> R.id.horizon12
                }
            )
            render()
        }
    }

    private fun applyInputsAndRender() {
        val start = Money.parse(binding.startBalance.text?.toString()) ?: appBalance
        val incomeBp = (Money.parse(binding.growthIncome.text?.toString()) ?: 0L).toInt().coerceIn(-10_000, 10_000)
        val expenseBp = (Money.parse(binding.growthExpense.text?.toString()) ?: 0L).toInt().coerceIn(-10_000, 10_000)
        prefs.forecastStartOverride = if (start != appBalance) start else Long.MIN_VALUE
        prefs.forecastIncomeGrowthBp = incomeBp
        prefs.forecastExpenseGrowthBp = expenseBp
        prefs.forecastMonths = horizon
        render()
        setFragmentResult("fluxo_data", Bundle.EMPTY)
    }

    private fun render() {
        if (_binding == null) return
        val current = Dates.currentMonth()
        val averages = ForecastCalculator.average(totals, current, 3)
        if (averages == null) {
            binding.chart.isVisible = false
            binding.emptyForecast.isVisible = true
            binding.finalLabel.text = ""
            binding.finalValue.text = "—"
            binding.finalValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
            binding.lowestLine.text = ""
            binding.basisLine.text = ""
            binding.table.removeAllViews()
            return
        }
        val start = Money.parse(binding.startBalance.text?.toString()) ?: appBalance
        val incomeBp = (Money.parse(binding.growthIncome.text?.toString()) ?: 0L).toInt().coerceIn(-10_000, 10_000)
        val expenseBp = (Money.parse(binding.growthExpense.text?.toString()) ?: 0L).toInt().coerceIn(-10_000, 10_000)
        val result = ForecastCalculator.project(start, averages, incomeBp, expenseBp, horizon, current)
        bindResult(result, averages)
    }

    private fun bindResult(result: ForecastResult, averages: MonthlyAverages) {
        val context = requireContext()
        val last = result.points.lastOrNull()
        binding.chart.isVisible = true
        binding.emptyForecast.isVisible = false

        binding.finalLabel.text = last?.let { getString(R.string.forecast_final, Dates.monthLabel(it.month)) }.orEmpty()
        binding.finalValue.text = Money.format(result.finalCents)
        binding.finalValue.setTextColor(
            ContextCompat.getColor(context, if (result.finalCents < 0) R.color.expense else R.color.income)
        )
        binding.lowestLine.text = result.lowest?.let {
            getString(
                R.string.forecast_lowest,
                Money.format(it.cumulativeCents),
                Dates.monthLabel(it.month)
            )
        }.orEmpty()
        binding.chart.setData(
            result.points.map { it.cumulativeCents },
            result.points.map { shortMonth(it.month) }
        )
        binding.basisLine.text = getString(
            R.string.forecast_basis,
            averages.monthsUsed,
            Money.format(averages.incomeCents),
            Money.format(averages.expenseCents)
        )

        binding.table.removeAllViews()
        result.points.forEach { point ->
            val row = ItemForecastMonthBinding.inflate(layoutInflater, binding.table, false)
            row.month.text = shortMonth(point.month)
            row.flows.text = getString(
                R.string.forecast_flows,
                Money.format(point.incomeCents),
                Money.format(point.expenseCents)
            )
            row.net.text = if (point.netCents >= 0) {
                getString(R.string.forecast_net_positive, Money.format(point.netCents))
            } else {
                getString(R.string.forecast_net_negative, Money.format(-point.netCents))
            }
            row.net.setTextColor(
                ContextCompat.getColor(context, if (point.netCents < 0) R.color.expense else R.color.income)
            )
            row.cumulative.text = getString(R.string.forecast_accumulated, Money.format(point.cumulativeCents))
            row.cumulative.setTextColor(
                ContextCompat.getColor(context, if (point.cumulativeCents < 0) R.color.expense else R.color.ink_soft)
            )
            binding.table.addView(row.root)
        }
    }

    private fun formatPercent(bp: Int): String =
        Money.formatPlain(bp.toLong()).replace(",00", "")

    private fun shortMonth(month: YearMonth): String {
        val short = Dates.monthShort(month)
        return "$short/${String.format("%02d", month.year % 100)}"
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
