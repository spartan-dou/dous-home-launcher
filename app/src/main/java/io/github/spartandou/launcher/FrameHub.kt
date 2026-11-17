package io.github.spartandou.launcher

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * The one Home Assistant API of the app, held by whoever needs it: the frame while it is on screen
 * (the TV's screensaver, the tablet's app), and on a tablet the frame keeper the whole time, so
 * Home Assistant keeps the tablet available between two frames. The frame on screen answers; with
 * none, the settings, notifications and values are kept for the next one, and the screen can still
 * be turned off. Main thread only.
 */
object FrameHub : FrameServer.Host {
    private var server: FrameServer? = null
    private val holders = mutableSetOf<Any>()
    private lateinit var app: Context

    /** The frame on screen, which answers for itself. */
    var active: Frame? = null

    fun hold(context: Context, holder: Any) {
        app = context.applicationContext
        holders += holder
        val running = server
        if (running == null) server = FrameServer(Prefs(app).frameKey, this).also { it.start() }
        else running.start()
    }

    fun release(holder: Any) {
        holders -= holder
        if (holders.isEmpty()) {
            server?.stop()
            server = null
        }
    }

    /** The network changed: listen again, on the new local address. */
    fun restart() {
        server?.let {
            it.stop()
            it.start()
        }
    }

    override fun state(): JSONObject = active?.state() ?: Prefs(app).let { prefs ->
        JSONObject()
            .put("id", prefs.deviceId)
            .put("name", Build.MODEL)
            .put("memories", prefs.memories)
            .put("memories_only", prefs.memoriesOnly)
            .put("paused", false)
            .put("brightness", prefs.frameBrightness.takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("place", JSONObject.NULL)
            .put("date", JSONObject.NULL)
    }

    override fun settings(changes: JSONObject) {
        active?.let { return it.settings(changes) }
        val prefs = Prefs(app)
        if (changes.has("memories")) prefs.memories = changes.optBoolean("memories")
        if (changes.has("memories_only")) prefs.memoriesOnly = changes.optBoolean("memories_only")
        if (changes.has("brightness")) prefs.frameBrightness = changes.optInt("brightness", -1).coerceIn(-1, 100)
    }

    // Without a frame, the photo commands have nothing to change: done all the same
    override fun command(name: String): Boolean = active?.command(name) ?: when (name) {
        "screen_off" -> FrameKeeper.sleep(app)
        "next", "previous", "close" -> true
        else -> false
    }

    override fun notification(json: JSONObject) {
        active?.let { return it.notification(json) }
        Overlay.notify(json, System.currentTimeMillis())
    }

    override fun sensors(json: JSONObject) {
        active?.let { return it.sensors(json) }
        json.optJSONArray("sensors")?.let { Overlay.setValues(it, System.currentTimeMillis()) }
    }
}
