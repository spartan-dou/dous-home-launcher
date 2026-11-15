package io.github.spartandou.launcher

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * The photo frame as an app, for a tablet: it keeps the screen on while it is shown. Whatever
 * lights the tablet up and opens it (Home Assistant's own app does both) decides when it shows.
 */
class FrameActivity : Activity() {
    private lateinit var frame: Frame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideTvParts()
        setContentView(R.layout.frame)
        frame = Frame(this, findViewById(R.id.frame_root), touch = true, onClose = ::finish) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * Away from a TV, the app is only a photo frame: its home screen is no longer offered as the
     * device's launcher, nor its remote buttons service in the accessibility settings.
     */
    private fun hideTvParts() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        listOf(HomeActivity::class.java, KeyService::class.java).forEach { part ->
            packageManager.setComponentEnabledSetting(
                ComponentName(this, part), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    override fun onStart() {
        super.onStart()
        frame.start()
    }

    override fun onStop() {
        super.onStop()
        frame.stop()
    }

    // The system bars stay away; a swipe from an edge brings them back for a moment
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        frame.destroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) frame.release()
    }
}
