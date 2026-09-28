package com.example.autocut

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class CutDensityPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var cutsPerSecond = AppSettings.DEFAULT_CUTS_PER_SECOND

    private val radius = dp(12f)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_strip)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_stroke)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cut_mark)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        alpha = 235
    }

    fun setCutsPerSecond(cutsPerSecond: Int) {
        val clamped = cutsPerSecond.coerceAtLeast(1)
        if (this.cutsPerSecond != clamped) {
            this.cutsPerSecond = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = borderPaint.strokeWidth / 2f
        val bounds = RectF(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(bounds, radius, radius, bgPaint)
        canvas.drawRoundRect(bounds, radius, radius, borderPaint)

        val pad = dp(7f)
        val left = bounds.left + pad
        val right = bounds.right - pad
        val top = bounds.top + dp(9f)
        val bottom = bounds.bottom - dp(9f)
        val step = (right - left) / cutsPerSecond
        for (index in 1..cutsPerSecond) {
            val x = left + step * index
            canvas.drawLine(x, top, x, bottom, markPaint)
        }
    }

    private fun dp(value: Float): Float = value * density
}
