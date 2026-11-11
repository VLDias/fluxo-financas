package app.fluxo.financas.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.fluxo.financas.forecast.MonthlyTotal
import app.fluxo.financas.notify.NotificationParser
import app.fluxo.financas.salary.MonthPayroll
import app.fluxo.financas.salary.PayrollCalculator2026
import app.fluxo.financas.salary.PayrollDiscount
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.salary.SalaryVersion
import app.fluxo.financas.salary.VacationConfig
import org.json.JSONArray
import org.json.JSONObject
import app.fluxo.financas.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

class Repo private constructor(context: Context) {

    private val helper = Db(context.applicationContext)

    companion object {
        @Volatile
        private var instance: Repo? = null

        fun get(context: Context): Repo = instance ?: synchronized(this) {
            instance ?: Repo(context.applicationContext).also { instance = it }
        }

        private const val BASE_TXN_SELECT = """SELECT t.id, t.amount_cents, t.is_expense, t.category_id,
            t.account_id, t.merchant, t.note, t.date, t.source, t.source_hash, t.created_at,
            c.name AS cat_name, c.emoji AS cat_emoji, c.color AS cat_color,
            a.name AS account_name
            FROM txns t
            JOIN categories c ON c.id = t.category_id
            LEFT JOIN accounts a ON a.id = t.account_id"""
    }

    private val read: SQLiteDatabase get() = helper.readableDatabase
    private val write: SQLiteDatabase get() = helper.writableDatabase

    suspend fun categories(kind: String? = null): List<Category> = withContext(Dispatchers.IO) {
        val where = if (kind != null) "kind = ?" else null
        val args = if (kind != null) arrayOf(kind) else null
        read.query("categories", null, where, args, null, null, "sort ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toCategory()) }
        }
    }

    suspend fun accounts(): List<Account> = withContext(Dispatchers.IO) {
        read.query("accounts", null, null, null, null, null, "name ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Account(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        bank = c.getString(c.getColumnIndexOrThrow("bank")),
                        color = c.getInt(c.getColumnIndexOrThrow("color"))
                    )
                )
            }
        }
    }

    suspend fun transactions(ym: YearMonth?, query: String = ""): List<Txn> = withContext(Dispatchers.IO) {
        val sb = StringBuilder(BASE_TXN_SELECT)
        val args = mutableListOf<String>()
        val where = mutableListOf<String>()
        if (ym != null) {
            val (start, end) = Dates.range(ym)
            where += "t.date >= ? AND t.date < ?"
            args += start.toString(); args += end.toString()
        }
        if (query.isNotBlank()) {
            where += "(t.merchant LIKE ? OR t.note LIKE ? OR c.name LIKE ?)"
            val q = "%${query.trim()}%"
            args += q; args += q; args += q
        }
        if (where.isNotEmpty()) sb.append(" WHERE ").append(where.joinToString(" AND "))
        sb.append(" ORDER BY t.date DESC, t.id DESC")
        read.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.toTxn()) }
        }
    }

    suspend fun txnById(id: Long): Txn? = withContext(Dispatchers.IO) {
        read.rawQuery("$BASE_TXN_SELECT WHERE t.id = ? LIMIT 1", arrayOf(id.toString())).use { c ->
            if (c.moveToNext()) c.toTxn() else null
        }
    }

    suspend fun recent(limit: Int = 5): List<Txn> = withContext(Dispatchers.IO) {
        read.rawQuery("$BASE_TXN_SELECT ORDER BY t.date DESC, t.id DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toTxn()) }
        }
    }

    suspend fun summary(ym: YearMonth): Summary = withContext(Dispatchers.IO) {
        val (start, end) = Dates.range(ym)
        var income = 0L
        var expense = 0L
        read.rawQuery(
            "SELECT is_expense, SUM(amount_cents) FROM txns WHERE date >= ? AND date < ? GROUP BY is_expense",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(0) == 1) expense = c.getLong(1) else income = c.getLong(1)
            }
        }
        val count = read.rawQuery(
            "SELECT COUNT(*) FROM txns WHERE date >= ? AND date < ?",
            arrayOf(start.toString(), end.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val cats = categories("expense").associateBy { it.id }
        val byCat = mutableListOf<Pair<Category, Long>>()
        read.rawQuery(
            """SELECT category_id, SUM(amount_cents) FROM txns
               WHERE is_expense = 1 AND date >= ? AND date < ?
               GROUP BY category_id ORDER BY SUM(amount_cents) DESC""",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) {
                cats[c.getLong(0)]?.let { byCat += it to c.getLong(1) }
            }
        }
        Summary(income, expense, count, byCat)
    }

    suspend fun budgets(ym: YearMonth): List<BudgetRow> = withContext(Dispatchers.IO) {
        val key = Dates.key(ym)
        val limits = mutableMapOf<Long, Long>()
        read.rawQuery("SELECT category_id, limit_cents FROM budgets WHERE ym = ?", arrayOf(key)).use { c ->
            while (c.moveToNext()) limits[c.getLong(0)] = c.getLong(1)
        }
        val spent = summaryInternalSpent(ym)
        categories("expense").map { cat ->
            BudgetRow(cat, limits[cat.id] ?: 0L, spent[cat.id] ?: 0L)
        }
    }

    private fun summaryInternalSpent(ym: YearMonth): Map<Long, Long> {
        val (start, end) = Dates.range(ym)
        val map = mutableMapOf<Long, Long>()
        read.rawQuery(
            "SELECT category_id, SUM(amount_cents) FROM txns WHERE is_expense=1 AND date>=? AND date<? GROUP BY category_id",
            arrayOf(start.toString(), end.toString())
        ).use { c -> while (c.moveToNext()) map[c.getLong(0)] = c.getLong(1) }
        return map
    }

    suspend fun setBudget(categoryId: Long, ym: YearMonth, limitCents: Long) = withContext(Dispatchers.IO) {
        if (limitCents <= 0) {
            write.delete("budgets", "category_id = ? AND ym = ?", arrayOf(categoryId.toString(), Dates.key(ym)))
        } else {
            val v = ContentValues().apply {
                put("category_id", categoryId)
                put("ym", Dates.key(ym))
                put("limit_cents", limitCents)
            }
            write.insertWithOnConflict("budgets", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun saveTxn(txn: Txn): Long = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("amount_cents", Math.abs(txn.amountCents))
            put("is_expense", if (txn.isExpense) 1 else 0)
            put("category_id", txn.categoryId)
            put("account_id", txn.accountId)
            put("merchant", txn.merchant.trim().ifBlank { if (txn.isExpense) "Despesa" else "Receita" })
            put("note", txn.note)
            put("date", txn.date)
            put("source", txn.source)
            if (txn.sourceHash != null) put("source_hash", txn.sourceHash)
            put("created_at", txn.createdAt)
        }
        if (txn.id > 0) {
            write.update("txns", v, "id = ?", arrayOf(txn.id.toString()))
            txn.id
        } else {
            write.insertWithOnConflict("txns", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    suspend fun deleteTxn(id: Long) = withContext(Dispatchers.IO) {
        write.delete("txns", "id = ?", arrayOf(id.toString()))
    }

    suspend fun pending(): List<Pending> = withContext(Dispatchers.IO) {
        read.query("pending", null, null, null, null, null, "posted_at DESC").use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Pending(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        packageName = c.getString(c.getColumnIndexOrThrow("pkg")),
                        appLabel = c.getString(c.getColumnIndexOrThrow("app_label")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        text = c.getString(c.getColumnIndexOrThrow("text")),
                        postedAt = c.getLong(c.getColumnIndexOrThrow("posted_at")),
                        parsedAmount = c.getLongOrNull("parsed_amount"),
                        parsedExpense = c.getIntOrNull("parsed_expense")?.let { it == 1 },
                        merchant = c.getString(c.getColumnIndexOrThrow("merchant")),
                        categoryName = c.getString(c.getColumnIndexOrThrow("category_name")),
                        confidence = c.getFloat(c.getColumnIndexOrThrow("confidence")),
                        sourceHash = c.getString(c.getColumnIndexOrThrow("source_hash"))
                    )
                )
            }
        }
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        read.rawQuery("SELECT COUNT(*) FROM pending", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun addPending(p: Pending): Long = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("pkg", p.packageName)
            put("app_label", p.appLabel)
            put("title", p.title)
            put("text", p.text)
            put("posted_at", p.postedAt)
            if (p.parsedAmount != null) put("parsed_amount", p.parsedAmount) else putNull("parsed_amount")
            if (p.parsedExpense != null) put("parsed_expense", if (p.parsedExpense) 1 else 0) else putNull("parsed_expense")
            put("merchant", p.merchant)
            put("category_name", p.categoryName)
            put("confidence", p.confidence)
            put("source_hash", p.sourceHash)
        }
        write.insertWithOnConflict("pending", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    suspend fun deletePending(id: Long) = withContext(Dispatchers.IO) {
        write.delete("pending", "id = ?", arrayOf(id.toString()))
    }

    suspend fun insertImported(
        pkg: String,
        appLabel: String,
        rawText: String,
        amountCents: Long,
        isExpense: Boolean,
        merchant: String,
        categoryName: String,
        postedAt: Long,
        sourceHash: String
    ): Boolean = withContext(Dispatchers.IO) {
        val kind = if (isExpense) "expense" else "income"
        val category = categories(kind).firstOrNull { it.name == categoryName }
            ?: categories(kind).firstOrNull { it.name == if (isExpense) "Outros" else "Outras receitas" }
            ?: return@withContext false
        val accountId = accountFor(KnownApps.labelFor(pkg), KnownApps.find(pkg)?.color ?: 0xFF6B8F8A.toInt())
        val v = ContentValues().apply {
            put("amount_cents", Math.abs(amountCents))
            put("is_expense", if (isExpense) 1 else 0)
            put("category_id", category.id)
            put("account_id", accountId)
            put("merchant", merchant.ifBlank { appLabel })
            put("note", rawText.take(400))
            put("date", postedAt)
            put("source", "notificacao")
            put("source_hash", sourceHash)
            put("created_at", System.currentTimeMillis())
        }
        write.insertWithOnConflict("txns", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun accountFor(name: String, color: Int): Long {
        read.rawQuery("SELECT id FROM accounts WHERE name = ? LIMIT 1", arrayOf(name)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val v = ContentValues().apply {
            put("name", name); put("bank", name); put("color", color)
        }
        return write.insert("accounts", null, v)
    }

    suspend fun seedDemo() = withContext(Dispatchers.IO) {
        write.delete("txns", "source = 'exemplo'", null)
        val ym = Dates.currentMonth()
        val cats = categories().associateBy { it.name }
        fun cat(name: String) = cats[name]?.id ?: 12L
        val days = listOf(
            arrayOf(3, "Salário", false, 680000L, "Salário mensal"),
            arrayOf(4, "Moradia", true, 185000L, "Aluguel"),
            arrayOf(5, "Mercado", true, 41230L, "Supermercado"),
            arrayOf(6, "Alimentação", true, 4890L, "iFood"),
            arrayOf(7, "Transporte", true, 2390L, "Uber"),
            arrayOf(8, "Assinaturas", true, 5590L, "Netflix"),
            arrayOf(9, "Mercado", true, 28740L, "Pão de Açúcar"),
            arrayOf(10, "Lazer", true, 7200L, "Cinema"),
            arrayOf(12, "Saúde", true, 13400L, "Drogasil"),
            arrayOf(14, "Alimentação", true, 3290L, "Padaria"),
            arrayOf(16, "Transporte", true, 18000L, "Posto Ipiranga"),
            arrayOf(18, "Compras", true, 21990L, "Amazon"),
            arrayOf(20, "Moradia", true, 18990L, "Conta de luz"),
            arrayOf(22, "Serviços", true, 4990L, "Internet"),
            arrayOf(24, "Alimentação", true, 6750L, "Restaurante")
        )
        for (d in days) {
            val day = minOf(d[0] as Int, ym.lengthOfMonth())
            val v = ContentValues().apply {
                put("amount_cents", d[3] as Long)
                put("is_expense", if (d[2] as Boolean) 1 else 0)
                put("category_id", cat(d[1] as String))
                put("account_id", 1L)
                put("merchant", d[4] as String)
                put("note", "")
                put("date", Dates.epochFromDate(ym.year, ym.monthValue - 1, day))
                put("source", "exemplo")
                put("created_at", System.currentTimeMillis())
            }
            write.insert("txns", null, v)
        }
        val budgetMap = mapOf(
            "Alimentação" to 70000L, "Mercado" to 90000L, "Transporte" to 40000L,
            "Moradia" to 220000L, "Lazer" to 30000L, "Assinaturas" to 15000L,
            "Saúde" to 25000L, "Compras" to 35000L
        )
        for ((name, limit) in budgetMap) {
            val v = ContentValues().apply {
                put("category_id", cat(name)); put("ym", Dates.key(ym)); put("limit_cents", limit)
            }
            write.insertWithOnConflict("budgets", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
        true
    }

    suspend fun wipe() = withContext(Dispatchers.IO) {
        write.delete("txns", null, null)
        write.delete("budgets", null, null)
        write.delete("pending", null, null)
        true
    }

    suspend fun monthlyExpenses(count: Int): List<Pair<YearMonth, Long>> = withContext(Dispatchers.IO) {
        val startYm = Dates.currentMonth().minusMonths((count - 1).toLong())
        val (start, _) = Dates.range(startYm)
        val map = mutableMapOf<String, Long>()
        read.rawQuery(
            """SELECT strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime') AS ym,
                      SUM(amount_cents) FROM txns
               WHERE is_expense = 1 AND date >= ? GROUP BY ym""",
            arrayOf(start.toString())
        ).use { c -> while (c.moveToNext()) map[c.getString(0)] = c.getLong(1) }
        (0 until count).map { offset ->
            val ym = startYm.plusMonths(offset.toLong())
            ym to (map[Dates.key(ym)] ?: 0L)
        }
    }

    suspend fun monthlyTotals(count: Int): List<MonthlyTotal> = withContext(Dispatchers.IO) {
        val startYm = Dates.currentMonth().minusMonths((count - 1).toLong())
        val (start, _) = Dates.range(startYm)
        val map = mutableMapOf<String, Pair<Long, Long>>()
        read.rawQuery(
            """SELECT strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime') AS ym,
                      SUM(CASE WHEN is_expense = 0 THEN amount_cents ELSE 0 END) AS income,
                      SUM(CASE WHEN is_expense = 1 THEN amount_cents ELSE 0 END) AS expense
               FROM txns WHERE date >= ? GROUP BY ym""",
            arrayOf(start.toString())
        ).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getLong(1) to c.getLong(2)
        }
        (0 until count).map { offset ->
            val ym = startYm.plusMonths(offset.toLong())
            val values = map[Dates.key(ym)] ?: (0L to 0L)
            MonthlyTotal(ym, values.first, values.second)
        }
    }

    suspend fun totalBalance(): Long = withContext(Dispatchers.IO) {
        read.rawQuery(
            "SELECT SUM(CASE WHEN is_expense = 1 THEN -amount_cents ELSE amount_cents END) FROM txns",
            null
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    /** Saldo realizado até o instante informado (ignora lançamentos futuros). */
    suspend fun balanceUntil(millis: Long): Long = withContext(Dispatchers.IO) {
        read.rawQuery(
            """SELECT SUM(CASE WHEN is_expense = 1 THEN -amount_cents ELSE amount_cents END)
               FROM txns WHERE date <= ?""",
            arrayOf(millis.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    suspend fun allTransactions(): List<Txn> = withContext(Dispatchers.IO) {
        read.rawQuery("$BASE_TXN_SELECT ORDER BY t.date DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toTxn()) }
        }
    }

    suspend fun salaryVersion(ym: YearMonth): SalaryVersion = withContext(Dispatchers.IO) {
        read.rawQuery(
            "SELECT * FROM salary_history WHERE effective_ym <= ? ORDER BY effective_ym DESC LIMIT 1",
            arrayOf(Dates.key(ym))
        ).use { c ->
            if (!c.moveToNext()) SalaryVersion(SalaryConfig(), null)
            else SalaryVersion(c.toSalaryConfig(), Dates.parseKey(c.getString(c.getColumnIndexOrThrow("effective_ym"))))
        }
    }

    suspend fun salaryConfig(ym: YearMonth): SalaryConfig = salaryVersion(ym).config

    suspend fun saveSalaryConfig(config: SalaryConfig, effectiveYm: YearMonth) = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("effective_ym", Dates.key(effectiveYm))
            put("gross_cents", config.grossCents)
            put("dependents", config.dependents)
            put("first_percent", config.firstPercent)
            put("first_day", config.firstDay)
            put("second_day", config.secondDay)
            put("second_next_month", if (config.secondNextMonth) 1 else 0)
            put("second_business_day", if (config.secondBusinessDay) 1 else 0)
            put("second_business_day_number", config.secondBusinessDayNumber)
            put("irrf_on_first", if (config.irrfOnFirst) 1 else 0)
            put("thirteenth_enabled", if (config.thirteenthEnabled) 1 else 0)
            put("thirteenth_month1", config.thirteenthFirstMonth)
            put("thirteenth_month2", config.thirteenthSecondMonth)
            put("thirteenth_day1", config.thirteenthFirstDay)
            put("thirteenth_day2", config.thirteenthSecondDay)
            put("months_worked", config.monthsWorked)
            put("discounts_json", encodeDiscounts(config.discounts))
        }
        write.insertWithOnConflict("salary_history", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        true
    }

    suspend fun clearSalaryHistory() = withContext(Dispatchers.IO) {
        write.delete("salary_history", null, null)
        write.delete("salary_config", null, null)
        true
    }

    suspend fun vacation(ym: YearMonth): VacationConfig = withContext(Dispatchers.IO) {
        read.rawQuery("SELECT days, sell_ten FROM salary_vacations WHERE ym = ?", arrayOf(Dates.key(ym))).use { c ->
            if (c.moveToNext()) VacationConfig(c.getInt(0), c.getInt(1) == 1) else VacationConfig()
        }
    }

    suspend fun saveVacation(ym: YearMonth, vacation: VacationConfig) = withContext(Dispatchers.IO) {
        if (vacation.days <= 0) {
            write.delete("salary_vacations", "ym = ?", arrayOf(Dates.key(ym)))
        } else {
            val v = ContentValues().apply {
                put("ym", Dates.key(ym))
                put("days", vacation.days.coerceIn(0, 30))
                put("sell_ten", if (vacation.sellTenDays) 1 else 0)
            }
            write.insertWithOnConflict("salary_vacations", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
        true
    }

    suspend fun launchSalary(ym: YearMonth, config: SalaryConfig, payroll: MonthPayroll): Int =
        withContext(Dispatchers.IO) {
            val categoryId = categories("income").firstOrNull { it.name == "Salário" }?.id ?: 13L
            val accountId = read.rawQuery(
                "SELECT id FROM accounts ORDER BY CASE WHEN name = 'Conta principal' THEN 0 ELSE 1 END, id LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            val monthLength = ym.lengthOfMonth()
            var count = 0
            fun insert(date: LocalDate, label: String, amount: Long, hash: String, note: String) {
                if (amount <= 0) return
                val v = ContentValues().apply {
                    put("amount_cents", amount)
                    put("is_expense", 0)
                    put("category_id", categoryId)
                    put("account_id", accountId)
                    put("merchant", label)
                    put("note", note)
                    put("date", Dates.epochFromDate(date.year, date.monthValue - 1, date.dayOfMonth, 9, 0))
                    put("source", "salario")
                    put("source_hash", hash)
                    put("created_at", System.currentTimeMillis())
                }
                if (write.insertWithOnConflict("txns", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) count++
            }
            val hasVacation = payroll.vacationBase > 0
            val note = buildString {
                append("INSS ${Money.format(payroll.inss)} · IRRF ${Money.format(payroll.irrf.tax)}")
                if (hasVacation) {
                    append(" · Férias ${Money.format(payroll.vacationBase + payroll.vacationThird)}")
                    append(" · Abono ${Money.format(payroll.abonoBase + payroll.abonoThird)}")
                }
                append(" · calculado pelo Fluxo")
            }
            val firstLabel = if (hasVacation) "Salário + férias — 1ª parcela" else "Salário — 1ª parcela"
            val secondLabel = if (hasVacation) "Salário + férias — 2ª parcela" else "Salário — 2ª parcela"
            val firstDate = LocalDate.of(ym.year, ym.monthValue, config.firstDay.coerceIn(1, monthLength))
            val secondDate = PayrollCalculator2026.secondPaymentDate(config, ym)
            insert(firstDate, firstLabel, payroll.firstInstallment, "salary:${Dates.key(ym)}:1", note)
            insert(secondDate, secondLabel, payroll.secondInstallment, "salary:${Dates.key(ym)}:2", note)
            if (payroll.thirteenthThisMonth > 0) {
                val first = ym.monthValue == config.thirteenthFirstMonth
                val configuredDay = if (first) config.thirteenthFirstDay else config.thirteenthSecondDay
                val day = if (configuredDay <= 0) monthLength else configuredDay.coerceIn(1, monthLength)
                insert(
                    LocalDate.of(ym.year, ym.monthValue, day),
                    if (first) "13º salário — 1ª parcela" else "13º salário — 2ª parcela",
                    payroll.thirteenthThisMonth,
                    "salary13:${ym.year}:${if (first) 1 else 2}",
                    "Calculado pelo Fluxo"
                )
            }
            count
        }

    private fun encodeDiscounts(list: List<PayrollDiscount>): String {
        val array = JSONArray()
        list.forEach { d ->
            array.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("percent", d.percent)
                    .put("value", d.value)
                    .put("apply13", d.applyToThirteenth)
            )
        }
        return array.toString()
    }

    private fun decodeDiscounts(json: String?): List<PayrollDiscount> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        PayrollDiscount(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            percent = o.optBoolean("percent"),
                            value = o.optLong("value"),
                            applyToThirteenth = o.optBoolean("apply13", true)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun hashFor(pkg: String, text: String, postedAt: Long): String {
        val day = Dates.ymOf(postedAt).toString() + postedAt / 86_400_000L
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$pkg|${NotificationParser.normalize(text)}|$day".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun Cursor.getLongOrNull(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.getIntOrNull(name: String): Int? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getInt(i)
    }

    private fun Cursor.toSalaryConfig(): SalaryConfig = SalaryConfig(
        grossCents = getLong(getColumnIndexOrThrow("gross_cents")),
        dependents = getInt(getColumnIndexOrThrow("dependents")),
        firstPercent = getInt(getColumnIndexOrThrow("first_percent")),
        firstDay = getInt(getColumnIndexOrThrow("first_day")),
        secondDay = getInt(getColumnIndexOrThrow("second_day")),
        secondNextMonth = getInt(getColumnIndexOrThrow("second_next_month")) == 1,
        secondBusinessDay = getInt(getColumnIndexOrThrow("second_business_day")) == 1,
        secondBusinessDayNumber = getInt(getColumnIndexOrThrow("second_business_day_number")),
        irrfOnFirst = getInt(getColumnIndexOrThrow("irrf_on_first")) == 1,
        thirteenthEnabled = getInt(getColumnIndexOrThrow("thirteenth_enabled")) == 1,
        thirteenthFirstMonth = getInt(getColumnIndexOrThrow("thirteenth_month1")),
        thirteenthSecondMonth = getInt(getColumnIndexOrThrow("thirteenth_month2")),
        thirteenthFirstDay = getInt(getColumnIndexOrThrow("thirteenth_day1")),
        thirteenthSecondDay = getInt(getColumnIndexOrThrow("thirteenth_day2")),
        monthsWorked = getInt(getColumnIndexOrThrow("months_worked")),
        discounts = decodeDiscounts(getString(getColumnIndexOrThrow("discounts_json")))
    )

    private fun Cursor.toCategory(): Category = Category(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        emoji = getString(getColumnIndexOrThrow("emoji")),
        color = getInt(getColumnIndexOrThrow("color")),
        kind = getString(getColumnIndexOrThrow("kind"))
    )

    private fun Cursor.toTxn(): Txn = Txn(
        id = getLong(getColumnIndexOrThrow("id")),
        amountCents = getLong(getColumnIndexOrThrow("amount_cents")),
        isExpense = getInt(getColumnIndexOrThrow("is_expense")) == 1,
        categoryId = getLong(getColumnIndexOrThrow("category_id")),
        accountId = getLongOrNull("account_id"),
        merchant = getString(getColumnIndexOrThrow("merchant")),
        note = getString(getColumnIndexOrThrow("note")),
        date = getLong(getColumnIndexOrThrow("date")),
        source = getString(getColumnIndexOrThrow("source")),
        sourceHash = getString(getColumnIndexOrThrow("source_hash")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        categoryName = getString(getColumnIndexOrThrow("cat_name")),
        categoryEmoji = getString(getColumnIndexOrThrow("cat_emoji")),
        categoryColor = getInt(getColumnIndexOrThrow("cat_color")),
        accountName = getString(getColumnIndexOrThrow("account_name")) ?: ""
    )
}
