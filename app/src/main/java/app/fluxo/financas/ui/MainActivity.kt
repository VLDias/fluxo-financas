package app.fluxo.financas.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import app.fluxo.financas.R
import app.fluxo.financas.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String? = null

    private val tags = listOf("summary", "txns", "salary", "budgets", "settings")
    private val navIds = listOf(
        R.id.nav_summary, R.id.nav_txns, R.id.nav_salary, R.id.nav_budgets, R.id.nav_settings
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.container.setPadding(0, bars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        val light = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = light
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightNavigationBars = light

        binding.bottomNav.setOnItemSelectedListener { item ->
            val index = navIds.indexOf(item.itemId)
            if (index >= 0) {
                showTab(index)
                true
            } else false
        }

        binding.fab.setOnClickListener {
            TransactionEditorSheet().show(supportFragmentManager, "txn_editor")
        }

        val initial = savedInstanceState?.getInt(KEY_TAB, 0)?.coerceIn(0, tags.lastIndex) ?: 0
        showTab(initial)
        binding.bottomNav.selectedItemId = navIds[initial]
    }

    private fun showTab(index: Int) {
        val safeIndex = index.coerceIn(0, tags.lastIndex)
        val tag = tags[safeIndex]
        val fm = supportFragmentManager
        if (fm.isStateSaved) return

        // commitNow serializa a troca: nunca deixa duas abas visíveis ao mesmo tempo,
        // mesmo com toques rápidos ou restauração de estado.
        fm.commitNow {
            tags.forEach { other ->
                if (other != tag) fm.findFragmentByTag(other)?.let { hide(it) }
            }
            val existing = fm.findFragmentByTag(tag)
            if (existing == null) {
                add(R.id.container, createFragment(safeIndex), tag)
            } else {
                show(existing)
            }
        }
        currentTag = tag
        binding.fab.visibility = if (safeIndex == 1) android.view.View.VISIBLE else android.view.View.GONE
        (fm.findFragmentByTag(tag) as? Refreshable)?.refreshData()
    }

    private fun createFragment(index: Int): Fragment = when (index) {
        0 -> DashboardFragment()
        1 -> TransactionsFragment()
        2 -> SalaryFragment()
        3 -> BudgetsFragment()
        else -> SettingsFragment()
    }

    fun selectTab(index: Int) {
        val safeIndex = index.coerceIn(0, navIds.lastIndex)
        if (binding.bottomNav.selectedItemId == navIds[safeIndex]) {
            showTab(safeIndex)
        } else {
            binding.bottomNav.selectedItemId = navIds[safeIndex]
        }
    }

    fun refreshAll() {
        supportFragmentManager.fragments.forEach { (it as? Refreshable)?.refreshData() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, tags.indexOf(currentTag).takeIf { it >= 0 } ?: 0)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_TAB = "selected_tab"
    }
}
