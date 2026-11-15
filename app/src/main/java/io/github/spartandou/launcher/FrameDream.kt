package io.github.spartandou.launcher

import android.service.dreams.DreamService

/**
 * The photo frame as the system screensaver, for the TV: Android starts and stops it, and any key
 * of the remote goes back to what was on screen.
 */
class FrameDream : DreamService() {
    private var frame: Frame? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.frame)
        frame = Frame(this, findViewById(R.id.frame_root), touch = false, onClose = ::finish, onSettings = {})
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        frame?.start()
    }

    override fun onDreamingStopped() {
        frame?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        frame?.destroy()
        frame = null
        super.onDetachedFromWindow()
    }
}
