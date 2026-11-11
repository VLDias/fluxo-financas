package app.fluxo.financas.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Pending
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.ItemPendingBinding
import app.fluxo.financas.databinding.SheetReviewBinding
import app.fluxo.financas.util.applyBottomInset
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class ReviewSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReviewBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyBottomInset(binding.root)
        load()
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = repo.pending()
            if (_binding == null) return@launch
            binding.container.removeAllViews()
            binding.empty.isVisible = items.isEmpty()
            items.forEach { item -> binding.container.addView(buildRow(item)) }
        }
    }

    private fun buildRow(pending: Pending): View {
        val row = ItemPendingBinding.inflate(layoutInflater, binding.container, false)
        row.appLabel.text = "${pending.appLabel} · ${app.fluxo.financas.util.Dates.dayLabel(pending.postedAt)}".uppercase()
        row.text.text = pending.text.ifBlank { pending.title }
        val amount = pending.parsedAmount
        row.suggestion.text = when {
            amount != null && amount > 0 -> {
                val sign = if (pending.parsedExpense == false) "+" else "−"
                "Sugestão: $sign ${app.fluxo.financas.data.Money.format(amount)}" +
                    (pending.categoryName?.let { " · $it" } ?: "")
            }
            else -> getString(R.string.review_failed)
        }
        row.confirm.setOnClickListener { confirm(pending) }
        row.ignore.setOnClickListener { ignore(pending) }
        return row.root
    }

    private fun confirm(pending: Pending) {
        val amount = pending.parsedAmount
        val expense = pending.parsedExpense
        if (amount == null || amount <= 0 || expense == null) {
            TransactionEditorSheet.forPending(pending).show(parentFragmentManager, "txn_editor")
            dismiss()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repo.insertImported(
                pkg = pending.packageName,
                appLabel = pending.appLabel,
                rawText = listOf(pending.title, pending.text).filter { it.isNotBlank() }.joinToString(" — "),
                amountCents = amount,
                isExpense = expense,
                merchant = pending.merchant.ifBlank { pending.appLabel },
                categoryName = pending.categoryName ?: if (expense) "Outros" else "Outras receitas",
                postedAt = pending.postedAt,
                sourceHash = pending.sourceHash
                    ?: repo.hashFor(pending.packageName, pending.text, pending.postedAt)
            )
            repo.deletePending(pending.id)
            setFragmentResult("fluxo_data", Bundle.EMPTY)
            android.widget.Toast.makeText(requireContext(), R.string.added, android.widget.Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private fun ignore(pending: Pending) {
        viewLifecycleOwner.lifecycleScope.launch {
            repo.deletePending(pending.id)
            setFragmentResult("fluxo_data", Bundle.EMPTY)
            load()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
