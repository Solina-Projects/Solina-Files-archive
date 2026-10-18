package ir.solina.fm

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** Lightweight pinch-to-zoom and pan image surface for the in-app media viewer. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val transform = Matrix()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var scale = 1f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(1f, 8f)
            val factor = next / scale
            transform.postScale(factor, factor, detector.focusX, detector.focusY)
            scale = next
            imageMatrix = transform
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = transform
        isClickable = true
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && scale > 1f) {
                    transform.postTranslate(event.x - lastX, event.y - lastY)
                    imageMatrix = transform
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resetZoom() {
        scale = 1f
        transform.reset()
        imageMatrix = transform
        post { fitImage() }
    }

    private fun fitImage() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        val fit = min(width.toFloat() / drawable.intrinsicWidth, height.toFloat() / drawable.intrinsicHeight)
        val dx = (width - drawable.intrinsicWidth * fit) / 2f
        val dy = (height - drawable.intrinsicHeight * fit) / 2f
        transform.setScale(fit, fit)
        transform.postTranslate(dx, dy)
        scale = 1f
        imageMatrix = transform
    }
}
