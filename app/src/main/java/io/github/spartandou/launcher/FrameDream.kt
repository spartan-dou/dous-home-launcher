package io.github.spartandou.launcher

import android.service.dreams.DreamService
import android.view.KeyEvent

/**
 * The photo frame as the system screensaver, for the TV: Android starts and stops it. As on
 * ImmichFrame, left and right change the photo and OK (or play/pause) pauses it; any other key goes
 * back to what was on screen.
 */
class FrameDream : DreamService() {
    private var frame: Frame? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        setContentView(R.layout.frame)
        frame = Frame(this, findViewById(R.id.frame_root), touch = false, onClose = ::finish, onSettings = {})
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val frame = frame ?: return super.dispatchKeyEvent(event)
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (event.action == KeyEvent.ACTION_DOWN) frame.skip(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (event.action == KeyEvent.ACTION_DOWN) frame.skip(1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.action == KeyEvent.ACTION_UP) frame.togglePause(); true }
            else -> false
        }
        if (handled) return true
        // Any other key wakes up, as a non-interactive screensaver did
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
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
