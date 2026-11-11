package app.fluxo.financas.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.fluxo.financas.R
import app.fluxo.financas.data.Account
import app.fluxo.financas.data.Category
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Pending
import app.fluxo.financas.data.Repo
import app.fluxo.financas.data.Txn
import app.fluxo.financas.databinding.SheetTransactionBinding
import app.fluxo.financas.util.Dates
import app.fluxo.financas.util.applyBottomInset
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Calendar

class TransactionEditorSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTransactionBinding? = null
    private val binding get() = _binding!!
    private val repo by lazy { Repo.get(requireContext()) }

    private var categories: List<Category> = emptyList()
    private var accounts: List<Account> = emptyList()
    private var selectedCategoryId = 0L
    private var selectedAccountId: Long? = null
    private var dateMillis = System.currentTimeMillis()
    private var isExpense = true
    private var editing: Txn? = null
    private var initialized = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args: Bundle = arguments ?: Bundle.EMPTY
        val editId = args.getLong(ARG_TXN_ID, -1L)
        val pendingId = args.getLong(ARG_PENDING_ID, -1L)
        applyBottomInset(binding.root)

        binding.title.text = getString(if (editId > 0) R.string.edit_txn else R.string.new_txn)
        binding.delete.isVisible = editId > 0

        binding.typeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || !initialized) return@addOnButtonCheckedListener
            isExpense = checkedId == R.id.btnExpense
            selectedCategoryId = 0
            populateCategories()
        }
        binding.date.setOnClickListener { pickDate() }
        binding.save.setOnClickListener { save(pendingId) }
        binding.delete.setOnClickListener { confirmDelete() }

        viewLifecycleOwner.lifecycleScope.launch {
            categories = repo.categories()
            accounts = repo.accounts()
            editing = if (editId > 0) repo.txnById(editId) else null

            val prefillAmount = args.getLong(ARG_AMOUNT, 0L)
            val prefillText = args.getString(ARG_TEXT).orEmpty()
            isExpense = editing?.isExpense ?: args.getBoolean(ARG_EXPENSE, true)
            selectedCategoryId = editing?.categoryId ?: 0L
            selectedAccountId = editing?.accountId ?: accounts.firstOrNull()?.id
            dateMillis = editing?.date ?: args.getLong(ARG_DATE, System.currentTimeMillis())
            initialized = true

            binding.typeGroup.check(if (isExpense) R.id.btnExpense else R.id.btnIncome)
            populateCategories()
            setupAccounts()
            binding.date.setText(Dates.fullDate(dateMillis))
            binding.amount.setText(
                when {
                    editing != null -> Money.formatPlain(editing!!.amountCents)
                    prefillAmount > 0 -> Money.formatPlain(prefillAmount)
                    else -> ""
                }
            )
            binding.merchant.setText(editing?.merchant ?: args.getString(ARG_MERCHANT).orEmpty())
            binding.note.setText(editing?.note ?: prefillText)
        }
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
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun populateCategories() {
        if (_binding == null) return
        val list = categories.filter { it.isExpense == isExpense }
        if (list.isEmpty()) return
        val current = list.firstOrNull { it.id == selectedCategoryId } ?: list.first()
        selectedCategoryId = current.id
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            list.map { it.name }
        )
        binding.category.setAdapter(adapter)
        binding.category.setText(current.name, false)
        binding.category.setOnItemClickListener { _, _, position, _ ->
            selectedCategoryId = list[position].id
        }
    }

    private fun setupAccounts() {
        if (_binding == null) return
        if (accounts.isEmpty()) return
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            accounts.map { it.name }
        )
        binding.account.setAdapter(adapter)
        val current = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.first()
        selectedAccountId = current.id
        binding.account.setText(current.name, false)
        binding.account.setOnItemClickListener { _, _, position, _ ->
            selectedAccountId = accounts[position].id
        }
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                dateMillis = Dates.epochFromDate(year, month, day)
                binding.date.setText(Dates.fullDate(dateMillis))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun save(pendingId: Long) {
        binding.amountLayout.error = null
        binding.merchantLayout.error = null
        val cents = Money.parse(binding.amount.text?.toString())
        if (cents == null || cents <= 0) {
            binding.amountLayout.error = getString(R.string.required_fields)
            return
        }
        val merchant = binding.merchant.text?.toString()?.trim().orEmpty()
        if (merchant.isBlank()) {
            binding.merchantLayout.error = getString(R.string.required_fields)
            return
        }
        if (selectedCategoryId == 0L) {
            selectedCategoryId = categories.firstOrNull { it.isExpense == isExpense }?.id ?: return
        }
        val txn = Txn(
            id = editing?.id ?: 0,
            amountCents = cents,
            isExpense = isExpense,
            categoryId = selectedCategoryId,
            accountId = selectedAccountId,
            merchant = merchant,
            note = binding.note.text?.toString().orEmpty(),
            date = dateMillis,
            source = editing?.source ?: "manual",
            sourceHash = editing?.sourceHash,
            createdAt = editing?.createdAt ?: System.currentTimeMillis()
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repo.saveTxn(txn)
            if (pendingId > 0) repo.deletePending(pendingId)
            setFragmentResult(DATA_CHANGED, Bundle.EMPTY)
            android.widget.Toast.makeText(requireContext(), R.string.added, android.widget.Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun confirmDelete() {
        val txn = editing ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_txn_title)
            .setMessage(R.string.delete_txn_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.deleteTxn(txn.id)
                    setFragmentResult(DATA_CHANGED, Bundle.EMPTY)
                    dismiss()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val DATA_CHANGED = "fluxo_data"
        private const val ARG_TXN_ID = "txn_id"
        private const val ARG_PENDING_ID = "pending_id"
        private const val ARG_AMOUNT = "amount"
        private const val ARG_EXPENSE = "expense"
        private const val ARG_MERCHANT = "merchant"
        private const val ARG_TEXT = "text"
        private const val ARG_DATE = "date"

        fun forTxn(id: Long): TransactionEditorSheet = TransactionEditorSheet().apply {
            arguments = Bundle().apply { putLong(ARG_TXN_ID, id) }
        }

        fun forPending(pending: Pending): TransactionEditorSheet = TransactionEditorSheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_PENDING_ID, pending.id)
                pending.parsedAmount?.let { putLong(ARG_AMOUNT, it) }
                pending.parsedExpense?.let { putBoolean(ARG_EXPENSE, it) }
                putString(ARG_MERCHANT, pending.merchant)
                putString(ARG_TEXT, pending.text)
                putLong(ARG_DATE, pending.postedAt)
            }
        }
    }
}
