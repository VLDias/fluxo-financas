package app.fluxo.financas.ui

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Repo
import app.fluxo.financas.databinding.FragmentTransactionsBinding
import app.fluxo.financas.ui.adapters.TxnAdapter
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.visibleIf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.YearMonth

class TransactionsFragment : Fragment(R.layout.fragment_transactions), Refreshable {

    private var _binding: FragmentTransactionsBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }
    private var month: YearMonth = Dates.currentMonth()
    private var searchJob: Job? = null
    private lateinit var adapter: TxnAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTransactionsBinding.bind(view)
        savedInstanceState?.getString(KEY_MONTH)?.let { Dates.parseKey(it)?.let { m -> month = m } }

        adapter = TxnAdapter { txn ->
            TransactionEditorSheet.forTxn(txn.id).show(parentFragmentManager, "txn_editor")
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.prevMonth.setOnClickListener { shiftMonth(-1) }
        binding.nextMonth.setOnClickListener { shiftMonth(1) }
        binding.search.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(220)
                refreshData()
            }
        }
        parentFragmentManager.setFragmentResultListener(DATA_CHANGED, viewLifecycleOwner) { _, _ ->
            refreshData()
        }
        refreshData()
    }

    private fun shiftMonth(delta: Long) {
        val target = month.plusMonths(delta)
        if (target > Dates.currentMonth()) return
        month = target
        refreshData()
    }

    override fun refreshData() {
        if (_binding == null || !isAdded) return
        binding.monthLabel.text = Dates.monthLabel(month)
        binding.nextMonth.alpha = if (month >= Dates.currentMonth()) 0.25f else 1f
        val query = binding.search.text?.toString().orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val txns = repo.transactions(month, query)
            if (_binding == null) return@launch
            adapter.submit(txns)
            val expenses = txns.filter { it.isExpense }.sumOf { it.amountCents }
            binding.summaryLine.text = if (txns.isEmpty()) "" else
                getString(R.string.txn_count, txns.size, Money.format(expenses))
            binding.empty.visibleIf(txns.isEmpty())
            binding.list.visibleIf(txns.isNotEmpty())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
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
