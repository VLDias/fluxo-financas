package app.fluxo.financas.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.FragmentSalaryBinding
import app.fluxo.financas.databinding.ItemPayrollRowBinding
import app.fluxo.financas.salary.MonthPayroll
import app.fluxo.financas.salary.PayrollCalculator2026
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.salary.VacationConfig
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.visibleIf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

class SalaryFragment : Fragment(R.layout.fragment_salary), Refreshable {

    private var _binding: FragmentSalaryBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private var month: YearMonth = Dates.currentMonth()
    private var config = SalaryConfig()
    private var effectiveMonth: YearMonth? = null
    private var vacation = VacationConfig()
    private var payroll: MonthPayroll? = null
    private var bindingVacation = false
    private var daysJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSalaryBinding.bind(view)
        savedInstanceState?.getString(KEY_MONTH)?.let { Dates.parseKey(it)?.let { m -> month = m } }

        binding.prevMonth.setOnClickListener { shiftMonth(-1) }
        binding.nextMonth.setOnClickListener { shiftMonth(1) }
        binding.editConfig.setOnClickListener {
            SalaryConfigSheet.new(Dates.key(month)).show(parentFragmentManager, "salary_config")
        }
        binding.launchButton.setOnClickListener { launch() }

        binding.vacationSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingVacation) return@setOnCheckedChangeListener
            vacation = if (checked) {
                VacationConfig(days = vacation.days.takeIf { it in 1..30 } ?: 30, sellTenDays = vacation.sellTenDays)
            } else {
                VacationConfig()
            }
            binding.vacationInputs.visibleIf(checked)
            if (checked) binding.vacationDays.setText(vacation.days.toString())
            persistVacation()
        }
        binding.sellTenSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingVacation) return@setOnCheckedChangeListener
            if (checked && vacation.days > 20) {
                Toast.makeText(requireContext(), R.string.salary_vacation_limit, Toast.LENGTH_SHORT).show()
                bindingVacation = true
                binding.sellTenSwitch.isChecked = false
                bindingVacation = false
                return@setOnCheckedChangeListener
            }
            vacation = vacation.copy(sellTenDays = checked)
            persistVacation()
        }
        binding.vacationDays.doAfterTextChanged { editable ->
            if (bindingVacation) return@doAfterTextChanged
            daysJob?.cancel()
            val raw = editable?.toString().orEmpty()
            daysJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(350)
                val days = raw.toIntOrNull()
                if (days == null || days !in 1..30) {
                    binding.vacationDaysLayout.error = "1 a 30 dias"
                    return@launch
                }
                if (vacation.sellTenDays && days > 20) {
                    binding.vacationDaysLayout.error = getString(R.string.salary_vacation_limit)
                    return@launch
                }
                binding.vacationDaysLayout.error = null
                vacation = vacation.copy(days = days)
                repo.saveVacation(month, vacation)
                computeAndBind()
            }
        }

        parentFragmentManager.setFragmentResultListener(DATA_CHANGED, viewLifecycleOwner) { _, _ ->
            refreshData()
        }
        refreshData()
    }

    private fun shiftMonth(delta: Long) {
        val target = month.plusMonths(delta)
        if (target > Dates.currentMonth().plusMonths(24)) return
        month = target
        refreshData()
    }

    override fun refreshData() {
        if (_binding == null || !isAdded) return
        binding.monthLabel.text = Dates.monthLabel(month)
        binding.nextMonth.alpha = if (month >= Dates.currentMonth().plusMonths(24)) 0.25f else 1f
        daysJob?.cancel()

        viewLifecycleOwner.lifecycleScope.launch {
            val version = repo.salaryVersion(month)
            config = version.config
            effectiveMonth = version.effective
            vacation = repo.vacation(month)
            if (_binding == null) return@launch
            bindConfig()
            bindVacation()
            computeAndBind()
        }
    }

    private fun bindConfig() {
        if (config.grossCents <= 0) {
            binding.configSummary.text = getString(R.string.salary_empty)
            return
        }
        val secondDate = PayrollCalculator2026.secondPaymentDate(config, month)
        val secondShort = shortDate(secondDate)
        val secondText = if (config.secondBusinessDay) {
            getString(R.string.salary_second_summary_business, config.secondBusinessDayNumber) + " ($secondShort)"
        } else secondShort
        val dependents = if (config.dependents == 1) "1 dependente" else "${config.dependents} dependentes"
        val discounts = if (config.discounts.size == 1) "1 desconto" else "${config.discounts.size} descontos"
        binding.configSummary.text = buildString {
            append(Money.format(config.grossCents))
            append(" · ")
            append("${config.firstPercent}% dia ${config.firstDay} + ${100 - config.firstPercent}% $secondText")
            append("\n")
            append("$dependents · $discounts")
            effectiveMonth?.let { since ->
                append(" · ")
                append(if (since == month) getString(R.string.salary_config_from_now) else getString(R.string.salary_config_since, Dates.monthLabel(since)))
            }
        }
    }

    private fun bindVacation() {
        bindingVacation = true
        val active = vacation.days > 0
        binding.vacationSwitch.isChecked = active
        binding.vacationInputs.visibleIf(active)
        if (active) {
            if (binding.vacationDays.text?.toString() != vacation.days.toString()) {
                binding.vacationDays.setText(vacation.days.toString())
            }
            binding.sellTenSwitch.isChecked = vacation.sellTenDays
            binding.vacationDaysLayout.error = null
        } else {
            binding.vacationDays.setText("")
            binding.sellTenSwitch.isChecked = false
        }
        bindingVacation = false
    }

    private fun computeAndBind() {
        if (_binding == null) return
        val result = PayrollCalculator2026.computeMonth(config, month.monthValue, vacation)
        payroll = result
        bind(result)
    }

    private fun bind(p: MonthPayroll) {
        val hasSalary = config.grossCents > 0
        binding.netLabel.setText(
            if (p.thirteenthThisMonth > 0) R.string.salary_net_with_13 else R.string.salary_net
        )
        binding.netValue.text = Money.format(p.totalNet)
        binding.emptyHint.visibleIf(!hasSalary)
        binding.launchButton.isEnabled = hasSalary
        binding.rows.removeAllViews()

        if (hasSalary) {
            if (p.salaryBase > 0) {
                addRow(getString(R.string.salary_salary_days, p.salaryDays), p.salaryBase)
            }
            val vacationPay = p.vacationBase + p.vacationThird
            if (vacationPay > 0) addRow(getString(R.string.salary_vacation_pay), vacationPay)
            val abono = p.abonoBase + p.abonoThird
            if (abono > 0) addRow(getString(R.string.salary_abono), abono)
            if (p.thirteenthThisMonth > 0) addRow(getString(R.string.salary_13_line), p.thirteenthThisMonth)
            addRow(getString(R.string.salary_inss), p.inss, negative = true)
            addRow(getString(R.string.salary_irrf), p.irrf.tax, negative = true)
            if (p.discounts > 0) addRow(getString(R.string.salary_discounts_line), p.discounts, negative = true)
        }

        binding.taxDetails.visibleIf(hasSalary)
        if (hasSalary) {
            val rate = String.format(Locale("pt", "BR"), "%.2f", p.irrf.rateBp / 100.0)
                .trimEnd('0').trimEnd(',')
            val simplified = if (p.irrf.usedSimplified) getString(R.string.salary_simplified) else ""
            binding.taxDetails.text = buildString {
                append(
                    getString(
                        R.string.salary_tax_details,
                        Money.format(p.irrf.baseCents),
                        rate,
                        Money.format(p.irrf.reduction),
                        simplified
                    )
                )
                p.thirteenth?.let { t ->
                    append("\n")
                    append(getString(R.string.salary_13_details, Money.format(t.inss), Money.format(t.irrf)))
                }
            }
        }

        val secondDate = PayrollCalculator2026.secondPaymentDate(config, month)
        binding.firstLabel.text = getString(R.string.salary_first_installment, config.firstDay)
        binding.secondLabel.text = getString(R.string.salary_second_installment_date, shortDate(secondDate))
        binding.firstValue.text = Money.format(p.firstInstallment)
        binding.secondValue.text = Money.format(p.secondInstallment)
        if (p.vacationBase > 0) {
            binding.firstSub.setText(R.string.salary_first_sub_vacation)
            binding.secondSub.setText(R.string.salary_second_sub_vacation)
        } else {
            binding.firstSub.text = if (config.irrfOnFirst) {
                getString(R.string.salary_first_sub, config.firstPercent)
            } else {
                getString(R.string.salary_first_sub_no_irrf, config.firstPercent)
            }
            binding.secondSub.text = getString(R.string.salary_second_sub, 100 - config.firstPercent)
        }

        if (vacation.days > 0) {
            binding.vacationCaption.text = if (vacation.sellTenDays && vacation.days + 10 > 30) {
                getString(R.string.salary_vacation_abono_ignored)
            } else {
                getString(
                    R.string.salary_vacation_caption,
                    Money.format(p.vacationBase),
                    Money.format(p.vacationThird),
                    Money.format(p.abonoBase + p.abonoThird)
                )
            }
        } else {
            binding.vacationCaption.text = ""
        }

        val thirteenth = p.thirteenth
        if (config.thirteenthEnabled && thirteenth != null) {
            binding.thirteenthFirst.isVisible = true
            binding.thirteenthSecond.isVisible = true
            binding.thirteenthFirst.text = getString(
                R.string.salary_13_first,
                thirteenthDateLabel(config.thirteenthFirstMonth, config.thirteenthFirstDay),
                Money.format(thirteenth.firstInstallment)
            )
            binding.thirteenthSecond.text = getString(
                R.string.salary_13_second,
                thirteenthDateLabel(config.thirteenthSecondMonth, config.thirteenthSecondDay),
                Money.format(thirteenth.secondInstallment)
            )
            binding.thirteenthThisMonth.visibleIf(p.thirteenthThisMonth > 0)
            binding.thirteenthThisMonth.text = getString(
                R.string.salary_13_this_month,
                Money.format(p.thirteenthThisMonth)
            )
            binding.thirteenthOff.isVisible = false
        } else {
            binding.thirteenthFirst.isVisible = false
            binding.thirteenthSecond.isVisible = false
            binding.thirteenthThisMonth.isVisible = false
            binding.thirteenthOff.isVisible = true
        }
    }

    private fun addRow(label: String, cents: Long, negative: Boolean = false) {
        val row = ItemPayrollRowBinding.inflate(layoutInflater, binding.rows, false)
        row.label.text = label
        row.value.text = (if (negative) "− " else "") + Money.format(Math.abs(cents))
        row.value.setTextColor(
            ContextCompat.getColor(requireContext(), if (negative) R.color.expense else R.color.ink)
        )
        binding.rows.addView(row.root)
    }

    private fun persistVacation() {
        viewLifecycleOwner.lifecycleScope.launch {
            repo.saveVacation(month, vacation)
            computeAndBind()
        }
    }

    private fun launch() {
        val p = payroll ?: return
        if (config.grossCents <= 0) return
        viewLifecycleOwner.lifecycleScope.launch {
            val count = repo.launchSalary(month, config, p)
            Toast.makeText(
                requireContext(),
                if (count > 0) getString(R.string.salary_launched, count) else getString(R.string.salary_already),
                Toast.LENGTH_SHORT
            ).show()
            setFragmentResult(DATA_CHANGED, Bundle.EMPTY)
        }
    }

    private fun shortDate(date: java.time.LocalDate): String = Dates.shortDate(
        Dates.epochFromDate(date.year, date.monthValue - 1, date.dayOfMonth)
    )

    private fun thirteenthDateLabel(monthNumber: Int, day: Int): String {
        val target = YearMonth.of(month.year, monthNumber)
        val safeDay = if (day <= 0) target.lengthOfMonth() else day.coerceIn(1, target.lengthOfMonth())
        return Dates.shortDate(Dates.epochFromDate(target.year, target.monthValue - 1, safeDay))
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
