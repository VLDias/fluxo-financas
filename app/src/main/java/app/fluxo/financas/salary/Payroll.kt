package app.fluxo.financas.salary

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.YearMonth

data class PayrollDiscount(
    val id: String,
    val name: String,
    val percent: Boolean = false,
    /** Centavos quando fixo; basis points quando percentual (5% = 500). */
    val value: Long = 0,
    val applyToThirteenth: Boolean = true
)

data class SalaryConfig(
    val grossCents: Long = 0,
    val dependents: Int = 0,
    val firstPercent: Int = 30,
    val firstDay: Int = 19,
    /** 0 = último dia do mês. */
    val secondDay: Int = 0,
    /** 2ª parcela cai no mês seguinte ao de referência. */
    val secondNextMonth: Boolean = false,
    /** 2ª parcela é o N-ésimo dia útil, em vez de um dia fixo. */
    val secondBusinessDay: Boolean = false,
    val secondBusinessDayNumber: Int = 5,
    val irrfOnFirst: Boolean = true,
    val thirteenthEnabled: Boolean = true,
    val thirteenthFirstMonth: Int = 11,
    val thirteenthSecondMonth: Int = 12,
    /** 0 = último dia do mês. */
    val thirteenthFirstDay: Int = 30,
    val thirteenthSecondDay: Int = 20,
    val monthsWorked: Int = 12,
    val discounts: List<PayrollDiscount> = emptyList()
)

data class SalaryVersion(val config: SalaryConfig, val effective: java.time.YearMonth?)

data class VacationConfig(
    val days: Int = 0,
    val sellTenDays: Boolean = false
)

data class IrrfBreakdown(
    val baseCents: Long,
    val rateBp: Int,
    val deductionCents: Long,
    val taxBeforeReduction: Long,
    val reduction: Long,
    val tax: Long,
    val usedSimplified: Boolean
)

data class ThirteenthPayroll(
    val grossCents: Long,
    val inss: Long,
    val irrf: Long,
    val discounts: Long,
    val firstInstallment: Long,
    val secondInstallment: Long
) {
    fun installmentForMonth(month: Int, config: SalaryConfig): Long = when (month) {
        config.thirteenthFirstMonth -> firstInstallment
        config.thirteenthSecondMonth -> secondInstallment
        else -> 0L
    }
}

enum class SalaryPaymentKind { FIRST, SECOND, THIRTEENTH }

data class SalaryPayment(
    val kind: SalaryPaymentKind,
    val amountCents: Long,
    val date: LocalDate
)

data class MonthPayroll(
    val salaryDays: Int,
    val salaryBase: Long,
    val vacationBase: Long,
    val vacationThird: Long,
    val abonoBase: Long,
    val abonoThird: Long,
    val grossTaxable: Long,
    val inss: Long,
    val irrf: IrrfBreakdown,
    val discounts: Long,
    val netSalary: Long,
    val thirteenth: ThirteenthPayroll?,
    val thirteenthThisMonth: Long,
    val firstInstallment: Long,
    val secondInstallment: Long,
    val totalNet: Long
)

/**
 * Tabelas oficiais 2026.
 * INSS: Portaria Interministerial MPS/MF nº 13, de 09/01/2026.
 * IRRF: Lei 15.191/2025 (tabela progressiva) + Lei 15.270/2025 (redução mensal).
 *
 * Premissas de férias: 1/3 constitucional entra no IRRF e não no INSS (STF, Tema 985);
 * abono pecuniário (10 dias) é isento de INSS e IRRF.
 */
object PayrollCalculator2026 {

    private val inssBrackets = listOf(
        162100L to 750,     // até 1.621,00 -> 7,5%
        290284L to 900,     // até 2.902,84 -> 9%
        435427L to 1200,    // até 4.354,27 -> 12%
        847555L to 1400     // até 8.475,55 -> 14%
    )

    private val irrfBrackets = listOf(
        242880L to 0,
        282665L to 750,
        375105L to 1500,
        466468L to 2250,
        Long.MAX_VALUE to 2750
    )
    private val irrfDeductions = listOf(0L, 18216L, 39416L, 67549L, 90873L)

    const val dependentDeduction = 18959L      // R$ 189,59
    const val simplifiedLimit = 60720L         // R$ 607,20
    const val reductionCap = 31289L            // R$ 312,89
    const val reductionThreshold = 500000L     // R$ 5.000,00
    const val reductionZeroAt = 735000L        // R$ 7.350,00
    private val reductionIntercept = BigDecimal("97862")
    private val reductionSlope = BigDecimal("0.133145")

    fun inss(grossCents: Long): Long {
        if (grossCents <= 0) return 0
        var previous = 0L
        var total = BigDecimal.ZERO
        for ((cap, rateBp) in inssBrackets) {
            if (grossCents <= previous) break
            val portion = minOf(grossCents, cap) - previous
            if (portion > 0) {
                total = total.add(
                    BigDecimal.valueOf(portion)
                        .multiply(BigDecimal.valueOf(rateBp.toLong()))
                        .divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP)
                )
            }
            previous = cap
        }
        return total.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun irrf(grossTaxableCents: Long, inssCents: Long, dependents: Int): IrrfBreakdown {
        if (grossTaxableCents <= 0) return IrrfBreakdown(0, 0, 0, 0, 0, 0, false)
        val legal = inssCents + dependents.coerceAtLeast(0) * dependentDeduction
        val simplified = minOf(grossTaxableCents * 2500 / 10_000, simplifiedLimit)
        val usedSimplified = simplified > legal
        val deduction = if (usedSimplified) simplified else legal
        val base = (grossTaxableCents - deduction).coerceAtLeast(0)

        val index = irrfBrackets.indexOfFirst { base <= it.first }.let { if (it < 0) irrfBrackets.lastIndex else it }
        val rateBp = irrfBrackets[index].second
        val fixedDeduction = irrfDeductions[index]
        val taxBefore = if (base == 0L) 0L else {
            BigDecimal.valueOf(base)
                .multiply(BigDecimal.valueOf(rateBp.toLong()))
                .divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.valueOf(fixedDeduction))
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
                .coerceAtLeast(0)
        }

        val reduction = when {
            grossTaxableCents <= reductionThreshold -> minOf(taxBefore, reductionCap)
            grossTaxableCents <= reductionZeroAt -> {
                val formula = reductionIntercept.subtract(
                    reductionSlope.multiply(BigDecimal.valueOf(grossTaxableCents))
                ).setScale(0, RoundingMode.HALF_UP).toLong().coerceAtLeast(0)
                minOf(taxBefore, formula)
            }
            else -> 0L
        }

        return IrrfBreakdown(
            baseCents = base,
            rateBp = rateBp,
            deductionCents = deduction,
            taxBeforeReduction = taxBefore,
            reduction = reduction,
            tax = (taxBefore - reduction).coerceAtLeast(0),
            usedSimplified = usedSimplified
        )
    }

    fun computeMonth(config: SalaryConfig, month: Int, vacation: VacationConfig): MonthPayroll {
        val gross = config.grossCents.coerceAtLeast(0)
        val vacationDays = vacation.days.coerceIn(0, 30)
        val abonoDays = if (vacation.sellTenDays && vacationDays + 10 <= 30) 10 else 0
        val workedDays = (30 - vacationDays - abonoDays).coerceAtLeast(0)

        val salaryBase = gross * workedDays / 30
        val vacationBase = gross * vacationDays / 30
        val vacationThird = vacationBase / 3
        val abonoBase = gross * abonoDays / 30
        val abonoThird = abonoBase / 3

        val grossTaxable = salaryBase + vacationBase + vacationThird
        val inssBase = salaryBase + vacationBase
        val inss = inss(inssBase)
        val irrfLine = irrf(grossTaxable, inss, config.dependents)
        val discounts = discountsFor(config.discounts, gross)
        val netSalary = grossTaxable + abonoBase + abonoThird - inss - irrfLine.tax - discounts

        val thirteenth = if (config.thirteenthEnabled && config.monthsWorked > 0) {
            val gross13 = gross * config.monthsWorked.coerceIn(1, 12) / 12
            val inss13 = inss(gross13)
            val irrf13 = irrf(gross13, inss13, config.dependents).tax
            val disc13 = discountsFor(config.discounts.filter { it.applyToThirteenth }, gross13)
            val first13 = gross13 / 2
            ThirteenthPayroll(
                grossCents = gross13,
                inss = inss13,
                irrf = irrf13,
                discounts = disc13,
                firstInstallment = first13,
                secondInstallment = (gross13 - first13 - inss13 - irrf13 - disc13).coerceAtLeast(0)
            )
        } else null
        val thirteenthThisMonth = thirteenth?.installmentForMonth(month, config) ?: 0L

        val first: Long
        val second: Long
        if (vacationDays == 0 && abonoDays == 0) {
            val firstGross = salaryBase * config.firstPercent.coerceIn(1, 99) / 100
            first = if (config.irrfOnFirst) firstGross - irrfLine.tax else firstGross
            second = salaryBase - inss - discounts - firstGross
        } else {
            // Mês com férias: o holerite mistura salário, férias e abono; divide o líquido.
            first = netSalary * config.firstPercent.coerceIn(1, 99) / 100
            second = netSalary - first
        }

        return MonthPayroll(
            salaryDays = workedDays,
            salaryBase = salaryBase,
            vacationBase = vacationBase,
            vacationThird = vacationThird,
            abonoBase = abonoBase,
            abonoThird = abonoThird,
            grossTaxable = grossTaxable,
            inss = inss,
            irrf = irrfLine,
            discounts = discounts,
            netSalary = netSalary,
            thirteenth = thirteenth,
            thirteenthThisMonth = thirteenthThisMonth,
            firstInstallment = first,
            secondInstallment = second,
            totalNet = netSalary + thirteenthThisMonth
        )
    }

    private fun discountsFor(discounts: List<PayrollDiscount>, base: Long): Long =
        discounts.sumOf { d ->
            if (d.percent) base * d.value / 10_000 else d.value
        }

    /**
     * Data efetiva da 2ª parcela. Pode cair no mês seguinte e/ou no N-ésimo dia útil.
     * Ex.: referência novembro/2026, 5º dia útil do mês seguinte -> 07/12/2026.
     */
    /** Parcelas previstas do mês de referência, com as datas efetivas de pagamento. */
    fun payments(config: SalaryConfig, reference: YearMonth, payroll: MonthPayroll): List<SalaryPayment> {
        val monthLength = reference.lengthOfMonth()
        val firstDay = config.firstDay.coerceIn(1, monthLength)
        val list = mutableListOf(
            SalaryPayment(
                SalaryPaymentKind.FIRST,
                payroll.firstInstallment,
                LocalDate.of(reference.year, reference.monthValue, firstDay)
            ),
            SalaryPayment(
                SalaryPaymentKind.SECOND,
                payroll.secondInstallment,
                secondPaymentDate(config, reference)
            )
        )
        if (payroll.thirteenthThisMonth > 0) {
            val configuredDay = if (reference.monthValue == config.thirteenthFirstMonth) {
                config.thirteenthFirstDay
            } else {
                config.thirteenthSecondDay
            }
            val day = if (configuredDay <= 0) monthLength else configuredDay.coerceIn(1, monthLength)
            list += SalaryPayment(
                SalaryPaymentKind.THIRTEENTH,
                payroll.thirteenthThisMonth,
                LocalDate.of(reference.year, reference.monthValue, day)
            )
        }
        return list.filter { it.amountCents > 0 }
    }

    fun secondPaymentDate(config: SalaryConfig, reference: YearMonth): LocalDate {
        val target = if (config.secondNextMonth) reference.plusMonths(1) else reference
        return if (config.secondBusinessDay) {
            BrazilianHolidays.nthBusinessDay(target, config.secondBusinessDayNumber.coerceIn(1, 23))
        } else {
            val day = if (config.secondDay <= 0) target.lengthOfMonth()
            else config.secondDay.coerceIn(1, target.lengthOfMonth())
            target.atDay(day)
        }
    }
}

/** Feriados nacionais usados no cálculo de dia útil. */
object BrazilianHolidays {

    private val fixed = setOf(
        MonthDay.of(1, 1),   // Confraternização Universal
        MonthDay.of(4, 21),  // Tiradentes
        MonthDay.of(5, 1),   // Trabalho
        MonthDay.of(9, 7),   // Independência
        MonthDay.of(10, 12), // Nossa Senhora Aparecida
        MonthDay.of(11, 2),  // Finados
        MonthDay.of(11, 15), // Proclamação da República
        MonthDay.of(11, 20), // Consciência Negra
        MonthDay.of(12, 25)  // Natal
    )

    fun isHoliday(date: LocalDate): Boolean {
        if (MonthDay.from(date) in fixed) return true
        // Sexta-feira Santa
        return date == easterSunday(date.year).minusDays(2)
    }

    fun nthBusinessDay(month: YearMonth, n: Int): LocalDate {
        var current = month.atDay(1)
        var count = 0
        while (current.month == month.month) {
            val weekday = current.dayOfWeek != DayOfWeek.SATURDAY && current.dayOfWeek != DayOfWeek.SUNDAY
            if (weekday && !isHoliday(current)) {
                count++
                if (count == n) return current
            }
            current = current.plusDays(1)
        }
        return month.atEndOfMonth()
    }

    /** Algoritmo de Meeus/Jones/Butcher para o domingo de Páscoa. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }
}
