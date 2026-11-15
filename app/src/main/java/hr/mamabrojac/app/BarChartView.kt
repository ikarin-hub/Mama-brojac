package hr.mamabrojac.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Simple rounded bar chart; the last bar (current period) is highlighted. */
class BarChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var labels: List<String> = emptyList()
    private var values: List<Int> = emptyList()
    private var labelEvery = 1
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val accent = context.getColor(R.color.accent)
    private val faint = context.getColor(R.color.bar_faint)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * sp
        color = context.getColor(R.color.muted)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * sp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = context.getColor(R.color.ink)
    }

    fun setData(newLabels: List<String>, newValues: List<Int>, showEveryNthLabel: Int = 1) {
        labels = newLabels
        values = newValues
        labelEvery = showEveryNthLabel.coerceAtLeast(1)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0 || width == 0) return
        val labelH = 22 * dp
        val valueH = 18 * dp
        val top = valueH
        val bottom = height - labelH
        val slot = width.toFloat() / n
        val bw = slot * 0.58f
        val radius = minOf(bw / 2f, 8 * dp)
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)

        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            val v = values[i]
            val h = maxOf((bottom - top) * v / max * progress, 3 * dp)
            val barTop = bottom - h
            barPaint.color = if (i == n - 1) accent else faint
            canvas.drawRoundRect(cx - bw / 2f, barTop, cx + bw / 2f, bottom, radius, radius, barPaint)

            val showValue = v > 0 && (n <= 14 || i == n - 1 || v == max)
            if (showValue && progress > 0.9f) {
                canvas.drawText(v.toString(), cx, barTop - 5 * dp, valuePaint)
            }
            if (i % labelEvery == (n - 1) % labelEvery) {
                canvas.drawText(labels.getOrElse(i) { "" }, cx, height - 6 * dp, labelPaint)
            }
        }
    }
}
