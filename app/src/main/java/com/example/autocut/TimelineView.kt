package com.example.autocut

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.core.content.ContextCompat
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Frame(val bitmap: Bitmap) {
        var lastAccess: Long = 0L
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private var durationMs = 0L
    private var sourceDurationMs = 0L
    private var pxPerMs = dp(0.12f)
    private var centerMs = 0L
    private var labelStepMs = 1000L

    private val tileWidth = dp(48f)

    private val corner = dp(12f)
    private val pad = dp(6f)
    private val pillRow = dp(20f)
    private val rulerRow = dp(22f)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipSplitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipActivePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipDeletedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cutMarks = LongArray(0)
    private var cutAppliedMs = Long.MIN_VALUE

    private class Segment(
        val srcStart: Long,
        val srcEnd: Long,
        val renderStart: Long,
        val renderEnd: Long,
        val clipIndex: Int,
        val vanish: Boolean
    )

    private var clipRanges: List<ClipRange> = emptyList()
    private var activeClipIndex = -1
    private var clipsActive = false
    private var segments: List<Segment> = emptyList()
    private var vanishing: ClipRange? = null
    private var vanishProgress = 0f
    private var vanishAnimator: ValueAnimator? = null
    private var lastNotifiedFocus = Long.MIN_VALUE
    var onFocusChanged: (() -> Unit)? = null
    var onClipsSettled: (() -> Unit)? = null
    val isRemovalAnimating: Boolean get() = vanishAnimator != null

    private val scroller = OverScroller(context)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (durationMs <= 0L || width <= 0) return false
                val focusX = detector.focusX
                val timeAtFocus = timeAtX(focusX)
                pxPerMs = (pxPerMs * detector.scaleFactor).coerceIn(loZoom(), hiZoom())
                centerMs = (timeAtFocus - (focusX - width / 2f) / pxPerMs).toLong()
                clampCenter()
                refresh()
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
                refresh()
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

    private val frameLock = Any()
    private val frames = TreeMap<Long, Frame>()
    private var frameBytes = 0
    private val maxFrameBytes = 12 * 1024 * 1024

    private val queue = LinkedHashSet<Long>()
    private var workerRunning = false
    private var inFlight = -1L

    private val retrieverLock = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var frameFailLogs = 0
    private val executor = Executors.newSingleThreadExecutor()
    private var released = false

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
        minorTickPaint.color = ContextCompat.getColor(context, R.color.timeline_tick)
        minorTickPaint.strokeWidth = dp(1f)
        minorTickPaint.alpha = 140
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
        cutMarkPaint.color = ContextCompat.getColor(context, R.color.cut_mark)
        cutMarkPaint.strokeWidth = dp(1.5f)
        clipSplitPaint.color = ContextCompat.getColor(context, R.color.clip_split)
        clipSplitPaint.strokeWidth = dp(2f)
        clipActivePaint.color = ContextCompat.getColor(context, R.color.clip_active)
        clipDeletedPaint.color = ContextCompat.getColor(context, R.color.clip_deleted)
        clipBorderPaint.color = ContextCompat.getColor(context, R.color.clip_split)
        clipBorderPaint.style = Paint.Style.STROKE
        clipBorderPaint.strokeWidth = dp(1.5f)
    }

    fun setClips(ranges: List<ClipRange>, activeIndex: Int) {
        cancelRemoval()
        clipRanges = ranges
        activeClipIndex = activeIndex
        clipsActive = ranges.isNotEmpty() &&
            !(ranges.size == 1 && ranges[0].startMs == 0L && ranges[0].endMs >= sourceDurationMs)
        rebuildLayout()
        invalidate()
    }

    fun clearClips() {
        cancelRemoval()
        clipRanges = emptyList()
        activeClipIndex = -1
        clipsActive = false
        rebuildLayout()
        invalidate()
    }

    fun animateRemoval(vanished: ClipRange, ranges: List<ClipRange>, activeIndex: Int = -1) {
        cancelRemoval()
        clipRanges = ranges
        activeClipIndex = activeIndex
        clipsActive = ranges.isNotEmpty() &&
            !(ranges.size == 1 && ranges[0].startMs == 0L && ranges[0].endMs >= sourceDurationMs)
        vanishing = vanished
        vanishProgress = 0f
        rebuildLayout()
        clampCenter()
        refresh()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REMOVE_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                vanishProgress = it.animatedValue as Float
                rebuildLayout()
                clampCenter()
                refresh()
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (vanishAnimator !== animation) return
                vanishAnimator = null
                vanishing = null
                vanishProgress = 0f
                rebuildLayout()
                clampCenter()
                refresh()
                onClipsSettled?.invoke()
            }
        })
        vanishAnimator = anim
        anim.start()
    }

    private fun cancelRemoval() {
        val anim = vanishAnimator ?: return
        vanishAnimator = null
        vanishing = null
        vanishProgress = 0f
        anim.cancel()
    }

    private fun rebuildLayout() {
        if (clipRanges.isEmpty() && vanishing == null) {
            segments = if (sourceDurationMs > 0L) {
                listOf(Segment(0L, sourceDurationMs, 0L, sourceDurationMs, -1, false))
            } else {
                emptyList()
            }
            durationMs = sourceDurationMs
            return
        }
        val items = ArrayList<Segment>()
        var render = 0L
        var vanishInserted = vanishing == null
        for ((index, clip) in clipRanges.withIndex()) {
            val vanished = vanishing
            if (!vanishInserted && vanished != null && clip.startMs >= vanished.endMs) {
                val width = vanishWidth(vanished)
                items.add(Segment(vanished.startMs, vanished.endMs, render, render + width, -1, true))
                render += width
                vanishInserted = true
            }
            items.add(
                Segment(
                    clip.startMs,
                    clip.endMs,
                    render,
                    render + (clip.endMs - clip.startMs),
                    index,
                    false
                )
            )
            render += clip.endMs - clip.startMs
        }
        val trailing = vanishing
        if (!vanishInserted && trailing != null) {
            val width = vanishWidth(trailing)
            items.add(Segment(trailing.startMs, trailing.endMs, render, render + width, -1, true))
            render += width
        }
        segments = items
        durationMs = render
    }

    private fun vanishWidth(vanished: ClipRange): Long =
        ((vanished.endMs - vanished.startMs) * (1f - vanishProgress)).toLong().coerceAtLeast(0L)

    private fun toSource(renderedMs: Long): Long {
        if (segments.isEmpty()) return renderedMs.coerceAtLeast(0L)
        val time = renderedMs.coerceIn(0L, durationMs)
        for (segment in segments) {
            if (segment.renderEnd > segment.renderStart && time < segment.renderEnd) {
                val span = segment.renderEnd - segment.renderStart
                val fraction = (time - segment.renderStart).toFloat() / span
                val sourceSpan = segment.srcEnd - segment.srcStart
                return segment.srcStart + (sourceSpan * fraction).toLong().coerceIn(0L, sourceSpan)
            }
        }
        return segments.last().srcEnd
    }

    private fun toRendered(sourceMs: Long): Long? {
        for (segment in segments) {
            val sourceSpan = segment.srcEnd - segment.srcStart
            if (sourceSpan > 0L && sourceMs >= segment.srcStart && sourceMs < segment.srcEnd) {
                val fraction = (sourceMs - segment.srcStart).toFloat() / sourceSpan
                return segment.renderStart +
                    ((segment.renderEnd - segment.renderStart) * fraction).toLong()
            }
        }
        return null
    }

    fun focusMs(): Long = centerMs

    fun focusSourceMs(): Long = toSource(centerMs)

    fun setCutMarks(timesMs: LongArray, appliedUpToMs: Long) {
        cutMarks = timesMs
        cutAppliedMs = appliedUpToMs
        invalidate()
    }

    fun setCutProgress(appliedUpToMs: Long) {
        cutAppliedMs = appliedUpToMs
        invalidate()
    }

    fun clearCutMarks() {
        cutMarks = LongArray(0)
        cutAppliedMs = Long.MIN_VALUE
        invalidate()
    }

    fun setVideoUri(uri: Uri) {
        cancelRemoval()
        sourceDurationMs = 0L
        rebuildLayout()
        synchronized(frameLock) {
            queue.clear()
            frames.clear()
            frameBytes = 0
        }
        executor.execute {
            if (released) return@execute
            var fresh: MediaMetadataRetriever? = null
            try {
                fresh = MediaMetadataRetriever()
                fresh.setDataSource(context, uri)
                val duration = fresh
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                synchronized(retrieverLock) {
                    try {
                        retriever?.release()
                    } catch (ignored: Exception) {
                    }
                    retriever = fresh
                    fresh = null
                }
                post {
                    if (!released) setDuration(duration)
                }
            } catch (error: Exception) {
                try {
                    fresh?.release()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    fun setDuration(ms: Long) {
        cancelRemoval()
        sourceDurationMs = ms
        rebuildLayout()
        centerMs = 0L
        pxPerMs = pxPerMs.coerceIn(loZoom(), hiZoom())
        clampCenter()
        refresh()
    }

    fun release() {
        cancelRemoval()
        released = true
        executor.shutdownNow()
        synchronized(retrieverLock) {
            try {
                retriever?.release()
            } catch (ignored: Exception) {
            }
            retriever = null
        }
        synchronized(frameLock) {
            queue.clear()
            frames.clear()
            frameBytes = 0
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pxPerMs = pxPerMs.coerceIn(loZoom(), hiZoom())
        clampCenter()
        scheduleFrames()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            centerMs = scroller.currX.toLong()
            clampCenter()
            scheduleFrames()
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

        val stripRect = stripRect()
        canvas.drawRoundRect(stripRect, dp(6f), dp(6f), stripPaint)
        drawFrames(canvas, stripRect)
        drawClipOverlay(canvas, stripRect)
        drawCutMarks(canvas, stripRect)
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

    private fun stripRect(): RectF =
        RectF(pad, pad + pillRow + rulerRow, width - pad, height - pad)

    private fun stripHeight(): Float = height - 2f * pad - pillRow - rulerRow

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

            val bitmap = frameBitmapAt(toSource(timeMs), chunkMs)
            if (bitmap != null) {
                drawCover(canvas, bitmap, rect)
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

    private fun drawClipOverlay(canvas: Canvas, stripRect: RectF) {
        if (vanishing == null && !clipsActive) return
        if (durationMs <= 0L || width <= 0 || pxPerMs <= 0f) return
        canvas.save()
        canvas.clipRect(stripRect)

        val rect = RectF()
        for (segment in segments) {
            if (segment.renderStart > 0L) {
                drawClipEdge(canvas, stripRect, segment.renderStart)
            }
            if (segment.renderEnd <= segment.renderStart) continue
            if (segment.vanish) {
                rect.set(
                    xForTime(segment.renderStart),
                    stripRect.top,
                    xForTime(segment.renderEnd),
                    stripRect.bottom
                )
                canvas.drawRect(rect, clipDeletedPaint)
                canvas.drawRect(rect, clipBorderPaint)
                continue
            }
            if (segment.clipIndex == activeClipIndex) {
                rect.set(
                    xForTime(segment.renderStart),
                    stripRect.top,
                    xForTime(segment.renderEnd),
                    stripRect.bottom
                )
                canvas.drawRect(rect, clipActivePaint)
                canvas.drawRect(rect, clipBorderPaint)
            }
        }
        canvas.restore()
    }

    private fun drawClipEdge(canvas: Canvas, stripRect: RectF, timeMs: Long) {
        val x = xForTime(timeMs)
        if (x < stripRect.left || x > stripRect.right) return
        canvas.drawLine(x, stripRect.top, x, stripRect.bottom, clipSplitPaint)
    }

    private fun drawCutMarks(canvas: Canvas, stripRect: RectF) {
        if (cutMarks.isEmpty() || durationMs <= 0L || width <= 0 || pxPerMs <= 0f) return
        canvas.save()
        canvas.clipRect(stripRect)
        for (cut in cutMarks) {
            if (cut > cutAppliedMs) break
            val rendered = toRendered(cut) ?: continue
            val x = xForTime(rendered)
            if (x < stripRect.left || x > stripRect.right) continue
            canvas.drawLine(x, stripRect.top, x, stripRect.bottom, cutMarkPaint)
        }
        canvas.restore()
    }

    private fun drawRuler(canvas: Canvas, w: Float, stripTop: Float) {
        if (durationMs <= 0L || width <= 0 || pxPerMs <= 0f) return
        val step = rulerStepMs()
        labelStepMs = step
        val baseline = stripTop - dp(3f)
        val labelY = baseline - dp(7f)
        val spacing = step * pxPerMs

        val leftTime = timeAtX(pad)
        val rightTime = timeAtX(w - pad)
        var t = (floor(leftTime / step.toDouble()) * step).toLong()
        while (t <= rightTime) {
            if (t >= 0L && t <= durationMs) {
                val x = xForTime(t)
                canvas.drawLine(x, baseline - dp(5f), x, baseline, tickPaint)
                canvas.drawText(formatTime(t), x, labelY, labelPaint)
                if (spacing >= dp(80f)) {
                    val minor = t + step / 2
                    if (minor in 0..durationMs) {
                        val mx = xForTime(minor)
                        canvas.drawLine(mx, baseline - dp(3f), mx, baseline, minorTickPaint)
                    }
                }
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

    private fun refresh() {
        if (centerMs != lastNotifiedFocus) {
            lastNotifiedFocus = centerMs
            onFocusChanged?.invoke()
        }
        invalidate()
        scheduleFrames()
    }

    private fun quantize(timeMs: Long): Long = (timeMs / GRID_MS) * GRID_MS

    private fun visibleFrameTimes(): List<Long> {
        if (durationMs <= 0L || width <= 0 || pxPerMs <= 0f) return emptyList()
        val chunkMs = tileWidth / pxPerMs
        if (chunkMs <= 0f) return emptyList()
        val first = floor(timeAtX(pad) / chunkMs).toLong()
        val last = ceil(timeAtX(width - pad) / chunkMs).toLong()
        val times = ArrayList<Long>()
        var index = first
        while (index <= last) {
            val rendered = (index * chunkMs).toLong().coerceIn(0L, durationMs)
            val key = quantize(toSource(rendered))
            if (key in 0..sourceDurationMs && !times.contains(key)) times.add(key)
            index++
        }
        return times
    }

    private fun scheduleFrames() {
        if (released) return
        val wanted = visibleFrameTimes()
        var startWorker = false
        synchronized(frameLock) {
            queue.clear()
            for (time in wanted) {
                if (time != inFlight && frames[time] == null) queue.add(time)
            }
            if (!workerRunning && queue.isNotEmpty()) {
                workerRunning = true
                startWorker = true
            }
        }
        if (startWorker) executor.execute { workerLoop() }
    }

    private fun workerLoop() {
        while (!released) {
            val time = synchronized(frameLock) {
                val iterator = queue.iterator()
                if (!iterator.hasNext()) {
                    workerRunning = false
                    null
                } else {
                    val next = iterator.next()
                    iterator.remove()
                    inFlight = next
                    next
                }
            } ?: break

            val bitmap = decodeFrame(time)
            synchronized(frameLock) {
                inFlight = -1L
            }
            if (bitmap != null && !released) {
                putFrame(time, bitmap)
                postInvalidate()
            }
        }
        synchronized(frameLock) {
            workerRunning = false
        }
    }

    private fun decodeFrame(timeMs: Long): Bitmap? {
        synchronized(retrieverLock) {
            if (released) return null
            val source = try {
                retriever?.getFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
            } catch (error: Exception) {
                if (frameFailLogs < 5) {
                    frameFailLogs++
                    Log.d("AutoCut", "frame throw t=$timeMs err=$error")
                }
                null
            }
            if (source == null) {
                if (frameFailLogs < 5) {
                    frameFailLogs++
                    Log.d(
                        "AutoCut",
                        "frame null t=$timeMs hasRetriever=${retriever != null} " +
                            "durMs=$sourceDurationMs"
                    )
                }
                return null
            }
            return cropToTile(source)
        }
    }

    private fun cropToTile(source: Bitmap): Bitmap {
        val tileHeight = stripHeight()
        if (tileHeight <= 0f || source.width <= 0 || source.height <= 0) return source
        val targetHeight = max(64, (tileHeight / 2f).toInt())
        val targetWidth = max(1, (targetHeight * (tileWidth / tileHeight)).toInt())

        val scale = max(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height
        )
        val cropWidth = min(source.width, max(1, (targetWidth / scale).toInt()))
        val cropHeight = min(source.height, max(1, (targetHeight / scale).toInt()))
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2

        val cropped = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        val output = if (cropped.width == targetWidth && cropped.height == targetHeight) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        }
        if (cropped !== source && cropped !== output) cropped.recycle()
        if (source !== output) source.recycle()
        return output
    }

    private fun putFrame(key: Long, bitmap: Bitmap) {
        val size = bitmap.allocationByteCount
        synchronized(frameLock) {
            frames[key]?.let { frameBytes -= it.bitmap.allocationByteCount }
            val frame = Frame(bitmap)
            frame.lastAccess = SystemClock.elapsedRealtime()
            frames[key] = frame
            frameBytes += size
            evictFrames()
        }
    }

    private fun evictFrames() {
        while (frameBytes > maxFrameBytes && frames.size > 1) {
            var victim: MutableMap.MutableEntry<Long, Frame>? = null
            for (entry in frames.entries) {
                if (victim == null || entry.value.lastAccess < victim.value.lastAccess) {
                    victim = entry
                }
            }
            val target = victim ?: break
            frameBytes -= target.value.bitmap.allocationByteCount
            frames.remove(target.key)
        }
    }

    private fun frameBitmapAt(timeMs: Long, chunkMs: Float): Bitmap? {
        val key = quantize(timeMs)
        val tolerance = max(chunkMs.toLong(), GRID_MS)
        val now = SystemClock.elapsedRealtime()
        synchronized(frameLock) {
            frames[key]?.let {
                it.lastAccess = now
                return it.bitmap
            }
            val ceiling = frames.ceilingEntry(key)
            val floorEntry = frames.floorEntry(key)
            var candidate: Frame? = null
            var distance = Long.MAX_VALUE
            if (ceiling != null && ceiling.key - key < distance) {
                distance = ceiling.key - key
                candidate = ceiling.value
            }
            if (floorEntry != null && key - floorEntry.key < distance) {
                distance = key - floorEntry.key
                candidate = floorEntry.value
            }
            if (candidate == null || distance > tolerance) return null
            candidate.lastAccess = now
            return candidate.bitmap
        }
    }

    private fun timeAtX(x: Float): Long = (centerMs + (x - width / 2f) / pxPerMs).toLong()

    private fun xForTime(timeMs: Float): Float = width / 2f + (timeMs - centerMs) * pxPerMs

    private fun xForTime(timeMs: Long): Float = xForTime(timeMs.toFloat())

    private fun hiZoom(): Float = tileWidth / GRID_MS

    private fun loZoom(): Float {
        if (durationMs <= 0L || width <= 0) return dp(0.004f)
        val fit = (width * 0.9f) / durationMs
        return max(dp(0.0004f), min(fit, hiZoom()))
    }

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
            30000, 60000, 120000, 300000, 600000, 1800000, 3600000
        )
        for (candidate in candidates) {
            if (candidate * pxPerMs >= dp(40f)) return candidate
        }
        return candidates[candidates.size - 1]
    }

    private fun formatTime(ms: Long): String {
        val value = ms.coerceAtLeast(0L)
        if (labelStepMs < 1000L) {
            val tenths = (value + 50L) / 100L
            val whole = tenths / 10
            val fraction = tenths % 10
            return if (fraction == 0L) whole.toString() else "$whole.$fraction"
        }
        val totalSeconds = value / 1000
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        private const val GRID_MS = 100L
        private const val REMOVE_ANIM_MS = 280L
    }
}
