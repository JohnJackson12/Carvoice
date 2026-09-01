package com.carvoice.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Sits directly on top of the main playback SeekBar (same bounds, in a
 * shared FrameLayout - see activity_main.xml) and draws the trim
 * start/end points as two draggable dots right on that same bar, with the
 * would-be-cut regions dimmed - replacing the old design of two separate,
 * easy-to-confuse "Trim start" / "Trim end" SeekBars that never showed
 * the actual kept-window of the song at a glance.
 *
 * Dragging a dot only updates the LIVE preview (via [onTrimChanged],
 * wired to VoiceService.setTrim - the existing non-destructive "skip this
 * much on playback" behavior) - nothing is written to the file just from
 * dragging. Actually cutting the file is a separate, explicit action (the
 * scissors button next to this view, see MainActivity.applyRealTrim) -
 * dragging a dot around should never risk losing audio by itself.
 *
 * Touch handling only claims touches that start near an existing dot
 * (within [touchSlopPx]); everything else is left alone (returns false)
 * so the SeekBar underneath still scrubs playback position normally for
 * every other tap/drag on the bar.
 */
class TrimOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var durationMs: Int = 0
        set(value) { field = value.coerceAtLeast(0); invalidate() }

    var startMs: Int = 0
        private set
    var endMs: Int = 0  // absolute position (NOT "seconds from end") - durationMs minus the trim-end seconds
        private set

    /** Fired continuously while dragging, with (startSeconds, endSecondsFromEnd) -
     * matches the (frontSeconds, endSeconds) shape VoiceService.setTrim
     * already expects. */
    var onTrimChanged: ((frontSeconds: Int, endSecondsFromEnd: Int) -> Unit)? = null

    private val minGapMs = 2_000  // dots can't cross/overlap - always leave at least 2s of song between them

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#552196F3")
        style = Paint.Style.FILL
    }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val dotRadiusPx = resources.displayMetrics.density * 9f
    private val touchSlopPx = resources.displayMetrics.density * 24f

    private var draggingStart = false
    private var draggingEnd = false

    /** Sets both points at once without firing [onTrimChanged] - for
     * syncing FROM an external source (VoiceService.trimCallback, a newly
     * loaded song's saved trim) rather than user dragging. */
    fun setTrimSilently(frontSeconds: Int, endSecondsFromEnd: Int) {
        startMs = (frontSeconds * 1000).coerceIn(0, durationMs)
        endMs = (durationMs - endSecondsFromEnd * 1000).coerceIn(0, durationMs)
        invalidate()
    }

    private fun xForMs(ms: Int): Float {
        if (durationMs <= 0) return 0f
        return width * (ms.toFloat() / durationMs)
    }

    private fun msForX(x: Float): Int {
        if (width <= 0 || durationMs <= 0) return 0
        return ((x / width) * durationMs).toInt().coerceIn(0, durationMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0 || width <= 0) return
        val h = height.toFloat()
        val startX = xForMs(startMs)
        val endX = xForMs(endMs)

        // Dim the parts of the song that a trim right now would remove -
        // this IS "the activity window" the two-separate-sliders design
        // never showed: you can see exactly what's kept at a glance.
        if (startX > 0) canvas.drawRect(0f, 0f, startX, h, cutPaint)
        if (endX < width) canvas.drawRect(endX, 0f, width.toFloat(), h, cutPaint)
        canvas.drawRect(startX, 0f, endX, h, trackPaint)

        drawDot(canvas, startX, h / 2f)
        drawDot(canvas, endX, h / 2f)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, dotRadiusPx, dotPaint)
        canvas.drawCircle(x, y, dotRadiusPx, dotStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val startX = xForMs(startMs)
                val endX = xForMs(endMs)
                val distToStart = kotlin.math.abs(event.x - startX)
                val distToEnd = kotlin.math.abs(event.x - endX)
                if (distToStart > touchSlopPx && distToEnd > touchSlopPx) {
                    return false  // not near either dot - let the SeekBar underneath handle this touch
                }
                if (distToStart <= distToEnd) draggingStart = true else draggingEnd = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingStart && !draggingEnd) return false
                val ms = msForX(event.x)
                if (draggingStart) {
                    startMs = ms.coerceIn(0, endMs - minGapMs).coerceAtLeast(0)
                } else if (draggingEnd) {
                    endMs = ms.coerceIn(startMs + minGapMs, durationMs)
                }
                invalidate()
                fireChange()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingStart || draggingEnd) {
                    draggingStart = false
                    draggingEnd = false
                    fireChange()
                    return true
                }
                return false
            }
        }
        return false
    }

    private fun fireChange() {
        val frontSeconds = startMs / 1000
        val endSecondsFromEnd = (durationMs - endMs) / 1000
        onTrimChanged?.invoke(frontSeconds, endSecondsFromEnd)
    }
}
