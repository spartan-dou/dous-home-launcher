package io.github.spartandou.launcher

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import java.util.concurrent.Executor

/** The weather line under the clock, on the home screen and on the photo frame. */
/** [compact]: the photo frame's, its icon and temperatures only ("Demain 9° / 12°"). */
class WeatherLine(private val view: TextView, private val prefs: Prefs, private val worker: Executor, private val compact: Boolean = false) {
    private val weather = Weather(view.context)
    private val main = Handler(Looper.getMainLooper())
    private var loading = false
    private var icon = 0

    /** Shows what is known, and goes online when it is too old (at most once an hour). */
    fun update() {
        if (!prefs.showWeather) {
            view.visibility = View.GONE
            return
        }
        show()
        val city = prefs.weatherCity
        if (loading || !weather.stale(city)) return
        loading = true
        worker.execute {
            val ok = try { weather.refresh(city); true } catch (e: Exception) { Log.w(TAG, "Weather not loaded", e); false }
            main.post {
                loading = false
                if (ok && prefs.showWeather) show()
            }
        }
    }

    private fun show() {
        val line = weather.line() ?: return
        view.text = if (compact) line.text.substringBefore("  ·  ") else line.text
        if (line.icon != icon) {
            icon = line.icon
            val size = Tiles.dp(view.context, 18)
            view.setCompoundDrawablesRelative(view.context.getDrawable(line.icon)?.apply { setBounds(0, 0, size, size) }, null, null, null)
        }
        view.visibility = View.VISIBLE
    }

    companion object {
        private const val TAG = "WeatherLine"
    }
}
