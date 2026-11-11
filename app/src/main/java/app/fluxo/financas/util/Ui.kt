package app.fluxo.financas.util

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import app.fluxo.financas.R

fun Context.dp(value: Number): Float = value.toFloat() * resources.displayMetrics.density
fun Context.dpi(value: Number): Int = (value.toFloat() * resources.displayMetrics.density).toInt()

fun View.visibleIf(condition: Boolean) {
    visibility = if (condition) View.VISIBLE else View.GONE
}

fun applyBottomInset(view: View) {
    val basePadding = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bars.bottom)
        insets
    }
}

fun progressColors(context: Context, ratio: Float): Int {
    val color = when {
        ratio > 1f -> R.color.expense
        ratio >= 0.8f -> R.color.accent
        else -> R.color.primary
    }
    return ContextCompat.getColor(context, color)
}

fun LinearProgressIndicator.applyBudgetRatio(ratio: Float) {
    setIndicatorColor(progressColors(context, ratio))
    progress = (ratio.coerceIn(0f, 1f) * 100).toInt()
}
