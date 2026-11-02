package io.github.spartandou.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The accent colour of the focus frames: a fixed one, or in automatic mode the liveliest colour of
 * the current photo, taken from its small blurred copy (about 1 ms, no extra memory).
 */
object Accent {
    var color: Int = Color.WHITE
        private set
    var onChange: (() -> Unit)? = null
    private var fade: ValueAnimator? = null

    fun set(target: Int, animate: Boolean) {
        fade?.cancel()
        if (!animate) {
            color = target
            onChange?.invoke()
            return
        }
        fade = ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
            duration = 800
            addUpdateListener { color = it.animatedValue as Int; onChange?.invoke() }
            start()
        }
    }

    /** The most saturated bright colour of the photo, lifted so it reads well on any background. */
    fun fromPhoto(blur: Bitmap): Int {
        val hsv = FloatArray(3)
        var best = FloatArray(3)
        var bestScore = -1f
        val stepX = (blur.width / 24).coerceAtLeast(1)
        val stepY = (blur.height / 14).coerceAtLeast(1)
        for (y in 0 until blur.height step stepY) {
            for (x in 0 until blur.width step stepX) {
                Color.colorToHSV(blur.getPixel(x, y), hsv)
                val score = hsv[1] * (0.4f + hsv[2])
                if (score > bestScore) { bestScore = score; best = hsv.copyOf() }
            }
        }
        // Grey photos give a soft white; colourful ones a light, clearly tinted accent
        if (best[1] < 0.15f) return 0xFFEDEDED.toInt()
        best[1] = best[1].coerceIn(0.35f, 0.6f)
        best[2] = 1f
        return Color.HSVToColor(best)
    }
}

/** Rounded frame in the current [Accent] colour, drawn only on the focused tile. */
class FocusFrame(private val radius: Float, strokeWidth: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    private val rect = RectF()
    private var focused = false

    override fun draw(canvas: Canvas) {
        if (!focused) return
        val half = paint.strokeWidth / 2
        rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        paint.color = Accent.color
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_focused in state
        if (now == focused) return false
        focused = now
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
