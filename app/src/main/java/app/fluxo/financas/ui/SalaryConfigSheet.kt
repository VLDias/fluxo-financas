package app.fluxo.financas.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.DialogDiscountBinding
import app.fluxo.financas.databinding.ItemDiscountBinding
import app.fluxo.financas.databinding.SheetSalaryConfigBinding
import app.fluxo.financas.salary.PayrollDiscount
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.applyBottomInset
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

class SalaryConfigSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSalaryConfigBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private val discounts = mutableListOf<PayrollDiscount>()
    private var month1 = 11
    private var month2 = 12
    private var effectiveMonth: YearMonth = Dates.currentMonth()
    private var secondRuleIndex = 0
    private var loadedConfig = SalaryConfig()
    private val locale = Locale("pt", "BR")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSalaryConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyBottomInset(binding.root)
        effectiveMonth = Dates.parseKey(arguments?.getString(ARG_MONTH)) ?: Dates.currentMonth()
        binding.effectiveLabel.text = getString(R.string.salary_effective_from, Dates.monthLabel(effectiveMonth))
        month1 = 11
        month2 = 12

        setupMonth(binding.thirteenthMonth1, isFirst = true)
        setupMonth(binding.thirteenthMonth2, isFirst = false)
        val ruleAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ruleLabels())
        binding.secondRule.setAdapter(ruleAdapter)
        binding.secondRule.setOnItemClickListener { _, _, position, _ ->
            secondRuleIndex = position
            updateSecondFieldHint()
        }
        binding.addDiscount.setOnClickListener { showDiscountDialog() }
        binding.save.setOnClickListener { save() }

        viewLifecycleOwner.lifecycleScope.launch {
            val config = repo.salaryVersion(effectiveMonth).config
            loadedConfig = config
            discounts.clear()
            discounts.addAll(config.discounts)
            month1 = config.thirteenthFirstMonth
            month2 = config.thirteenthSecondMonth
            binding.gross.setText(if (config.grossCents > 0) Money.formatPlain(config.grossCents) else "")
            binding.dependents.setText(config.dependents.toString())
            binding.firstPercent.setText(config.firstPercent.toString())
            binding.firstDay.setText(config.firstDay.toString())
            secondRuleIndex = when {
                config.secondBusinessDay && config.secondNextMonth -> 3
                config.secondBusinessDay -> 2
                config.secondNextMonth -> 1
                else -> 0
            }
            binding.secondRule.setText(ruleLabels()[secondRuleIndex], false)
            binding.secondDay.setText(
                if (config.secondBusinessDay) config.secondBusinessDayNumber.toString()
                else config.secondDay.toString()
            )
            updateSecondFieldHint()
            binding.irrfFirstSwitch.isChecked = config.irrfOnFirst
            binding.thirteenthSwitch.isChecked = config.thirteenthEnabled
            binding.thirteenthDay1.setText(config.thirteenthFirstDay.toString())
            binding.thirteenthDay2.setText(config.thirteenthSecondDay.toString())
            binding.monthsWorked.setText(config.monthsWorked.toString())
            binding.thirteenthMonth1.setText(monthName(month1), false)
            binding.thirteenthMonth2.setText(monthName(month2), false)
            renderDiscounts()
        }
    }

    private fun setupMonth(view: MaterialAutoCompleteTextView, isFirst: Boolean) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, monthNames())
        view.setAdapter(adapter)
        view.setOnItemClickListener { _, _, position, _ ->
            if (isFirst) month1 = position + 1 else month2 = position + 1
        }
    }

    private fun monthNames(): List<String> = (1..12).map { monthName(it) }

    private fun ruleLabels(): List<String> = listOf(
        getString(R.string.salary_second_rule_same_day),
        getString(R.string.salary_second_rule_next_day),
        getString(R.string.salary_second_rule_same_business),
        getString(R.string.salary_second_rule_next_business)
    )

    private fun isBusinessRule(): Boolean = secondRuleIndex >= 2

    private fun updateSecondFieldHint() {
        if (_binding == null) return
        binding.secondValueLayout.hint = getString(
            if (isBusinessRule()) R.string.salary_second_business_hint else R.string.salary_second_day_hint
        )
        if (isBusinessRule() && (binding.secondDay.text?.toString()?.toIntOrNull() ?: 0) !in 1..23) {
            binding.secondDay.setText("5")
        }
    }

    private fun monthName(month: Int): String =
        Month.of(month).getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase(locale) }

    private fun renderDiscounts() {
        if (_binding == null) return
        binding.discountsContainer.removeAllViews()
        binding.discountsEmpty.isVisible = discounts.isEmpty()
        discounts.forEach { discount ->
            val row = ItemDiscountBinding.inflate(layoutInflater, binding.discountsContainer, false)
            row.name.text = discount.name
            row.value.text = buildString {
                if (discount.percent) {
                    append(Money.formatPlain(discount.value))
                    append("% do salário")
                } else {
                    append(Money.format(discount.value))
                    append("/mês")
                }
                if (discount.applyToThirteenth) append(" · incide no 13º")
            }
            row.remove.setOnClickListener {
                discounts.removeAll { it.id == discount.id }
                renderDiscounts()
            }
            binding.discountsContainer.addView(row.root)
        }
    }

    private fun showDiscountDialog() {
        val dialogBinding = DialogDiscountBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.salary_add_discount)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()

        dialogBinding.percentSwitch.setOnCheckedChangeListener { _, checked ->
            dialogBinding.valueLayout.prefixText = if (checked) "%" else "R$ "
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.name.text?.toString()?.trim().orEmpty()
                val value = Money.parse(dialogBinding.value.text?.toString())
                val percent = dialogBinding.percentSwitch.isChecked
                var valid = true
                if (name.isBlank()) {
                    dialogBinding.nameLayout.error = getString(R.string.required_fields)
                    valid = false
                } else {
                    dialogBinding.nameLayout.error = null
                }
                if (value == null || value <= 0 || (percent && value > 10000)) {
                    dialogBinding.valueLayout.error = getString(
                        if (percent) R.string.salary_percent_invalid else R.string.required_fields
                    )
                    valid = false
                } else {
                    dialogBinding.valueLayout.error = null
                }
                if (!valid) return@setOnClickListener

                discounts.add(
                    PayrollDiscount(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        percent = percent,
                        value = value!!,
                        applyToThirteenth = dialogBinding.apply13Switch.isChecked
                    )
                )
                dialog.dismiss()
                renderDiscounts()
            }
        }
        dialog.show()
    }

    private fun save() {
        val gross = Money.parse(binding.gross.text?.toString()) ?: 0
        val dependents = binding.dependents.text?.toString()?.toIntOrNull() ?: 0
        val firstPercent = binding.firstPercent.text?.toString()?.toIntOrNull() ?: 0
        val firstDay = binding.firstDay.text?.toString()?.toIntOrNull() ?: 0
        val secondValue = binding.secondDay.text?.toString()?.toIntOrNull() ?: -1
        val businessRule = isBusinessRule()
        val nextMonth = secondRuleIndex == 1 || secondRuleIndex == 3
        val thirteenthDay1 = binding.thirteenthDay1.text?.toString()?.toIntOrNull() ?: -1
        val thirteenthDay2 = binding.thirteenthDay2.text?.toString()?.toIntOrNull() ?: -1
        val monthsWorked = binding.monthsWorked.text?.toString()?.toIntOrNull() ?: 12

        var valid = true
        if (gross <= 0) {
            binding.grossLayout.error = getString(R.string.salary_invalid)
            valid = false
        } else {
            binding.grossLayout.error = null
        }
        if (firstPercent !in 1..99 || firstDay !in 1..31 || monthsWorked !in 1..12) {
            valid = false
        }
        if (businessRule && secondValue !in 1..23) valid = false
        if (!businessRule && secondValue !in 0..31) valid = false
        if (thirteenthDay1 !in 0..31 || thirteenthDay2 !in 0..31) valid = false
        if (!valid) {
            if (gross > 0) Toast.makeText(requireContext(), R.string.salary_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (month1 == month2) {
            Toast.makeText(requireContext(), R.string.salary_months_same, Toast.LENGTH_SHORT).show()
            return
        }

        val config = SalaryConfig(
            grossCents = gross,
            dependents = dependents.coerceIn(0, 20),
            firstPercent = firstPercent,
            firstDay = firstDay,
            secondDay = if (businessRule) loadedConfig.secondDay else secondValue,
            secondNextMonth = nextMonth,
            secondBusinessDay = businessRule,
            secondBusinessDayNumber = if (businessRule) secondValue else loadedConfig.secondBusinessDayNumber,
            irrfOnFirst = binding.irrfFirstSwitch.isChecked,
            thirteenthEnabled = binding.thirteenthSwitch.isChecked,
            thirteenthFirstMonth = month1,
            thirteenthSecondMonth = month2,
            thirteenthFirstDay = thirteenthDay1,
            thirteenthSecondDay = thirteenthDay2,
            monthsWorked = monthsWorked,
            discounts = discounts.toList()
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repo.saveSalaryConfig(config, effectiveMonth)
            Toast.makeText(requireContext(), R.string.salary_saved, Toast.LENGTH_SHORT).show()
            setFragmentResult("fluxo_data", Bundle.EMPTY)
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
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

    companion object {
        private const val ARG_MONTH = "month"

        fun new(monthKey: String): SalaryConfigSheet = SalaryConfigSheet().apply {
            arguments = Bundle().apply { putString(ARG_MONTH, monthKey) }
        }
    }
}
