package app.fluxo.financas.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.util.dp
import kotlin.math.max
import kotlin.math.min

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val value: Long, val color: Int, val label: String = "")

    private var slices: List<Slice> = emptyList()
    private var totalCents = 0L
    private var animProgress = 0f
    private var animator: ValueAnimator? = null

    private val ringWidth = context.dp(20)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        textAlign = Paint.Align.CENTER
        textSize = context.dp(21)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_soft)
        textAlign = Paint.Align.CENTER
        textSize = context.dp(11)
    }

    fun setData(newSlices: List<Slice>, total: Long) {
        slices = newSlices.filter { it.value > 0 }.sortedByDescending { it.value }
        totalCents = total
        contentDescription = "Gastos por categoria. Total ${Money.format(total)}."
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f - context.dp(2)
        val radius = min(width / 2f, height / 2f) - ringWidth / 2f - context.dp(4)
        if (radius <= 0) return
        val cx = width / 2f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        if (totalCents <= 0 || slices.isEmpty()) {
            ringPaint.color = ContextCompat.getColor(context, R.color.divider)
            canvas.drawArc(rect, 0f, 360f, false, ringPaint)
            canvas.drawText(Money.format(0L), cx, cy + context.dp(4), titlePaint)
            canvas.drawText("sem gastos", cx, cy + context.dp(22), captionPaint)
            return
        }

        var start = -90f
        val gap = if (slices.size > 1) 3.5f else 0f
        for (slice in slices) {
            val sweep = 360f * (slice.value.toFloat() / totalCents) * animProgress
            ringPaint.color = slice.color
            canvas.drawArc(rect, start + gap / 2f, max(0f, sweep - gap), false, ringPaint)
            start += sweep
        }
        canvas.drawText(Money.format(totalCents), cx, cy + context.dp(4), titlePaint)
        canvas.drawText("em gastos", cx, cy + context.dp(22), captionPaint)
    }
}
