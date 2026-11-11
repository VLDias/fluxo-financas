package app.fluxo.financas.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.FragmentBudgetsBinding
import app.fluxo.financas.ui.adapters.BudgetAdapter
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.applyBudgetRatio
import app.fluxo.financas.util.progressColors
import kotlinx.coroutines.launch
import java.time.YearMonth

class BudgetsFragment : Fragment(R.layout.fragment_budgets), Refreshable {

    private var _binding: FragmentBudgetsBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private var month: YearMonth = Dates.currentMonth()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentBudgetsBinding.bind(view)
        savedInstanceState?.getString(KEY_MONTH)?.let { Dates.parseKey(it)?.let { m -> month = m } }
        binding.list.layoutManager = LinearLayoutManager(requireContext())

        binding.prevMonth.setOnClickListener { shiftMonth(-1) }
        binding.nextMonth.setOnClickListener { shiftMonth(1) }
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
            val rows = repo.budgets(month)
            if (_binding == null) return@launch
            binding.list.adapter = BudgetAdapter(rows) { row ->
                BudgetEditorSheet.new(row.category.id, Dates.key(month), row.limitCents)
                    .show(parentFragmentManager, "budget_editor")
            }

            val defined = rows.filter { it.limitCents > 0 }
            val limitTotal = defined.sumOf { it.limitCents }
            val spentTotal = defined.sumOf { it.spentCents }
            binding.totalValue.text = Money.format(limitTotal)
            if (limitTotal <= 0) {
                binding.totalLeft.text = ""
                binding.totalCaption.text = getString(R.string.empty_budgets)
                binding.totalProgress.progress = 0
                binding.totalProgress.setIndicatorColor(progressColors(requireContext(), 0f))
            } else {
                val ratio = spentTotal.toFloat() / limitTotal
                binding.totalCaption.text = getString(
                    R.string.budget_of, Money.format(spentTotal), Money.format(limitTotal)
                )
                binding.totalLeft.text = if (spentTotal > limitTotal) {
                    getString(R.string.over_limit, Money.format(spentTotal - limitTotal))
                } else {
                    getString(R.string.remaining, Money.format(limitTotal - spentTotal))
                }
                binding.totalLeft.setTextColor(
                    requireContext().getColor(if (spentTotal > limitTotal) R.color.expense else R.color.ink_soft)
                )
                binding.totalProgress.applyBudgetRatio(ratio)
            }
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
