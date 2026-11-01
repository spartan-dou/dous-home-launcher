package io.github.spartandou.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Launcher settings, plain framework views (no AndroidX). Immich is set up from a phone through
 * the QR code (server, API key, albums); the TV side lists the albums again so they can be changed
 * with the remote.
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var albumsBox: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var setup: SetupServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(0xFF0F1115.toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = dp(48)
            setPadding(p, p, p, p)
        }

        // Left: phone set-up
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(title(getString(R.string.settings_name)))
        val qr = ImageView(this)
        left.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(16) })
        val qrText = text(getString(R.string.scan_qr), 14f).apply { gravity = Gravity.CENTER; maxWidth = dp(300) }
        left.addView(qrText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        status = text("", 14f).apply { gravity = Gravity.CENTER; maxWidth = dp(300); alpha = 0.7f }
        left.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(left, LinearLayout.LayoutParams(dp(320), -1))

        // Right: what to show, changeable with the remote
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), 0, 0, 0) }
        right.addView(header(getString(R.string.photos)))
        right.addView(check(getString(R.string.memories), prefs.memories) { prefs.memories = it })
        albumsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(albumsBox)

        right.addView(header(getString(R.string.display)))
        val interval = Button(this)
        fun refreshInterval() {
            val s = prefs.intervalSeconds
            interval.text = getString(R.string.interval, if (s < 60) "$s s" else "${s / 60} min")
        }
        refreshInterval()
        interval.setOnClickListener {
            val all = Prefs.INTERVALS
            prefs.intervalSeconds = all[(all.indexOf(prefs.intervalSeconds) + 1) % all.size]
            refreshInterval()
        }
        right.addView(interval, LinearLayout.LayoutParams(-2, -2))
        right.addView(check(getString(R.string.show_date), prefs.showDate) { prefs.showDate = it })
        right.addView(check(getString(R.string.show_place), prefs.showPlace) { prefs.showPlace = it })
        right.addView(check(getString(R.string.auto_hide), prefs.autoHide) { prefs.autoHide = it })

        right.addView(header(getString(R.string.layout)))
        right.addView(cycle(R.string.dock_position, R.array.dock_positions, { if (prefs.dockOnTop) 1 else 0 }) { prefs.dockOnTop = it == 1 })
        right.addView(cycle(R.string.tile_size, R.array.tile_sizes, { prefs.tileSize }) { prefs.tileSize = it })
        right.addView(cycle(R.string.clock_position, R.array.clock_positions, { prefs.clockPosition }) { prefs.clockPosition = it })
        right.addView(cycle(R.string.accent, R.array.accents, { prefs.accent }) { prefs.accent = it })
        root.addView(ScrollView(this).apply { addView(right) }, LinearLayout.LayoutParams(0, -1, 1f))

        setContentView(root)

        refreshStatus()
        loadAlbums()
        startSetup(qr, qrText)
    }

    override fun onDestroy() {
        setup?.stop()
        super.onDestroy()
    }

    private fun startSetup(qr: ImageView, qrText: TextView) {
        val server = SetupServer(prefs) { main.post { refreshStatus(); loadAlbums() } }
        val url = try { server.start() } catch (e: Exception) { null }
        if (url == null) {
            qrText.text = getString(R.string.no_network)
            return
        }
        setup = server
        qr.setImageBitmap(qrBitmap(url, dp(220)))
        qrText.text = getString(R.string.scan_qr_url, url)
    }

    private fun refreshStatus() {
        status.text = if (prefs.apiKey.isBlank()) getString(R.string.not_connected)
        else getString(R.string.connected_to, prefs.serverUrl)
    }

    private fun loadAlbums() {
        if (prefs.apiKey.isBlank()) return
        val client = ImmichClient(prefs.serverUrl, prefs.apiKey)
        Thread {
            val result = try { Result.success(client.albums()) } catch (e: Exception) { Result.failure(e) }
            main.post { if (!isDestroyed) showAlbums(result) }
        }.start()
    }

    private fun showAlbums(result: Result<List<Album>>) {
        albumsBox.removeAllViews()
        result.onFailure { albumsBox.addView(text(getString(R.string.server_error, it.message ?: it.javaClass.simpleName), 14f)) }
        result.onSuccess { albums ->
            albums.forEach { album ->
                albumsBox.addView(check("${album.name}  (${album.count})", album.id in prefs.albumIds) { checked ->
                    prefs.albumIds = if (checked) prefs.albumIds + album.id else prefs.albumIds - album.id
                })
            }
        }
    }

    // Black on white with a quiet zone, the most readable for phone cameras
    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    // A button that steps through the choices of a string array, showing the current one
    private fun cycle(label: Int, choices: Int, get: () -> Int, set: (Int) -> Unit): Button {
        val names = resources.getStringArray(choices)
        val button = Button(this)
        fun refresh() {
            button.text = getString(label, names[get().coerceIn(names.indices)])
            // The accent button shows its own colour
            if (choices == R.array.accents) button.setTextColor(prefs.accentColor)
        }
        refresh()
        button.setOnClickListener { set((get() + 1) % names.size); refresh() }
        return button
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = value
        setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun title(value: String) = text(value, 28f).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private fun header(value: String) = text(value.uppercase(), 13f).apply {
        alpha = 0.6f
        letterSpacing = 0.08f
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun text(value: String, sp: Float) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(value: Int) = Tiles.dp(this, value)
}
