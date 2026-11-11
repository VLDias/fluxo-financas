package app.fluxo.financas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import app.fluxo.financas.data.Pending
import app.fluxo.financas.data.Repo
import app.fluxo.financas.data.Txn
import app.fluxo.financas.notify.NotificationParser
import app.fluxo.financas.salary.PayrollDiscount
import app.fluxo.financas.salary.SalaryConfig
import app.fluxo.financas.salary.VacationConfig
import app.fluxo.financas.ui.BudgetEditorSheet
import app.fluxo.financas.ui.ForecastSheet
import app.fluxo.financas.ui.SalaryConfigSheet
import app.fluxo.financas.ui.MainActivity
import app.fluxo.financas.ui.ReviewSheet
import app.fluxo.financas.ui.TransactionEditorSheet
import app.fluxo.financas.util.Dates
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = FluxoApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private val outDir = File(
        System.getenv("FLUXO_SCREENSHOTS_DIR") ?: "build/screenshots"
    ).apply { mkdirs() }

    @Test
    fun captureScreens(): Unit = runBlocking {
        Assume.assumeTrue(
            "Defina FLUXO_SCREENSHOTS=1 para gerar as capturas",
            System.getenv("FLUXO_SCREENSHOTS") == "1"
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = Repo.get(context)
        repo.wipe()
        repo.seedDemo()
        val now = Dates.currentMonth()
        for (offset in 1L..3L) {
            val m = now.minusMonths(offset)
            repo.saveTxn(
                Txn(
                    amountCents = 650000,
                    isExpense = false,
                    categoryId = 13,
                    accountId = 2,
                    merchant = "Salário",
                    date = Dates.epochFromDate(m.year, m.monthValue - 1, 5),
                    source = "exemplo"
                )
            )
            repo.saveTxn(
                Txn(
                    amountCents = 380000,
                    isExpense = true,
                    categoryId = 2,
                    accountId = 2,
                    merchant = "Mercado",
                    date = Dates.epochFromDate(m.year, m.monthValue - 1, 10),
                    source = "exemplo"
                )
            )
            repo.saveTxn(
                Txn(
                    amountCents = 120000,
                    isExpense = true,
                    categoryId = 4,
                    accountId = 2,
                    merchant = "Moradia",
                    date = Dates.epochFromDate(m.year, m.monthValue - 1, 12),
                    source = "exemplo"
                )
            )
        }
        val notification = "Compra aprovada no crédito de R$ 42,90 em Uber"
        val parsed = NotificationParser.parse("", notification)
        repo.saveSalaryConfig(
            SalaryConfig(
                grossCents = 600000,
                dependents = 1,
                firstPercent = 30,
                firstDay = 19,
                secondDay = 0,
                secondNextMonth = true,
                secondBusinessDay = true,
                secondBusinessDayNumber = 5,
                discounts = listOf(
                    PayrollDiscount("saude", "Plano de saúde", value = 30000),
                    PayrollDiscount("sindicato", "Sindicato", percent = true, value = 100)
                )
            ),
            Dates.currentMonth()
        )
        repo.addPending(
            Pending(
                packageName = "com.nu.production",
                appLabel = "Nubank",
                title = "Nubank",
                text = notification,
                postedAt = System.currentTimeMillis(),
                parsedAmount = parsed?.amountCents,
                parsedExpense = parsed?.isExpense,
                merchant = parsed?.merchant.orEmpty(),
                categoryName = parsed?.category,
                confidence = parsed?.confidence ?: 0f,
                sourceHash = repo.hashFor("com.nu.production", notification, System.currentTimeMillis())
            )
        )

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)

        capture(activity, File(outDir, "1-dashboard.png"))
        activity.findViewById<NestedScrollView>(R.id.scroll)?.scrollTo(0, 950)
        capture(activity, File(outDir, "1b-dashboard-scrolled.png"))
        activity.findViewById<NestedScrollView>(R.id.scroll)?.scrollTo(0, 1550)
        capture(activity, File(outDir, "1c-dashboard-evolution.png"))
        activity.findViewById<NestedScrollView>(R.id.scroll)?.scrollTo(0, 2050)
        capture(activity, File(outDir, "1d-dashboard-forecast.png"))
        activity.findViewById<NestedScrollView>(R.id.scroll)?.scrollTo(0, 0)

        nav.selectedItemId = R.id.nav_txns
        settle()
        capture(activity, File(outDir, "2-transactions.png"))
        nav.selectedItemId = R.id.nav_budgets
        settle()
        capture(activity, File(outDir, "3-budgets.png"))
        nav.selectedItemId = R.id.nav_salary
        settle()
        capture(activity, File(outDir, "4-salary.png"))
        activity.findViewById<NestedScrollView>(R.id.salaryScroll)?.scrollTo(0, 1300)
        capture(activity, File(outDir, "4b-salary-13.png"))
        activity.findViewById<NestedScrollView>(R.id.salaryScroll)?.scrollTo(0, 0)
        repo.saveVacation(Dates.currentMonth(), VacationConfig(days = 20, sellTenDays = true))
        nav.selectedItemId = R.id.nav_summary
        settle()
        nav.selectedItemId = R.id.nav_salary
        settle()
        capture(activity, File(outDir, "5-salary-vacation.png"))

        nav.selectedItemId = R.id.nav_settings
        settle()
        capture(activity, File(outDir, "6-settings.png"))

        val forecast = ForecastSheet()
        forecast.show(activity.supportFragmentManager, "forecast")
        settle()
        captureSheet(forecast.dialog?.window?.decorView, File(outDir, "12-forecast.png"))
        forecast.dismiss()
        settle()

        val salaryConfig = SalaryConfigSheet.new(Dates.key(Dates.currentMonth()))
        salaryConfig.show(activity.supportFragmentManager, "salary_config")
        settle()
        captureSheet(salaryConfig.dialog?.window?.decorView, File(outDir, "7-salary-config.png"))
        salaryConfig.dismiss()
        settle()

        val txn = repo.recent(1).first()
        val editor = TransactionEditorSheet.forTxn(txn.id)
        editor.show(activity.supportFragmentManager, "editor")
        settle()
        captureSheet(editor.dialog?.window?.decorView, File(outDir, "8-editor.png"))
        editor.dismiss()
        settle()

        val budget = BudgetEditorSheet.new(2L, Dates.key(Dates.currentMonth()), 90000L)
        budget.show(activity.supportFragmentManager, "budget")
        settle()
        captureSheet(budget.dialog?.window?.decorView, File(outDir, "9-budget-editor.png"))
        budget.dismiss()
        settle()

        val review = ReviewSheet()
        review.show(activity.supportFragmentManager, "review")
        settle()
        captureSheet(review.dialog?.window?.decorView, File(outDir, "10-review.png"))
        review.dismiss()
        settle()

        repo.wipe()
        repo.saveVacation(Dates.currentMonth(), VacationConfig())
        nav.selectedItemId = R.id.nav_summary
        settle()
        capture(activity, File(outDir, "11-empty.png"))
        controller.pause().stop().destroy()
    }

    private fun settle() {
        repeat(18) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(80)
        }
    }

    private fun capture(activity: MainActivity, file: File) {
        settle()
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        draw(decor, width, height, file)
    }

    private fun captureSheet(view: View?, file: File) {
        view ?: return
        val metrics = view.context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        draw(view, width, height, file)
    }

    private fun draw(view: View, width: Int, height: Int, file: File) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
