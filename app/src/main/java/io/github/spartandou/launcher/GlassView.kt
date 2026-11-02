package io.github.spartandou.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Frosted glass: shows, under its own bounds, the part of the current photo's blurred copy that is
 * behind it, so it looks like a live blur without any per-frame blur work. Cross-fades with the photo.
 */
class GlassView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var mix = 1f
    private var fade: ValueAnimator? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint().apply { color = 0x40000000 }
    private val sheenPaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x2EFFFFFF
    }
    private val matrix = Matrix()
    private val clip = Path()
    private val bounds = RectF()
    private val location = IntArray(2)

    var cornerRadius = 24 * resources.displayMetrics.density
        set(value) { field = value; invalidate() }

    var tint: Int
        get() = tintPaint.color
        set(value) { tintPaint.color = value; invalidate() }

    fun setBlur(bitmap: Bitmap?, fadeMs: Long) {
        fade?.cancel()
        previous = current
        current = bitmap
        mix = 0f
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = fadeMs
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { previous = null }
            })
            start()
        }
    }

    // A background: no size of its own, so a wrap_content parent sizes itself on its content and
    // then stretches the glass to match (otherwise "at most" would mean the whole screen)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        getLocationInWindow(location)
        // The wall recycles a blurred copy once replaced: never draw one that is gone
        previous?.takeIf { !it.isRecycled }?.let { drawAligned(canvas, it, 255) }
        current?.takeIf { !it.isRecycled }?.let { drawAligned(canvas, it, (mix * 255).toInt()) }
        canvas.drawRect(bounds, tintPaint)
        // A light sheen in the accent colour, so the glass picks up the photo's tint
        sheenPaint.color = (Accent.color and 0x00FFFFFF) or SHEEN_ALPHA
        canvas.drawRect(bounds, sheenPaint)
        canvas.restore()
        val half = strokePaint.strokeWidth / 2
        canvas.drawRoundRect(half, half, width - half, height - half, cornerRadius, cornerRadius, strokePaint)
    }

    // Same centre-crop as the photo, in window coordinates, shifted to this view's position
    private fun drawAligned(canvas: Canvas, bitmap: Bitmap, alpha: Int) {
        val screenW = rootView.width.toFloat()
        val screenH = rootView.height.toFloat()
        val scale = maxOf(screenW / bitmap.width, screenH / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate((screenW - bitmap.width * scale) / 2 - location[0], (screenH - bitmap.height * scale) / 2 - location[1])
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    companion object {
        // ~14 %: a tint, not a colour fill
        private const val SHEEN_ALPHA = 0x24000000
    }
}
