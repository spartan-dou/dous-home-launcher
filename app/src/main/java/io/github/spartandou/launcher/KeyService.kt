package io.github.spartandou.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Remote buttons remapped to apps, whatever is on screen (what Key Mapper did, in the launcher's
 * process: no extra app running). Only the Netflix button for now (BUTTON_12 on the Shield remote).
 */
class KeyService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_12) return false
        val target = Prefs(this).netflixKeyApp.ifEmpty { return false }
        // Down and up are both kept from the system; the app opens on release
        if (event.action == KeyEvent.ACTION_UP) {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(target)
                ?: packageManager.getLaunchIntentForPackage(target)
            if (intent == null) {
                Log.w(TAG, "$target is not installed")
                return false
            }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}


    companion object {
        private const val TAG = "KeyService"
    }
}
