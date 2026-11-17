package io.github.spartandou.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The photo frame, as an app on a tablet and as the screensaver on the TV: the same photos as the
 * home screen with the clock, plus what Home Assistant sends (notifications, values under the
 * clock) and what it changes (memories, pause, next photo).
 *
 * [touch]: a tap shows the buttons, a swipe changes the photo and a notification opens its link.
 * Without it (the screensaver) nothing is interactive: the first key goes back to the TV.
 */
class Frame(
    private val context: Context,
    private val root: View,
    private val touch: Boolean,
    private val onClose: () -> Unit,
    private val onSettings: () -> Unit,
) : FrameServer.Host {
    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val wall: PhotoWall
    private val weather = WeatherLine(root.findViewById(R.id.weather), prefs, worker)
    private val server = FrameServer(prefs.frameKey, this)
    private val clockCard: View = root.findViewById(R.id.clock_card)
    private val captionCard: View = root.findViewById(R.id.caption_box)
    private val sensors: LinearLayout = root.findViewById(R.id.sensors)
    private val sensorsRule: View = root.findViewById(R.id.sensors_rule)
    private val notes: LinearLayout = root.findViewById(R.id.notes)
    private val controls: LinearLayout = root.findViewById(R.id.controls)
    private var pauseButton: ImageView? = null
    private var sourceKey = prefs.sourceKey
    // 1% of the screen's shorter side: the cards are sized on it, as ImmichFrame's overlay (vmin)
    private var vmin = 0f

    private val hideControls = Runnable {
        controls.animate().alpha(0f).setDuration(400).withEndAction { controls.visibility = View.GONE }.start()
    }

    // Weather and the age of what Home Assistant sent, checked now and then
    private val tick = object : Runnable {
        override fun run() {
            weather.update()
            showOverlay()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val endOfNote = Runnable { showOverlay() }

    init {
        val slides = root.findViewById<FrameLayout>(R.id.slides)
        wall = PhotoWall(context, listOf(slides.getChildAt(0) as FrameLayout, slides.getChildAt(1) as FrameLayout),
            captionCard, root.findViewById(R.id.caption), root.findViewById(R.id.caption_date), prefs, stacked = true) {}
        if (touch) buildControls()
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val size = minOf(right - left, bottom - top) / 100f
            if (size > 0 && size != vmin) {
                vmin = size
                fit()
            }
        }
        // Tapping the clock opens Home Assistant, as on ImmichFrame
        if (touch) clockCard.setOnClickListener { openHomeAssistant() }
    }

    fun start() {
        // The albums or memories may have been changed in the settings meanwhile
        val changed = sourceKey != prefs.sourceKey
        sourceKey = prefs.sourceKey
        wall.start()
        if (changed) wall.skip()
        main.removeCallbacks(tick)
        tick.run()
        server.start()
    }

    fun stop() {
        server.stop()
        main.removeCallbacks(tick)
        main.removeCallbacks(endOfNote)
        main.removeCallbacks(hideControls)
        wall.stop()
    }

    /** Android wants memory back. */
    fun release() = wall.release()

    fun onRotated() = wall.onRotated()

    /** The frame is gone for good: its photos and threads go with it. */
    fun destroy() {
        wall.close()
        worker.shutdown()
    }

    // --- What Home Assistant asks ---

    override fun state(): JSONObject = JSONObject()
        .put("id", prefs.deviceId)
        .put("name", Build.MODEL)
        .put("memories", prefs.memories)
        .put("memories_only", prefs.memoriesOnly)
        .put("paused", wall.paused)
        .put("place", wall.current?.place ?: JSONObject.NULL)
        .put("date", wall.current?.date ?: JSONObject.NULL)

    override fun settings(changes: JSONObject) {
        if (changes.has("memories")) prefs.memories = changes.optBoolean("memories")
        if (changes.has("memories_only")) prefs.memoriesOnly = changes.optBoolean("memories_only")
        if (changes.has("paused")) setPaused(changes.optBoolean("paused"))
        // Other photos to show: at once, not after the one on screen has had its time
        if (sourceKey != prefs.sourceKey) {
            sourceKey = prefs.sourceKey
            wall.skip()
        }
    }

    override fun command(name: String): Boolean {
        when (name) {
            "next" -> wall.skip()
            "previous" -> wall.skip(-1)
            "close" -> onClose()
            // The screen off and left off (Tasker's former HA_LOCK); needs the device admin
            "screen_off" -> return FrameKeeper.sleep(context)
            else -> return false
        }
        return true
    }

    override fun notification(json: JSONObject) {
        Overlay.notify(json, System.currentTimeMillis())
        showOverlay()
    }

    override fun sensors(json: JSONObject) {
        json.optJSONArray("sensors")?.let { Overlay.setValues(it, System.currentTimeMillis()) }
        showOverlay()
    }

    // --- Notifications and values ---

    private var shownNotes: List<Overlay.Note> = emptyList()
    private var shownValues: List<Overlay.Value> = emptyList()

    private fun showOverlay() {
        val now = System.currentTimeMillis()
        val values = Overlay.values(now).toList()
        if (values != shownValues) {
            shownValues = values
            sensors.removeAllViews()
            values.forEachIndexed { i, value -> sensors.addView(valueView(value), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = px(2.4f) }) }
            val shown = if (values.isEmpty()) View.GONE else View.VISIBLE
            sensors.visibility = shown
            sensorsRule.visibility = shown
        }

        val current = Overlay.notes(now).toList()
        if (current != shownNotes) {
            shownNotes = current
            notes.removeAllViews()
            current.forEach { notes.addView(noteCard(it)) }
        }
        main.removeCallbacks(endOfNote)
        Overlay.nextEnd().takeIf { it > 0 }?.let { main.postDelayed(endOfNote, (it - now).coerceAtLeast(0) + 500) }
    }

    /**
     * Sizes everything on the screen's shorter side, as ImmichFrame's overlay does (its CSS in
     * vmin): the same look in landscape and portrait, on the tablet and on the TV.
     */
    private fun fit() {
        val margin = px(3f)
        (clockCard.layoutParams as FrameLayout.LayoutParams).setMargins(margin, 0, margin, margin)
        (captionCard.layoutParams as FrameLayout.LayoutParams).setMargins(margin, 0, margin, margin)
        clockCard.requestLayout()
        captionCard.requestLayout()
        glass(clockCard, 4.4f)
        glass(captionCard, 3f)

        root.findViewById<View>(R.id.clock_content).setPadding(px(3.2f), px(2.2f), px(3.2f), px(2.4f))
        size(R.id.frame_time, 7.5f)
        size(R.id.frame_date, 1.7f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = px(0.8f) }
        size(R.id.weather, 2f).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = px(0.8f)
            compoundDrawablePadding = px(0.8f)
        }
        (sensorsRule.layoutParams as LinearLayout.LayoutParams).topMargin = px(1.4f)
        sensors.setPadding(0, px(1.3f), 0, 0)

        root.findViewById<View>(R.id.caption_content).setPadding(px(2.4f), px(1.4f), px(2.4f), px(1.4f))
        size(R.id.caption_date, 2f)
        size(R.id.caption, 2.1f).apply {
            maxWidth = (root.width * 0.4f).toInt()
            ((parent as View).layoutParams as LinearLayout.LayoutParams).topMargin = px(0.3f)
        }
        listOf(R.id.caption_date_icon, R.id.caption_place_icon).forEach { id ->
            root.findViewById<View>(id).layoutParams = LinearLayout.LayoutParams(px(2.2f), px(2.2f)).apply { marginEnd = px(0.8f) }
        }

        notes.setPadding(margin, margin, margin, 0)
        // Built again at the new size
        shownNotes = emptyList()
        shownValues = emptyList()
        showOverlay()
    }

    private fun glass(card: View, radius: Float) {
        ((card as FrameLayout).getChildAt(0) as GlassView).apply {
            cornerRadius = px(radius).toFloat()
            tint = GLASS_TINT
        }
    }

    private fun size(id: Int, vminSize: Float): TextView =
        root.findViewById<TextView>(id).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * vminSize) }

    private fun px(vminSize: Float) = (vmin * vminSize).toInt()

    // Icon, value, then the unit smaller and softer
    private fun valueView(value: Overlay.Value): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
        fun part(text: String, size: Float, alpha: Float, medium: Boolean) = TextView(context).apply {
            this.text = text
            setTextAppearance(R.style.GlassText)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * size)
            this.alpha = alpha
            if (medium) {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                fontFeatureSettings = "tnum"
            }
            maxLines = 1
        }
        if (value.icon.isNotEmpty()) addView(part(value.icon, 2.2f, 1f, false), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(0.6f) })
        addView(part(value.value, 2.2f, 1f, true))
        if (value.unit.isNotEmpty()) addView(part(value.unit, 2.2f * 0.75f, 0.75f, false), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(0.2f) })
    }

    // A frosted pill at the top, centred; a linked one ends with "›"
    private fun noteCard(note: Overlay.Note): View {
        val card = FrameLayout(context)
        card.addView(GlassView(context).apply { tint = GLASS_TINT; cornerRadius = px(3f).toFloat() }, FrameLayout.LayoutParams(-1, -1))
        val link = note.link
        card.addView(TextView(context).apply {
            text = if (link != null) "${note.message} ›" else note.message
            setTextAppearance(R.style.GlassText)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 2.4f)
            gravity = android.view.Gravity.CENTER
            maxLines = 5
            maxWidth = (root.width * 0.7f).toInt()
            setLineSpacing(0f, 1.3f)
            setPadding(px(2.6f), px(1.2f), px(2.6f), px(1.2f))
        }, FrameLayout.LayoutParams(-2, -2))
        card.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(1.2f) }
        if (touch && link != null) card.setOnClickListener { open(link) }
        return card
    }

    private fun openHomeAssistant() {
        val intent = context.packageManager.getLaunchIntentForPackage(HOME_ASSISTANT) ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open Home Assistant", e)
        }
    }

    /** "/path" is a Home Assistant view, opened by its app; other links are opened as they are. */
    private fun open(link: String) {
        val uri = Uri.parse(if (link.startsWith("/")) "homeassistant://navigate$link" else link)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Nothing opens $uri", e)
        }
    }

    // --- Touch ---

    private fun buildControls() {
        fun button(icon: Int, label: Int, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            contentDescription = context.getString(label)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x8C000000.toInt()) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { onClick(); showControls() }
            controls.addView(this, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginStart = dp(10); marginEnd = dp(10) })
        }
        button(R.drawable.ic_previous, R.string.frame_previous) { wall.skip(-1) }
        pauseButton = button(R.drawable.ic_pause, R.string.frame_pause) { setPaused(!wall.paused) }
        button(R.drawable.ic_next, R.string.frame_next) { wall.skip() }
        button(R.drawable.ic_settings, R.string.settings_name) { onSettings() }
        button(R.drawable.ic_close, R.string.frame_close) { onClose() }

        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE) { main.removeCallbacks(hideControls); hideControls.run() } else showControls()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) < abs(velocityY) * 2 || abs(velocityX) < dp(400)) return false
                wall.skip(if (velocityX < 0) 1 else -1)
                return true
            }
        })
        root.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            gestures.onTouchEvent(event)
        }
    }

    private fun showControls() {
        main.removeCallbacks(hideControls)
        controls.animate().cancel()
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(150).start()
        main.postDelayed(hideControls, CONTROLS_MS)
    }

    private fun setPaused(paused: Boolean) {
        wall.paused = paused
        pauseButton?.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        pauseButton?.contentDescription = context.getString(if (paused) R.string.frame_resume else R.string.frame_pause)
    }

    private fun dp(value: Int) = Tiles.dp(context, value)

    companion object {
        private const val TAG = "Frame"
        private const val TICK_MS = 30_000L
        private const val CONTROLS_MS = 5_000L
        private const val HOME_ASSISTANT = "io.homeassistant.companion.android"
        // ImmichFrame's cards: 18 % of a dark tint over the blurred photo
        private const val GLASS_TINT = 0x2E000000
    }
}
