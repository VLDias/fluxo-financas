package app.fluxo.financas.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.util.dp
import kotlin.math.max

class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Long> = emptyList()
    private var labels: List<String> = emptyList()
    private var highlight = -1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_alt)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_soft)
        textAlign = Paint.Align.CENTER
        textSize = context.dp(10)
    }
    private val primary = ContextCompat.getColor(context, R.color.primary)
    private val labelSpace = context.dp(22)

    fun setData(newValues: List<Long>, newLabels: List<String>, highlightIndex: Int = newValues.lastIndex) {
        values = newValues
        labels = newLabels
        highlight = highlightIndex
        contentDescription = "Despesas dos últimos meses: " +
            newValues.mapIndexed { i, v -> "${newLabels.getOrElse(i) { "" }} ${Money.format(v)}" }.joinToString(", ")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val chartBottom = height - labelSpace
        val chartTop = context.dp(10)
        val chartHeight = chartBottom - chartTop
        if (chartHeight <= 0) return
        val peak = values.maxOrNull() ?: 0L
        if (peak <= 0L) {
            val paint = Paint(labelPaint).apply { textSize = context.dp(12) }
            canvas.drawText("Sem despesas nos últimos meses", width / 2f, height / 2f, paint)
            return
        }
        val maxValue = peak
        val slot = width.toFloat() / values.size
        val barWidth = max(context.dp(10), slot * 0.44f)
        val radius = context.dp(6)

        values.forEachIndexed { index, value ->
            val cx = slot * index + slot / 2f
            val left = cx - barWidth / 2f
            val right = cx + barWidth / 2f

            canvas.drawRoundRect(RectF(left, chartTop, right, chartBottom), radius, radius, trackPaint)

            if (value > 0) {
                val h = max(context.dp(4), chartHeight * (value.toFloat() / maxValue))
                val top = chartBottom - h
                barPaint.color = if (index == highlight) primary else (primary and 0x00FFFFFF) or 0x66000000
                canvas.drawRoundRect(RectF(left, top, right, chartBottom), radius, radius, barPaint)
            }
            val label = labels.getOrElse(index) { "" }
            canvas.drawText(label, cx, height - context.dp(6), labelPaint)
        }
    }
}
