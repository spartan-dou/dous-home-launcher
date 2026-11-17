package io.github.spartandou.launcher

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * App tiles in the 16:9 TV banner shape, floating on a shadow. Focused: a thin accent frame, a
 * slight zoom and a deeper shadow, the other tiles of the row dimmed.
 */
object Tiles {
    const val MARGIN_DP = 10
    private const val PRESSED_SCALE = 0.94f
    const val FRAME_DP = 2
    const val ELEVATION_DP = 4
    private const val DIMMED = 0.75f

    fun height(width: Int) = width * 9 / 16

    /**
     * An icon tile's icon: 44 dp on the TV as always; on a tablet as large as the tile leaves room
     * for, above its name (no small icon lost in a large card).
     */
    fun iconSize(context: Context, width: Int): Int =
        if (context.isTv()) dp(context, 44) else (height(width) - dp(context, 42)).coerceIn(dp(context, 36), dp(context, 64))

    /** An empty tile: its picture comes later from [fill], rendered off the main thread. */
    /** [zoom] false: the caller moves and sizes the tiles itself (the carousel). */
    fun create(context: Context, label: String, zoom: Boolean = true, onFocus: (Boolean) -> Unit): FrameLayout {
        val tile = FrameLayout(context)
        tile.isFocusable = true
        tile.isClickable = true
        tile.isLongClickable = true
        tile.contentDescription = label
        tile.foreground = FocusFrame(dp(context, 10).toFloat(), dp(context, FRAME_DP).toFloat())
        tile.background = context.getDrawable(R.drawable.tile_background)
        val radius = dp(context, 10).toFloat()
        tile.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        tile.clipToOutline = true
        tile.elevation = dp(context, ELEVATION_DP).toFloat()

        // On a tablet a tap has no focus to show: the tile gives a little under the finger. The TV
        // keeps its focus frame only, as before
        if (!context.isTv()) tile.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).alpha(0.8f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
            }
            false
        }

        tile.setOnFocusChangeListener { view, focused ->
            if (zoom) {
                val scale = if (focused) 1.1f else 1f
                view.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) dp(context, 10).toFloat() else 0f)
                    .setDuration(120).start()
                dimSiblings(view)
            }
            onFocus(focused)
        }
        return tile
    }

    // The focused tile stands out from the rest of its row; no focus in the row, none is dimmed
    private fun dimSiblings(tile: View) {
        val row = tile.parent as? ViewGroup ?: return
        row.post {
            val any = row.hasFocus()
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                val alpha = if (!any || child.isFocused) 1f else DIMMED
                if (child.alpha != alpha) child.animate().alpha(alpha).setDuration(120).start()
            }
        }
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
            else if (context.isTv()) FrameLayout.LayoutParams(dp(context, 44), dp(context, 44), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(context, 14) }
            // Tablet: the icon at the size it was drawn at for this tile (Tiles.iconSize)
            else (icon?.intrinsicWidth?.takeIf { it > 0 } ?: dp(context, 44)).let { size ->
                FrameLayout.LayoutParams(size, size, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(context, 10) }
            }
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
