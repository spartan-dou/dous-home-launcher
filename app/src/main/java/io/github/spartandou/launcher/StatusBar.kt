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
 * Connection and used RAM as shadowed icons, read every few seconds while the home screen is
 * shown, from cheap system calls. Not available to apps on the Shield: CPU load (blocked since
 * Android 8) and the remote's battery (the Shield keeps it to its own NVIDIA service).
 */
class StatusBar(private val context: Context, private val row: LinearLayout) {
    private val network = item(R.drawable.ic_wifi)
    private val ram = item(R.drawable.ic_memory)
    private val memoryInfo = ActivityManager.MemoryInfo()

    fun refresh() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        when {
            caps == null -> network.show(R.drawable.ic_offline, null)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> network.show(R.drawable.ic_ethernet, null)
            else -> network.show(R.drawable.ic_wifi, null)
        }

        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val used = 100 - memoryInfo.availMem * 100 / memoryInfo.totalMem
        ram.show(R.drawable.ic_memory, "$used %")
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
        row.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (row.childCount > 0) Tiles.dp(context, 18) else 0 })
        return Item(image, text)
    }

    private class Item(val image: ImageView, val text: TextView) {
        // The icon says what it is: text only for values
        fun show(icon: Int, value: String?) {
            if (image.tag != icon) { image.setImageResource(icon); image.tag = icon }
            text.text = value
            text.visibility = if (value == null) android.view.View.GONE else android.view.View.VISIBLE
        }
    }
}
