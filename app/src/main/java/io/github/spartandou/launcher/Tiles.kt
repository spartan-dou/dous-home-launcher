package io.github.spartandou.launcher

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** App tiles in the 16:9 TV banner shape, with an accent frame and a slight zoom on focus. */
object Tiles {
    const val MARGIN_DP = 10

    fun height(width: Int) = width * 9 / 16

    /** An empty tile: its picture comes later from [fill], rendered off the main thread. */
    fun create(context: Context, label: String, onFocus: (Boolean) -> Unit): FrameLayout {
        val tile = FrameLayout(context)
        tile.isFocusable = true
        tile.isClickable = true
        tile.isLongClickable = true
        tile.contentDescription = label
        tile.foreground = FocusFrame(dp(context, 10).toFloat(), dp(context, 3).toFloat())
        tile.background = context.getDrawable(R.drawable.tile_background)
        val radius = dp(context, 10).toFloat()
        tile.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        tile.clipToOutline = true

        tile.setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.1f else 1f
            view.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) dp(context, 8).toFloat() else 0f)
                .setDuration(120).start()
            onFocus(focused)
        }
        return tile
    }

    /** The app banner over the whole tile, or for phone-style apps the icon and the name. */
    fun fill(context: Context, tile: FrameLayout, label: String, banner: Drawable?, icon: Drawable?) {
        tile.removeAllViews()
        if (banner != null) {
            tile.background = null
            tile.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageDrawable(banner)
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // Phone-style apps have no banner: icon and name on a dark glass tile; an icon alone is centred
            val iconParams = if (label.isEmpty()) FrameLayout.LayoutParams(dp(context, 40), dp(context, 40), Gravity.CENTER)
            else FrameLayout.LayoutParams(dp(context, 44), dp(context, 44), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(context, 14) }
            tile.addView(ImageView(context).apply { setImageDrawable(icon) }, iconParams)
            if (label.isNotEmpty()) tile.addView(TextView(context).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = dp(context, 8); leftMargin = dp(context, 8); rightMargin = dp(context, 8)
            })
        }
    }


    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
