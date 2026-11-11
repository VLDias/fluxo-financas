package app.fluxo.financas.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.fluxo.financas.R
import app.fluxo.financas.util.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Long> = emptyList()
    private var labels: List<String> = emptyList()

    private val primary = ContextCompat.getColor(context, R.color.primary)
    private val negative = ContextCompat.getColor(context, R.color.expense)
    private val divider = ContextCompat.getColor(context, R.color.divider)
    private val inkSoft = ContextCompat.getColor(context, R.color.ink_soft)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1)
        color = divider
        pathEffect = DashPathEffect(floatArrayOf(context.dp(4), context.dp(4)), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkSoft
        textAlign = Paint.Align.CENTER
        textSize = context.dp(9)
    }

    fun setData(newValues: List<Long>, newLabels: List<String>) {
        values = newValues
        labels = newLabels
        contentDescription = newLabels.zip(newValues).joinToString(", ") { "${it.first}: ${it.second}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) {
            canvas.drawText("sem dados", width / 2f, height / 2f, labelPaint.apply { textAlign = Paint.Align.CENTER })
            return
        }
        val left = context.dp(6)
        val right = width - context.dp(6)
        val top = context.dp(10)
        val bottom = height - context.dp(20)
        if (right <= left || bottom <= top) return

        val minValue = min(0L, values.minOrNull() ?: 0L)
        val maxValue = max(0L, values.maxOrNull() ?: 0L)
        val span = (maxValue - minValue).coerceAtLeast(1L)
        fun y(value: Long): Float = bottom - ((value - minValue).toFloat() / span) * (bottom - top)
        fun x(index: Int): Float =
            if (values.size == 1) (left + right) / 2f
            else left + (right - left) * index / (values.size - 1).toFloat()

        val zeroY = y(0)
        canvas.drawLine(left, zeroY, right, zeroY, zeroPaint)

        val line = Path()
        values.forEachIndexed { index, value ->
            val px = x(index)
            val py = y(value)
            if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        val fill = Path(line).apply {
            lineTo(x(values.lastIndex), zeroY)
            lineTo(x(0), zeroY)
            close()
        }
        val baseColor = if (values.last() < 0) negative else primary
        fillPaint.shader = LinearGradient(
            0f, top, 0f, zeroY.coerceAtLeast(top + 1f),
            (baseColor and 0x00FFFFFF) or 0x33000000,
            (baseColor and 0x00FFFFFF),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, fillPaint)
        linePaint.color = baseColor
        canvas.drawPath(line, linePaint)

        val step = max(1, ceil(values.size / 6.0).toInt())
        values.forEachIndexed { index, value ->
            val px = x(index)
            if (index == values.lastIndex || index % step == 0) {
                dotPaint.color = if (value < 0) negative else primary
                canvas.drawCircle(px, y(value), context.dp(if (index == values.lastIndex) 3.4f else 2.2f), dotPaint)
            }
        }
        val indices = (values.indices.filter { it % step == 0 && values.lastIndex - it >= step } + 0 + values.lastIndex)
            .distinct()
            .sorted()
        indices.forEach { index ->
            val label = labels.getOrElse(index) { "" }
            if (label.isBlank()) return@forEach
            val tx = when (index) {
                0 -> left
                values.lastIndex -> right
                else -> x(index)
            }
            labelPaint.textAlign = when (index) {
                0 -> Paint.Align.LEFT
                values.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label, tx, height - context.dp(4), labelPaint)
        }
        labelPaint.textAlign = Paint.Align.CENTER
    }
}
