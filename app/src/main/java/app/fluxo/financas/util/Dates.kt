package app.fluxo.financas.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    val locale: Locale = Locale("pt", "BR")
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun currentMonth(): YearMonth = YearMonth.now(zone)

    fun key(ym: YearMonth): String = ym.toString()

    fun parseKey(value: String?): YearMonth? = try {
        if (value.isNullOrBlank()) null else YearMonth.parse(value)
    } catch (e: Exception) {
        null
    }

    fun range(ym: YearMonth): Pair<Long, Long> {
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun monthLabel(ym: YearMonth): String {
        val txt = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
        return txt.replaceFirstChar { it.uppercase(locale) }
    }

    fun monthShort(ym: YearMonth): String =
        ym.format(DateTimeFormatter.ofPattern("MMM", locale)).replace(".", "")

    fun dayLabel(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            date == today -> "Hoje"
            date == today.minusDays(1) -> "Ontem"
            date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
    }

    fun fullDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", locale))

    fun shortDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("dd/MM", locale))

    fun ymOf(millis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

    fun epochFromDate(year: Int, month0: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDate.of(year, month0 + 1, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
