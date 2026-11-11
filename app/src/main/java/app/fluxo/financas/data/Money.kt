package app.fluxo.financas.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object Money {
    val BR: Locale = Locale("pt", "BR")

    private fun currency(): NumberFormat = NumberFormat.getCurrencyInstance(BR).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }

    fun format(cents: Long): String = currency().format(BigDecimal.valueOf(cents, 2))

    fun formatAbs(cents: Long): String = format(Math.abs(cents))

    fun formatPlain(cents: Long): String =
        NumberFormat.getNumberInstance(BR).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(BigDecimal.valueOf(cents, 2))

    /** Aceita "R$ 1.234,56", "1234,56", "1234.56", "12", "12,5", "-30,00". */
    fun parse(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        var s = input.trim().lowercase(BR)
        val negative = s.startsWith("-") || (s.startsWith("(") && s.endsWith(")"))
        s = s.replace("r\$", "").replace(" ", "").replace("\u00a0", "")
        s = s.replace(Regex("""[^0-9,.\-]"""), "")
        s = s.removePrefix("-")
        if (s.isBlank()) return null
        val normalized: String = when {
            s.contains(',') -> s.replace(".", "").replace(',', '.')
            s.count { it == '.' } == 1 && s.substringAfterLast('.').length == 2 -> s
            s.count { it == '.' } == 1 && s.substringAfterLast('.').length == 1 -> s
            s.count { it == '.' } == 1 && s.substringAfterLast('.').length == 3 && s.substringBeforeLast('.').length in 1..3 -> s.replace(".", "")
            s.count { it == '.' } > 1 -> s.replace(".", "")
            else -> s
        }
        val dec = normalized.toBigDecimalOrNull() ?: return null
        val cents = dec.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
        return if (negative) -cents else cents
    }

    fun parsePositive(input: String?): Long? = parse(input)?.takeIf { it > 0 }
}
