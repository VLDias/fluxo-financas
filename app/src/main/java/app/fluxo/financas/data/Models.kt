package app.fluxo.financas.data

data class Category(
    val id: Long,
    val name: String,
    val emoji: String,
    val color: Int,
    val kind: String
) {
    val isExpense: Boolean get() = kind == "expense"
}

data class Account(val id: Long, val name: String, val bank: String?, val color: Int)

data class Txn(
    val id: Long = 0,
    val amountCents: Long,
    val isExpense: Boolean,
    val categoryId: Long,
    val accountId: Long?,
    val merchant: String,
    val note: String = "",
    val date: Long,
    val source: String = "manual",
    val sourceHash: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val categoryName: String = "",
    val categoryEmoji: String = "✨",
    val categoryColor: Int = 0xFF7A8C86.toInt(),
    val accountName: String = ""
)

data class Pending(
    val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val parsedAmount: Long? = null,
    val parsedExpense: Boolean? = null,
    val merchant: String = "",
    val categoryName: String? = null,
    val confidence: Float = 0f,
    val sourceHash: String? = null
)

data class BudgetRow(
    val category: Category,
    val limitCents: Long,
    val spentCents: Long
) {
    val ratio: Float get() = if (limitCents <= 0) 0f else (spentCents.toFloat() / limitCents).coerceAtLeast(0f)
}

data class Summary(
    val incomeCents: Long,
    val expenseCents: Long,
    val count: Int,
    val byCategory: List<Pair<Category, Long>>
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}
