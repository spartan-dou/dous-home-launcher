package io.github.spartandou.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Remote buttons remapped to apps, whatever is on screen (what Key Mapper did, in the launcher's
 * process: no extra app running). A button can open one app on a short press, another on a long
 * one and a third on two presses in a row; a mapped button no longer does what the system made it
 * do. Every other button is left alone.
 */
class KeyService : AccessibilityService() {
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    // The mappings as last read, and the stored text they came from
    private var raw = ""
    private var byKey: Map<Int, List<KeyMapping>> = emptyMap()

    private var longPress: Runnable? = null
    private var longDone = false
    // A first press waiting to see whether a second one follows
    private var waiting: Runnable? = null
    private var waitingKey = 0

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val mappings = mappings()[event.keyCode] ?: return false
        fun app(gesture: Int) = mappings.firstOrNull { it.gesture == gesture }?.app
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longDone = false
                cancelLongPress()
                app(KeyMapping.LONG)?.let { target ->
                    longPress = Runnable { longDone = true; open(target) }.also { main.postDelayed(it, LONG_MS) }
                }
            }
            KeyEvent.ACTION_UP -> {
                cancelLongPress()
                if (!longDone) pressed(event.keyCode, app(KeyMapping.SHORT), app(KeyMapping.DOUBLE))
            }
        }
        // Down and up are both kept from the system; the app opens on release
        return true
    }

    private fun pressed(keyCode: Int, short: String?, double: String?) {
        val first = waiting
        if (first != null && waitingKey == keyCode) {
            main.removeCallbacks(first)
            waiting = null
            double?.let(::open)
            return
        }
        // Without a double press to wait for, the app opens at once
        if (double == null) {
            short?.let(::open)
            return
        }
        waitingKey = keyCode
        waiting = Runnable { waiting = null; short?.let(::open) }.also { main.postDelayed(it, DOUBLE_MS) }
    }

    private fun cancelLongPress() {
        longPress?.let(main::removeCallbacks)
        longPress = null
    }

    // Read again only when the settings changed: this runs for every key pressed on the device
    private fun mappings(): Map<Int, List<KeyMapping>> {
        val stored = prefs.keyMappingsRaw
        if (stored != raw) {
            raw = stored
            byKey = prefs.keyMappings.groupBy { it.keyCode }
        }
        return byKey
    }

    private fun open(target: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(target)
            ?: packageManager.getLaunchIntentForPackage(target)
        if (intent == null) {
            Log.w(TAG, "$target is not installed")
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "KeyService"
        private const val LONG_MS = 600L
        private const val DOUBLE_MS = 350L
    }
}
