package app.fluxo.financas.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.fluxo.financas.R
import app.fluxo.financas.data.BudgetRow
import app.fluxo.financas.data.Money
import app.fluxo.financas.databinding.ItemCategoryBudgetBinding
import app.fluxo.financas.util.applyBudgetRatio

class BudgetAdapter(
    private val rows: List<BudgetRow>,
    private val onClick: (BudgetRow) -> Unit
) : RecyclerView.Adapter<BudgetAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCategoryBudgetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    inner class Holder(val binding: ItemCategoryBudgetBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BudgetRow) {
            val context = binding.root.context
            bindBudget(binding, row)
            binding.root.setOnClickListener { onClick(row) }
            binding.root.contentDescription =
                "${row.category.name}: ${Money.format(row.spentCents)} gastos, limite ${Money.format(row.limitCents)}"
            binding.progress.setIndicatorColor(
                ContextCompat.getColor(
                    context,
                    when {
                        row.limitCents <= 0 -> R.color.divider
                        row.ratio > 1f -> R.color.expense
                        row.ratio >= 0.8f -> R.color.accent
                        else -> R.color.primary
                    }
                )
            )
        }
    }
}

fun bindBudget(binding: ItemCategoryBudgetBinding, row: BudgetRow) {
    val context = binding.root.context
    binding.emoji.text = row.category.emoji
    binding.name.text = row.category.name
    if (row.limitCents <= 0) {
        binding.sub.text = context.getString(R.string.no_limit)
        binding.percent.text = "—"
        binding.progress.visibility = android.view.View.GONE
    } else {
        binding.progress.visibility = android.view.View.VISIBLE
        binding.progress.trackStopIndicatorSize = 0
        binding.sub.text = context.getString(
            R.string.budget_of,
            Money.format(row.spentCents),
            Money.format(row.limitCents)
        )
        binding.percent.text = context.getString(R.string.percent, (row.ratio * 100).toInt())
        binding.progress.applyBudgetRatio(row.ratio)
    }
}
