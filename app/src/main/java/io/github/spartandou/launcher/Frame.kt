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
    private val sensors: TextView = root.findViewById(R.id.sensors)
    private val notes: LinearLayout = root.findViewById(R.id.notes)
    private val controls: LinearLayout = root.findViewById(R.id.controls)
    private var pauseButton: ImageView? = null
    private var sourceKey = prefs.sourceKey

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
            root.findViewById(R.id.caption_box), root.findViewById(R.id.caption), root.findViewById(R.id.caption_date), prefs) {}
        if (touch) buildControls()
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

    private fun showOverlay() {
        val now = System.currentTimeMillis()
        val values = Overlay.values(now).joinToString("     ") { listOf(it.icon, it.text).filter(String::isNotEmpty).joinToString(" ") }
        sensors.text = values
        sensors.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE

        val current = Overlay.notes(now).toList()
        if (current != shownNotes) {
            shownNotes = current
            notes.removeAllViews()
            current.forEach { notes.addView(noteCard(it)) }
        }
        main.removeCallbacks(endOfNote)
        Overlay.nextEnd().takeIf { it > 0 }?.let { main.postDelayed(endOfNote, (it - now).coerceAtLeast(0) + 500) }
    }

    // A frosted card, like the home screen's surfaces
    private fun noteCard(note: Overlay.Note): View {
        val card = FrameLayout(context)
        card.addView(GlassView(context).apply { tint = 0x73000000 }, FrameLayout.LayoutParams(-1, -1))
        card.addView(TextView(context).apply {
            text = note.message
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 5
            setLineSpacing(0f, 1.15f)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }, FrameLayout.LayoutParams(-1, -2))
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        val link = note.link
        if (touch && link != null) card.setOnClickListener { open(link) }
        return card
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
    }
}
