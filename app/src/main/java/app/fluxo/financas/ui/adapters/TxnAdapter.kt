package app.fluxo.financas.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Txn
import app.fluxo.financas.databinding.ItemDayHeaderBinding
import app.fluxo.financas.databinding.ItemTransactionBinding
import app.fluxo.financas.util.Dates

class TxnAdapter(private val onClick: (Txn) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val label: String) : Row
        data class Item(val txn: Txn) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(txns: List<Txn>) {
        val out = mutableListOf<Row>()
        var lastLabel: String? = null
        txns.forEach { txn ->
            val label = Dates.dayLabel(txn.date)
            if (label != lastLabel) {
                out += Row.Header(label)
                lastLabel = label
            }
            out += Row.Item(txn)
        }
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemTransactionBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.label)
            is Row.Item -> (holder as ItemHolder).bind(row.txn)
        }
    }

    private class HeaderHolder(val binding: ItemDayHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.day.text = label
        }
    }

    private inner class ItemHolder(val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(txn: Txn) {
            val context = binding.root.context
            binding.emoji.text = txn.categoryEmoji
            binding.merchant.text = txn.merchant.ifBlank { context.getString(R.string.untitled) }
            val source = if (txn.source == "notificacao") " · auto" else ""
            binding.subtitle.text = "${txn.categoryName} · ${Dates.shortDate(txn.date)}$source"
            val sign = if (txn.isExpense) "− " else "+ "
            binding.amount.text = sign + Money.format(txn.amountCents)
            binding.amount.setTextColor(
                ContextCompat.getColor(context, if (txn.isExpense) R.color.expense else R.color.income)
            )
            binding.root.setOnClickListener { onClick(txn) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
