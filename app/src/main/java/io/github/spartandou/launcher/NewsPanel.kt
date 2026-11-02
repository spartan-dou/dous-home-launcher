package io.github.spartandou.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * "New" panel on the right: one portrait poster at a time from the apps' channels (recently
 * added...), cross-fading every few seconds. Focused: left/right browse, OK opens the entry. Only
 * the shown poster (and the one fading out) are in memory.
 */
class NewsPanel(
    private val context: Context,
    private val box: FrameLayout,
    private val onOpen: (Program) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var width = Tiles.dp(context, MAX_WIDTH_DP)
    private var height = width * 3 / 2
    // Room left by the rows: too little and the panel stays hidden
    private var fits = true

    private val card = FrameLayout(context)
    private val posters = listOf(ImageView(context), ImageView(context))
    private val title = text(15f, 0xFFFFFFFF.toInt()).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        maxLines = 2
    }

    private val caption: LinearLayout
    private var items: List<Pair<Program, WatchNext.Channel>> = emptyList()
    private var index = 0
    private var front = 0
    private var running = false

    private val rotate = object : Runnable {
        override fun run() {
            if (!card.isFocused) show(index + 1)
            main.postDelayed(this, ROTATE_MS)
        }
    }

    init {
        card.isFocusable = true
        card.isClickable = true
        card.foreground = FocusFrame(Tiles.dp(context, 12).toFloat(), Tiles.dp(context, Tiles.FRAME_DP).toFloat())
        card.background = GradientDrawable().apply { setColor(0x59000000) }
        // Floats like the app tiles
        card.elevation = Tiles.dp(context, Tiles.ELEVATION_DP).toFloat()
        val radius = Tiles.dp(context, 12).toFloat()
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        card.clipToOutline = true
        posters.forEach { card.addView(it.apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1)) }
        // The poster speaks for itself: its title (over a dark gradient) only while it is focused
        caption = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            val p = Tiles.dp(context, 10)
            setPadding(p, Tiles.dp(context, 28), p, p)
            addView(title)
            visibility = View.GONE
        }
        card.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        card.setOnClickListener { items.getOrNull(index)?.let { onOpen(it.first) } }
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { show(index + 1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { show(index - 1); true }
                else -> false
            }
        }
        card.setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.05f else 1f
            view.animate().scaleX(scale).scaleY(scale).alpha(if (focused) 1f else REST_ALPHA).setDuration(120).start()
            caption.visibility = if (focused) View.VISIBLE else View.GONE
            dim(!focused)
        }
        // At rest it blends into the photo: darker and see-through, full brightness when focused
        card.alpha = REST_ALPHA
        dim(true)
        box.addView(card, FrameLayout.LayoutParams(width, height))
        box.visibility = View.GONE
    }

    val view: View get() = card

    val isShown: Boolean get() = box.visibility == View.VISIBLE

    /** Width of the poster card, for what sits beside it. */
    val cardWidth: Int get() = width

    fun setItems(list: List<Pair<Program, WatchNext.Channel>>) {
        if (list == items) return
        items = list
        index = 0
        box.visibility = if (list.isEmpty() || !fits) View.GONE else View.VISIBLE
        if (list.isNotEmpty()) show(0)
    }


    /**
     * Sizes the poster to the height left between the top of the screen and the rows (portrait
     * 2:3, at most 110 dp wide); below a minimum the panel is hidden rather than squeezed.
     */
    fun fit(available: Int) {
        val maxHeight = Tiles.dp(context, MAX_WIDTH_DP) * 3 / 2
        val h = available.coerceAtMost(maxHeight)
        fits = h >= Tiles.dp(context, MIN_HEIGHT_DP)
        if (fits && h != height) {
            height = h
            width = h * 2 / 3
            card.layoutParams = FrameLayout.LayoutParams(width, height)
            // Pictures were cut for the old size
            if (items.isNotEmpty()) show(index)
        }
        box.visibility = if (items.isEmpty() || !fits) View.GONE else View.VISIBLE
    }

    fun hide() {
        items = emptyList()
        box.visibility = View.GONE
        posters.forEach { it.setImageDrawable(null) }
    }

    fun start() {
        if (running) return
        running = true
        main.postDelayed(rotate, ROTATE_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacks(rotate)
    }

    private fun show(i: Int) {
        if (items.isEmpty()) return
        index = Math.floorMod(i, items.size)
        val program = items[index].first
        title.text = program.title
        val uri = program.image
        val wanted = index
        worker.execute {
            val picture = uri?.let { WatchNext.bytes(context, it) }?.let { WatchNext.decode(it, width) }?.let { crop(it) }
            main.post { if (index == wanted) crossFade(picture) }
        }
    }

    // The new poster fades in over the old one, which is then dropped
    private fun crossFade(picture: Drawable?) {
        val back = posters[1 - front]
        val old = posters[front]
        back.setImageDrawable(picture)
        back.alpha = 0f
        back.bringToFront()
        // Keeps the title above the posters
        caption.bringToFront()
        back.animate().alpha(1f).setDuration(FADE_MS).withEndAction { old.setImageDrawable(null) }.start()
        front = 1 - front
    }

    /**
     * At the card size: a portrait poster fills it; a landscape still (some apps only have those)
     * is shown whole in the middle, over a dimmed, stretched copy of itself, so no title is cut.
     */
    private fun crop(bitmap: Bitmap): Drawable {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        fun draw(scale: Float) {
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((width - bitmap.width * scale) / 2, (height - bitmap.height * scale) / 2)
            }
            canvas.drawBitmap(bitmap, matrix, paint)
        }
        val cover = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        draw(cover)
        if (bitmap.width > bitmap.height) {
            canvas.drawColor(0xB3000000.toInt())
            draw(width.toFloat() / bitmap.width)
        }
        bitmap.recycle()
        return BitmapDrawable(context.resources, out)
    }

    private fun dim(on: Boolean) =
        posters.forEach { if (on) it.setColorFilter(REST_DARKEN, android.graphics.PorterDuff.Mode.SRC_ATOP) else it.clearColorFilter() }

    private fun text(sp: Float, color: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }

    companion object {
        private const val ROTATE_MS = 8_000L
        private const val MAX_WIDTH_DP = 110
        private const val MIN_HEIGHT_DP = 110
        private const val FADE_MS = 700L
        private const val REST_ALPHA = 0.9f
        private const val REST_DARKEN = 0x26000000
    }
}
