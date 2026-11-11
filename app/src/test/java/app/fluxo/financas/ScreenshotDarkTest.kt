package app.fluxo.financas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.fluxo.financas.data.Repo
import app.fluxo.financas.ui.MainActivity
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi", application = FluxoApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotDarkTest {

    @Test
    fun captureDark(): Unit = runBlocking {
        Assume.assumeTrue(
            "Defina FLUXO_SCREENSHOTS=1 para gerar as capturas",
            System.getenv("FLUXO_SCREENSHOTS") == "1"
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = Repo.get(context)
        repo.wipe()
        repo.seedDemo()
        repo.saveSalaryConfig(
            app.fluxo.financas.salary.SalaryConfig(
                grossCents = 600000,
                dependents = 1,
                discounts = listOf(
                    app.fluxo.financas.salary.PayrollDiscount("saude", "Plano de saúde", value = 30000)
                )
            ),
            app.fluxo.financas.util.Dates.currentMonth()
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val outDir = File(
            System.getenv("FLUXO_SCREENSHOTS_DIR") ?: "build/screenshots"
        ).apply { mkdirs() }
        capture(activity, File(outDir, "9-dark-dashboard.png"))
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_salary
        settle()
        capture(activity, File(outDir, "10-dark-salary.png"))
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_budgets
        settle()
        capture(activity, File(outDir, "11-dark-budgets.png"))
        controller.pause().stop().destroy()
    }

    private fun settle() {
        repeat(16) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(80)
        }
    }

    private fun capture(activity: MainActivity, file: File) {
        settle()
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
