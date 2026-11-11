package app.fluxo.financas.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Category
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Repo
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.applyBottomInset
import app.fluxo.financas.databinding.SheetBudgetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.time.YearMonth

class BudgetEditorSheet : BottomSheetDialogFragment() {

    private var _binding: SheetBudgetBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }

    private var categories: List<Category> = emptyList()
    private var selectedCategoryId = 0L
    private var month: YearMonth = Dates.currentMonth()
    private var existingLimit = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetBudgetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyBottomInset(binding.root)
        val args = requireArguments()
        selectedCategoryId = args.getLong(ARG_CATEGORY)
        existingLimit = args.getLong(ARG_LIMIT)
        month = Dates.parseKey(args.getString(ARG_MONTH)) ?: Dates.currentMonth()
        binding.limit.setText(if (existingLimit > 0) Money.formatPlain(existingLimit) else "")
        binding.remove.isVisible = existingLimit > 0
        binding.title.text = getString(if (existingLimit > 0) R.string.edit_budget else R.string.set_budget)

        binding.save.setOnClickListener { save() }
        binding.remove.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repo.setBudget(selectedCategoryId, month, 0)
                finish()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            categories = repo.categories("expense")
            val current = categories.firstOrNull { it.id == selectedCategoryId }
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                categories.map { it.name }
            )
            binding.category.setAdapter(adapter)
            if (current != null) {
                selectedCategoryId = current.id
                binding.category.setText(current.name, false)
                binding.categoryLayout.isEnabled = false
                binding.category.isEnabled = false
            } else {
                val first = categories.firstOrNull()
                if (first != null) {
                    selectedCategoryId = first.id
                    binding.category.setText(first.name, false)
                }
                binding.category.setOnItemClickListener { _, _, position, _ ->
                    selectedCategoryId = categories[position].id
                }
            }
            binding.categoryLayout.isEndIconVisible = current == null
        }
    }

    private fun save() {
        val cents = Money.parse(binding.limit.text?.toString()) ?: 0
        viewLifecycleOwner.lifecycleScope.launch {
            repo.setBudget(selectedCategoryId, month, cents)
            android.widget.Toast.makeText(requireContext(), R.string.budget_saved, android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun finish() {
        setFragmentResult("fluxo_data", Bundle.EMPTY)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_CATEGORY = "category_id"
        private const val ARG_MONTH = "month"
        private const val ARG_LIMIT = "limit"

        fun new(categoryId: Long, monthKey: String, limitCents: Long): BudgetEditorSheet =
            BudgetEditorSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CATEGORY, categoryId)
                    putString(ARG_MONTH, monthKey)
                    putLong(ARG_LIMIT, limitCents)
                }
            }
    }
}
