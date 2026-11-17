package io.github.spartandou.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
    private val clockBox: View = root.findViewById(R.id.clock_box)
    private val captionCard: View = root.findViewById(R.id.caption_box)
    private val sensors: LinearLayout = root.findViewById(R.id.sensors)
    private val notes: LinearLayout = root.findViewById(R.id.notes)
    private val pauseMark: ImageView = root.findViewById(R.id.pause_mark)
    private var sourceKey = prefs.sourceKey
    // 1% of the screen's shorter side: the cards are sized on it, as ImmichFrame's overlay (vmin)
    private var vmin = 0f
    // A tap is not repeated while the app it opened comes up (the fork's "busy")
    private var clockBusyUntil = 0L
    private var noteBusyUntil = 0L

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
            captionCard, root.findViewById(R.id.caption), root.findViewById(R.id.caption_date), prefs, frame = true) {}
        if (touch) buildGestures()
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val size = minOf(right - left, bottom - top) / 100f
            if (size > 0 && size != vmin) {
                vmin = size
                fit()
            }
        }
        // The time opens the launcher's home screen; the rest of the clock (date, weather) Home
        // Assistant as it was left, as on ImmichFrame; each value its own view (below)
        if (touch) {
            root.findViewById<View>(R.id.frame_time).setOnClickListener { openHome() }
            clockBox.setOnClickListener { openHomeAssistant() }
        }
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
        applyBrightness()
    }

    fun stop() {
        server.stop()
        main.removeCallbacks(tick)
        main.removeCallbacks(endOfNote)
        brightnessFade?.cancel()
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
        .put("brightness", prefs.frameBrightness.takeIf { it >= 0 } ?: JSONObject.NULL)
        .put("place", wall.current?.place ?: JSONObject.NULL)
        .put("date", wall.current?.date ?: JSONObject.NULL)

    override fun settings(changes: JSONObject) {
        if (changes.has("memories")) prefs.memories = changes.optBoolean("memories")
        if (changes.has("memories_only")) prefs.memoriesOnly = changes.optBoolean("memories_only")
        if (changes.has("paused")) setPaused(changes.optBoolean("paused"))
        if (changes.has("brightness")) {
            prefs.frameBrightness = changes.optInt("brightness", -1).coerceIn(-1, 100)
            applyBrightness()
        }
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
            values.forEachIndexed { i, value -> sensors.addView(valueView(value), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(18) }) }
            sensors.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
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
        (captionCard.layoutParams as FrameLayout.LayoutParams).setMargins(margin, 0, margin, margin)
        captionCard.requestLayout()
        glass(captionCard, 3f)

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

    // Emoji, value, then the unit smaller and softer, as the weather line beside the clock. The
    // emoji is centred on the line, the unit sits on the value's baseline
    private fun valueView(value: Overlay.Value): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        fun part(text: String, sp: Float, alpha: Float, family: String) = TextView(context).apply {
            this.text = text
            setTextAppearance(R.style.ShadowText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL)
            includeFontPadding = false
            this.alpha = alpha
            maxLines = 1
        }
        // Tapped: its room's view in Home Assistant, or Home Assistant as it was left
        if (touch) setOnClickListener { if (target(value.link) != null) open(value.link!!) else openHomeAssistant() }
        if (value.icon.isNotEmpty()) addView(part(value.icon, 15f, 1f, "sans-serif"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            addView(part(value.value, 15f, 1f, "sans-serif-medium").apply { fontFeatureSettings = "tnum" })
            if (value.unit.isNotEmpty()) addView(part(value.unit, 12f, 0.75f, "sans-serif-light"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2) })
        })
    }

    // A frosted pill at the top right (the clock has the left); a linked one ends with "›"
    private fun noteCard(note: Overlay.Note): View {
        val card = FrameLayout(context)
        card.addView(GlassView(context).apply { tint = GLASS_TINT; cornerRadius = px(3f).toFloat() }, FrameLayout.LayoutParams(-1, -1))
        val link = target(note.link)
        card.addView(TextView(context).apply {
            text = if (link != null) "${note.message} ›" else note.message
            setTextAppearance(R.style.GlassText)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 2.4f)
            gravity = android.view.Gravity.CENTER
            maxLines = 5
            maxWidth = (root.width * 0.4f).toInt()
            setLineSpacing(0f, 1.3f)
            setPadding(px(2.6f), px(1.2f), px(2.6f), px(1.2f))
        }, FrameLayout.LayoutParams(-1, -1)) // match_parent, as the glass: see frame.xml
        card.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(1.2f) }
        // Without a link, taps go through to the previous / pause / next zones underneath
        if (touch && link != null) card.setOnClickListener { open(link) }
        return card
    }

    private fun openHome() {
        try {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the home screen", e)
        }
    }

    // --- Brightness: only the frame's window, as ImmichFrame's ---

    private val window get() = (context as? Activity)?.window ?: (context as? DreamService)?.window
    private var brightnessFade: ValueAnimator? = null

    /**
     * Fades the frame's window to the brightness Home Assistant set (never fully dark: 0 % keeps a
     * faint light), or hands it back to the device's. Leaving the frame gives the device its own.
     */
    private fun applyBrightness() {
        val window = window ?: return
        brightnessFade?.cancel()
        val level = prefs.frameBrightness
        if (level < 0) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            return
        }
        val target = MIN_BRIGHTNESS + (1f - MIN_BRIGHTNESS) * level / 100f
        val from = window.attributes.screenBrightness.takeIf { it >= 0f } ?: deviceBrightness()
        brightnessFade = ValueAnimator.ofFloat(from, target).apply {
            duration = BRIGHTNESS_FADE_MS
            addUpdateListener { fade -> window.attributes = window.attributes.apply { screenBrightness = fade.animatedValue as Float } }
            start()
        }
    }

    // Where a fade starts when the device's brightness was in use (its 0-255 setting)
    private fun deviceBrightness(): Float = try {
        (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0f, 1f)
    } catch (e: Exception) {
        0.5f
    }

    // As the ImmichFrame fork's overlay: an empty invitation, so Home Assistant's app finds no
    // server, closes that screen and comes back as it was left (not reloaded on its first view)
    private fun openHomeAssistant() {
        if (System.currentTimeMillis() < clockBusyUntil) return
        clockBusyUntil = System.currentTimeMillis() + BUSY_MS
        clockBox.alpha = 0.5f
        main.postDelayed({ clockBox.alpha = 1f }, BUSY_MS)
        go("homeassistant://invite")
    }

    private fun open(link: String) {
        val target = target(link) ?: return
        if (System.currentTimeMillis() < noteBusyUntil) return
        noteBusyUntil = System.currentTimeMillis() + BUSY_MS
        go(target)
    }

    private fun go(uri: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Nothing opens $uri", e)
        }
    }

    /**
     * As the fork: "/path" opens that view in Home Assistant's app; any scheme other than the web
     * or homeassistant:// is dropped (no javascript: or intent: coming from an automation's text).
     */
    private fun target(link: String?): String? {
        val trimmed = link?.trim().orEmpty()
        if (trimmed.startsWith("/")) return "homeassistant://navigate$trimmed"
        return trimmed.takeIf { Regex("^(https?|homeassistant)://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    // --- Touch ---

    /**
     * As on ImmichFrame, invisible and always there: the left third goes back a photo, the right
     * third forward, the middle pauses or plays (a mark shows which a moment); a swipe changes the
     * photo too, a long press opens the settings.
     */
    private fun buildGestures() {
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val third = root.width / 3f
                when {
                    e.x < third -> wall.skip(-1)
                    e.x > 2 * third -> wall.skip()
                    else -> {
                        setPaused(!wall.paused)
                        showPauseMark()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) = onSettings()

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

    private fun showPauseMark() {
        pauseMark.setImageResource(if (wall.paused) R.drawable.ic_pause else R.drawable.ic_play)
        pauseMark.animate().cancel()
        pauseMark.alpha = 1f
        pauseMark.animate().alpha(0f).setStartDelay(PAUSE_MARK_MS).setDuration(400).start()
    }

    private fun setPaused(paused: Boolean) {
        wall.paused = paused
    }

    private fun dp(value: Int) = Tiles.dp(context, value)

    companion object {
        private const val TAG = "Frame"
        private const val TICK_MS = 30_000L
        private const val PAUSE_MARK_MS = 900L
        private const val BUSY_MS = 2_000L
        private const val BRIGHTNESS_FADE_MS = 2_000L
        // 0 % from Home Assistant: dim, but the photo still there
        private const val MIN_BRIGHTNESS = 0.06f
        private const val HOME_ASSISTANT = "io.homeassistant.companion.android"
        // ImmichFrame's cards: 18 % of a dark tint over the blurred photo
        private const val GLASS_TINT = 0x2E000000
    }
}
