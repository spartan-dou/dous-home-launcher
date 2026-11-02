package io.github.spartandou.launcher

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Network and memory alerts as shadowed icons (no network, weak Wi-Fi, RAM nearly full), checked
 * every few seconds while the home screen is shown, from cheap system calls. Not available to apps on the Shield: CPU load (blocked since
 * Android 8) and the remote's battery (the Shield keeps it to its own NVIDIA service).
 */
class StatusBar(private val context: Context, private val row: LinearLayout) {
    private val network = item(R.drawable.ic_wifi)
    private val ram = item(R.drawable.ic_memory)
    private val memoryInfo = ActivityManager.MemoryInfo()

    init {
        // The Shield's own settings, next to the RAM: up from the apps reaches it
        val size = Tiles.dp(context, 34)
        val button = android.widget.FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            contentDescription = context.getString(R.string.shield_settings)
            background = AccentDisc()
            setOnClickListener {
                try {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    android.util.Log.w("StatusBar", "No settings app", e)
                }
            }
        }
        val icon = ImageView(context).apply { setImageResource(R.drawable.ic_tune); alpha = 0.85f }
        button.addView(icon, android.widget.FrameLayout.LayoutParams(Tiles.dp(context, 18), Tiles.dp(context, 18), Gravity.CENTER))
        button.setOnFocusChangeListener { _, focused ->
            icon.setColorFilter(if (focused) 0xFF15181E.toInt() else 0xFFFFFFFF.toInt())
            icon.alpha = if (focused) 1f else 0.85f
        }
        row.addView(button, LinearLayout.LayoutParams(size, size).apply { marginStart = Tiles.dp(context, 12) })
    }

    /** Alerts only: nothing shows while the network and the memory are fine. */
    fun refresh() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val signal = caps?.signalStrength ?: Int.MIN_VALUE
        when {
            caps == null -> network.show(R.drawable.ic_offline, null, ALERT)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && signal != Int.MIN_VALUE && signal < WEAK_WIFI_DBM ->
                network.show(R.drawable.ic_wifi, null, ALERT)
            else -> network.hide()
        }

        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val used = 100 - memoryInfo.availMem * 100 / memoryInfo.totalMem
        if (used >= HIGH_RAM_PERCENT) ram.show(R.drawable.ic_memory, "$used %", ALERT) else ram.hide()
    }

    private fun item(icon: Int): Item {
        val box = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val image = ImageView(context).apply { setImageResource(icon); alpha = 0.85f }
        val text = TextView(context).apply {
            setTextColor(0xF2FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            setPadding(Tiles.dp(context, 5), 0, 0, 0)
        }
        box.addView(image, LinearLayout.LayoutParams(Tiles.dp(context, 18), Tiles.dp(context, 18)))
        box.addView(text)
        box.visibility = android.view.View.GONE
        row.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (row.childCount > 0) Tiles.dp(context, 18) else 0 })
        return Item(box, image, text)
    }

    private class Item(val box: LinearLayout, val image: ImageView, val text: TextView) {
        // The icon says what it is: text only for values
        fun show(icon: Int, value: String?, color: Int) {
            if (image.tag != icon) { image.setImageResource(icon); image.tag = icon }
            image.setColorFilter(color)
            text.setTextColor(color)
            text.text = value
            text.visibility = if (value == null) android.view.View.GONE else android.view.View.VISIBLE
            box.visibility = android.view.View.VISIBLE
        }

        fun hide() {
            box.visibility = android.view.View.GONE
        }
    }

    companion object {
        private const val ALERT = 0xFFFFB74D.toInt()
        private const val HIGH_RAM_PERCENT = 85
        private const val WEAK_WIFI_DBM = -75
    }
}
