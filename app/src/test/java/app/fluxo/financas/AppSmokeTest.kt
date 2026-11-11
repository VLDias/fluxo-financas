package app.fluxo.financas

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import app.fluxo.financas.data.Repo
import app.fluxo.financas.data.Txn
import app.fluxo.financas.ui.MainActivity
import app.fluxo.financas.ui.TransactionEditorSheet
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FluxoApp::class)
class AppSmokeTest {

    @Test
    fun dashboardAndTabsInflate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        assertNotNull(nav)
        assertEquals(R.id.nav_summary, nav.selectedItemId)

        nav.selectedItemId = R.id.nav_txns
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity.findViewById<RecyclerView>(R.id.list))

        nav.selectedItemId = R.id.nav_budgets
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity.findViewById<RecyclerView>(R.id.list))

        nav.selectedItemId = R.id.nav_settings
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.autoImportSwitch))

        controller.pause().stop().destroy()
    }

    @Test
    fun tabsNeverOverlay() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        val ids = listOf(R.id.nav_summary, R.id.nav_txns, R.id.nav_salary, R.id.nav_budgets, R.id.nav_settings)

        repeat(30) { i -> nav.selectedItemId = ids[i % ids.size] }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, activity.supportFragmentManager.fragments.count { it.isVisible })
        val container = activity.findViewById<ViewGroup>(R.id.container)
        val visibleChildren = (0 until container.childCount).count {
            container.getChildAt(it).visibility == android.view.View.VISIBLE
        }
        assertEquals(1, visibleChildren)

        val recreated = controller.recreate().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, recreated.supportFragmentManager.fragments.count { it.isVisible })
        controller.pause().stop().destroy()
    }

    @Test
    fun transactionSheetInflates() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val sheet = TransactionEditorSheet()
        sheet.show(activity.supportFragmentManager, "editor")
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(sheet.dialog)
        assertNotNull(sheet.dialog?.findViewById<android.view.View>(R.id.amount))
        sheet.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun repositoryRoundTrip(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = Repo.get(context)
        repo.wipe()
        val categories = repo.categories("expense")
        assertTrue(categories.isNotEmpty())
        val id = repo.saveTxn(
            Txn(
                amountCents = 1234,
                isExpense = true,
                categoryId = categories.first().id,
                accountId = null,
                merchant = "Teste",
                date = System.currentTimeMillis()
            )
        )
        assertTrue(id > 0)
        val summary = repo.summary(YearMonth.now())
        assertEquals(1234L, summary.expenseCents)
        assertEquals(1, summary.count)
        repo.wipe()
    }
}
