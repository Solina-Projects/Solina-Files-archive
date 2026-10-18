package ir.solina.fm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/** Lightweight animated waveform scrubber used by the audio player and APK preparation UI. */
class WaveformProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var durationMs = 1
    private var positionMs = 0
    private var playing = false
    private var phase = 0f
    var onSeek: ((Int) -> Unit)? = null

    fun setDuration(value: Int) { durationMs = max(1, value); invalidate() }
    fun setPosition(value: Int) { positionMs = value.coerceIn(0, durationMs); invalidate() }
    fun setPlaying(value: Boolean) { playing = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val mid = height / 2f
        if (w <= 0f || height <= 0) return
        val progressX = w * positionMs / durationMs
        trackPaint.color = 0x55888888
        canvas.drawLine(0f, mid, w, mid, trackPaint)
        wavePaint.color = 0xFF9AA0AA.toInt()
        drawWave(canvas, 0f, w, mid, false)
        canvas.save()
        canvas.clipRect(0f, 0f, progressX, height.toFloat())
        wavePaint.color = 0xFF596ED1.toInt()
        drawWave(canvas, 0f, w, mid, true)
        canvas.restore()
        canvas.drawCircle(progressX.coerceIn(2f * density, max(2f * density, w - 2f * density)), mid, 3f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF596ED1.toInt() })
        if (playing) {
            phase += 0.10f
            if (phase > 6.28f) phase = 0f
            postInvalidateDelayed(45L)
        }
    }

    private fun drawWave(canvas: Canvas, start: Float, end: Float, mid: Float, active: Boolean) {
        val path = Path()
        val step = max(2f * density, 3f * density)
        var x = start
        var first = true
        while (x <= end) {
            val envelope = 0.25f + 0.75f * (0.5f + 0.5f * sin(x * 0.031f + 0.7f))
            val amp = (height * 0.36f * envelope).coerceAtLeast(2f * density)
            val y = mid + sin(x * 0.115f + if (active && playing) phase else 0f) * amp
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += step
        }
        canvas.drawPath(path, wavePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.action == MotionEvent.ACTION_MOVE && !isPressed) return false
                isPressed = true
                onSeek?.invoke((event.x.coerceIn(0f, width.toFloat()) / max(1f, width) * durationMs).toInt())
                return true
            }
            MotionEvent.ACTION_UP -> { isPressed = false; performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; return true }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
