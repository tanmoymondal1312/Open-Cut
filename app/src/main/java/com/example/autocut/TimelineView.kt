package com.example.autocut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Frame(val timeMs: Long, val bitmap: Bitmap)

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val frames = ArrayList<Frame>()

    private var durationMs = 0L
    private var pxPerMs = dp(0.12f)
    private var centerMs = 0L

    private val floorZoom = dp(0.004f)
    private val ceilZoom = dp(1.2f)

    private val corner = dp(12f)
    private val pad = dp(6f)
    private val pillRow = dp(20f)
    private val rulerRow = dp(22f)
    private val tileWidth = dp(64f)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scroller = OverScroller(context)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (durationMs <= 0L || width <= 0) return false
                val focusX = detector.focusX
                val timeAtFocus = timeAtX(focusX)
                pxPerMs = (pxPerMs * detector.scaleFactor).coerceIn(minZoom(), maxZoom())
                centerMs = (timeAtFocus - (focusX - width / 2f) / pxPerMs).toLong()
                clampCenter()
                invalidate()
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean =
                durationMs > 0L && width > 0
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (durationMs <= 0L || scaleDetector.isInProgress) return false
                centerMs = (centerMs + distanceX / pxPerMs).toLong()
                clampCenter()
                invalidate()
                return true
            }

            override fun onFling(
                first: MotionEvent?,
                current: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (durationMs <= 0L || scaleDetector.isInProgress || width <= 0) return false
                scroller.fling(
                    centerMs.toInt(),
                    0,
                    (-velocityX / pxPerMs).toInt(),
                    0,
                    0,
                    durationMs.toInt(),
                    0,
                    0
                )
                postInvalidateOnAnimation()
                return true
            }
        }
    )

    init {
        bgPaint.color = ContextCompat.getColor(context, R.color.timeline_bg)
        stripPaint.color = ContextCompat.getColor(context, R.color.timeline_strip)
        borderPaint.color = ContextCompat.getColor(context, R.color.timeline_stroke)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = dp(1f)
        separatorPaint.color = ContextCompat.getColor(context, R.color.timeline_separator)
        separatorPaint.strokeWidth = dp(1f)
        placeholderPaint.color = ContextCompat.getColor(context, R.color.timeline_placeholder)
        tickPaint.color = ContextCompat.getColor(context, R.color.timeline_tick)
        tickPaint.strokeWidth = dp(1.5f)
        labelPaint.color = ContextCompat.getColor(context, R.color.timeline_label)
        labelPaint.textSize = dp(9f)
        labelPaint.textAlign = Paint.Align.CENTER
        playheadPaint.color = ContextCompat.getColor(context, R.color.timeline_playhead)
        playheadPaint.strokeWidth = dp(2.5f)
        playheadGlowPaint.color = ContextCompat.getColor(context, R.color.timeline_playhead)
        playheadGlowPaint.strokeWidth = dp(9f)
        playheadGlowPaint.alpha = 45
        pillPaint.color = ContextCompat.getColor(context, R.color.timeline_pill)
        pillTextPaint.color = ContextCompat.getColor(context, R.color.timeline_playhead)
        pillTextPaint.textSize = dp(10f)
        pillTextPaint.textAlign = Paint.Align.CENTER
        pillTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        handlePaint.color = ContextCompat.getColor(context, R.color.timeline_playhead)
    }

    fun setDuration(ms: Long) {
        durationMs = ms
        centerMs = 0L
        pxPerMs = pxPerMs.coerceIn(minZoom(), maxZoom())
        clampCenter()
        invalidate()
    }

    fun addFrame(timeMs: Long, bitmap: Bitmap) {
        var index = frames.size
        for (i in frames.indices) {
            if (frames[i].timeMs > timeMs) {
                index = i
                break
            }
        }
        frames.add(index, Frame(timeMs, bitmap))
        invalidate()
    }

    fun clearFrames() {
        frames.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pxPerMs = pxPerMs.coerceIn(minZoom(), maxZoom())
        clampCenter()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            centerMs = scroller.currX.toLong()
            clampCenter()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRoundRect(RectF(0f, 0f, w, h), corner, corner, bgPaint)

        canvas.save()
        canvas.clipRect(pad, pad, w - pad, h - pad)

        val stripRect = RectF(pad, pad + pillRow + rulerRow, w - pad, h - pad)
        canvas.drawRoundRect(stripRect, dp(6f), dp(6f), stripPaint)
        drawFrames(canvas, stripRect)
        drawRuler(canvas, w, stripRect.top)
        drawPlayhead(canvas, w, h)

        canvas.restore()

        borderPaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(
            RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)),
            corner,
            corner,
            borderPaint
        )
    }

    private fun drawFrames(canvas: Canvas, stripRect: RectF) {
        if (durationMs <= 0L || width <= 0 || pxPerMs <= 0f) return
        val chunkMs = tileWidth / pxPerMs
        if (chunkMs <= 0f) return

        val first = floor(timeAtX(stripRect.left) / chunkMs).toLong()
        val last = ceil(timeAtX(stripRect.right) / chunkMs).toLong()

        canvas.save()
        canvas.clipRect(stripRect)

        val rect = RectF()
        var index = first
        while (index <= last) {
            val timeMs = (index * chunkMs).toLong().coerceIn(0L, durationMs)
            val x = xForTime(timeMs)
            rect.set(x, stripRect.top, x + tileWidth, stripRect.bottom)

            val frame = frameAt(timeMs)
            if (frame != null) {
                drawCover(canvas, frame.bitmap, rect)
            } else {
                canvas.drawRect(rect, placeholderPaint)
            }

            if (index > first) {
                canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, separatorPaint)
            }
            index++
        }
        canvas.restore()
    }

    private fun drawRuler(canvas: Canvas, w: Float, stripTop: Float) {
        if (durationMs <= 0L || width <= 0 || pxPerMs <= 0f) return
        val step = rulerStepMs()
        val baseline = stripTop - dp(3f)
        val labelY = baseline - dp(7f)

        val leftTime = timeAtX(pad)
        val rightTime = timeAtX(w - pad)
        var t = (floor(leftTime / step.toDouble()) * step).toLong()
        while (t <= rightTime) {
            if (t >= 0L && t <= durationMs) {
                val x = xForTime(t)
                canvas.drawLine(x, baseline - dp(5f), x, baseline, tickPaint)
                canvas.drawText(formatTime(t), x, labelY, labelPaint)
            }
            t += step
        }
    }

    private fun drawPlayhead(canvas: Canvas, w: Float, h: Float) {
        if (durationMs <= 0L || width <= 0) return
        val x = w / 2f
        val top = pad + pillRow / 2f
        val bottom = h - pad - dp(1f)

        canvas.drawLine(x, top, x, bottom, playheadGlowPaint)
        canvas.drawLine(x, top, x, bottom, playheadPaint)

        val handleW = dp(24f)
        val handleH = dp(7f)
        canvas.drawRoundRect(
            RectF(x - handleW / 2f, pad + dp(2f), x + handleW / 2f, pad + dp(2f) + handleH),
            handleH / 2f,
            handleH / 2f,
            handlePaint
        )

        val text = formatTime(centerMs)
        val pillW = max(pillTextPaint.measureText(text) + dp(20f), dp(52f))
        val pillH = dp(17f)
        val pillTop = pad + dp(9f)
        canvas.drawRoundRect(
            RectF(x - pillW / 2f, pillTop, x + pillW / 2f, pillTop + pillH),
            pillH / 2f,
            pillH / 2f,
            pillPaint
        )
        canvas.drawText(
            text,
            x,
            pillTop + pillH / 2f + pillTextPaint.textSize * 0.35f,
            pillTextPaint
        )
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, rect: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dx = rect.left + (rect.width() - dw) / 2f
        val dy = rect.top + (rect.height() - dh) / 2f
        canvas.drawBitmap(bitmap, null, RectF(dx, dy, dx + dw, dy + dh), bitmapPaint)
    }

    private fun frameAt(timeMs: Long): Frame? {
        if (frames.isEmpty()) return null
        var best: Frame? = null
        var bestDelta = Long.MAX_VALUE
        for (frame in frames) {
            val delta = abs(frame.timeMs - timeMs)
            if (delta < bestDelta) {
                bestDelta = delta
                best = frame
            }
        }
        return best
    }

    private fun timeAtX(x: Float): Long = (centerMs + (x - width / 2f) / pxPerMs).toLong()

    private fun xForTime(timeMs: Float): Float = width / 2f + (timeMs - centerMs) * pxPerMs

    private fun xForTime(timeMs: Long): Float = xForTime(timeMs.toFloat())

    private fun minZoom(): Float {
        if (durationMs <= 0L || width <= 0) return floorZoom
        return max(floorZoom, (width * 0.85f) / durationMs)
    }

    private fun maxZoom(): Float = ceilZoom

    private fun clampCenter() {
        if (durationMs <= 0L) {
            centerMs = 0L
            return
        }
        if (width <= 0 || pxPerMs <= 0f) {
            centerMs = centerMs.coerceIn(0L, durationMs)
            return
        }
        val halfVisible = (width / 2f) / pxPerMs
        val minCenter = min(durationMs / 2f, halfVisible).toLong().coerceAtLeast(0L)
        val maxCenter =
            max(durationMs / 2f, durationMs - halfVisible).toLong().coerceAtMost(durationMs)
        centerMs = if (minCenter > maxCenter) minCenter else centerMs.coerceIn(minCenter, maxCenter)
    }

    private fun rulerStepMs(): Long {
        val candidates = longArrayOf(
            100, 200, 500, 1000, 2000, 5000, 10000, 15000,
            30000, 60000, 120000, 300000, 600000
        )
        for (candidate in candidates) {
            if (candidate * pxPerMs >= dp(76f)) return candidate
        }
        return candidates[candidates.size - 1]
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
