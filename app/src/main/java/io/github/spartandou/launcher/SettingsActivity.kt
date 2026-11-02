package io.github.spartandou.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Launcher settings, laid out like the Android TV ones: sections on the left, the focused one on
 * the right. Plain framework views (no AndroidX). Immich is connected from a phone (QR code).
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private var setup: SetupServer? = null
    private var setupUrl: String? = null
    private var section = -1

    private val sections = listOf(
        R.string.section_photos to ::photos,
        R.string.section_albums to ::albums,
        R.string.section_weather to ::weatherSection,
        R.string.section_display to ::display,
        R.string.section_layout to ::layout,
        R.string.section_connection to ::connection,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(0xFF0F1115.toInt())

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(40), dp(16), dp(40))
            setBackgroundColor(0xFF15181E.toInt())
        }
        menu.addView(text(getString(R.string.settings_name), 26f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, dp(24))
        })
        val items = sections.mapIndexed { i, (label, _) ->
            menuItem(getString(label)).also { item ->
                item.setOnFocusChangeListener { _, focused -> if (focused) show(i) }
                // OK on a section goes into it
                item.setOnClickListener { content.requestFocus() }
                menu.addView(item)
            }
        }
        root.addView(menu, LinearLayout.LayoutParams(dp(300), -1))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(56), dp(40))
        }
        contentScroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        root.addView(contentScroll, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)

        startSetup()
        // Not connected yet: open on the connection section
        val start = if (prefs.apiKey.isBlank()) sections.lastIndex else 0
        items[start].requestFocus()
        show(start)
    }

    override fun onDestroy() {
        setup?.stop()
        super.onDestroy()
    }

    private fun show(i: Int) {
        if (i == section) return
        section = i
        content.removeAllViews()
        content.addView(text(getString(sections[i].first), 30f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(16))
        })
        sections[i].second()
        contentScroll.scrollTo(0, 0)
    }

    private fun refresh() {
        val current = section
        section = -1
        show(current)
    }

    // --- Sections ---

    private fun photos() {
        add(check(getString(R.string.memories), prefs.memories) { prefs.memories = it })
        add(choice(getString(R.string.interval_label), { intervalName(prefs.intervalSeconds) }) {
            val all = Prefs.INTERVALS
            prefs.intervalSeconds = all[(all.indexOf(prefs.intervalSeconds) + 1) % all.size]
        })
        add(check(getString(R.string.show_place), prefs.showPlace) { prefs.showPlace = it })
        add(check(getString(R.string.show_date), prefs.showDate) { prefs.showDate = it })
    }

    private fun albums() {
        if (prefs.apiKey.isBlank()) {
            add(hint(getString(R.string.albums_need_connection)))
            return
        }
        add(hint(getString(R.string.albums_hint)))
        val grid = GridLayout(this).apply { columnCount = 3 }
        add(grid)
        add(hint(getString(R.string.loading)))
        val client = ImmichClient(prefs.serverUrl, prefs.apiKey)
        Thread {
            val result = try { Result.success(client.albums()) } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (isDestroyed || content.indexOfChild(grid) < 0) return@post
                content.removeViewAt(content.childCount - 1)
                result.onFailure { add(hint(getString(R.string.server_error, it.message ?: it.javaClass.simpleName))) }
                result.onSuccess { albums ->
                    // Three columns and the checked albums first: the choice shows without scrolling
                    albums.sortedByDescending { it.id in prefs.albumIds }.forEach { album ->
                        grid.addView(check("${album.name}  (${album.count})", album.id in prefs.albumIds) { checked ->
                            prefs.albumIds = if (checked) prefs.albumIds + album.id else prefs.albumIds - album.id
                        }.apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, GridLayout.LayoutParams().apply {
                            width = 0
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            rightMargin = dp(16)
                        })
                    }
                    if (albums.isEmpty()) add(hint(getString(R.string.no_albums)))
                }
            }
        }.start()
    }

    private fun weatherSection() {
        add(check(getString(R.string.show_weather), prefs.showWeather) { prefs.showWeather = it })
        add(choice(getString(R.string.weather_city), {
            prefs.weatherCity.ifBlank { Weather(this).placeName?.let { "$it (${getString(R.string.weather_auto)})" } ?: getString(R.string.weather_auto) }
        }, onClick = false) { editCity() })
        add(hint(getString(R.string.weather_city_help)))
    }

    private fun display() {
        add(check(getString(R.string.auto_hide), prefs.autoHide) { prefs.autoHide = it })
        add(check(getString(R.string.show_status), prefs.showStatus) { prefs.showStatus = it })
        add(cycle(getString(R.string.clock_label), R.array.clock_positions, { prefs.clockPosition }) { prefs.clockPosition = it })
        add(cycle(getString(R.string.accent_label), R.array.accents, { prefs.accent }) { prefs.accent = it })
    }

    private fun layout() {
        add(cycle(getString(R.string.dock_label), R.array.dock_positions, { if (prefs.dockOnTop) 1 else 0 }) { prefs.dockOnTop = it == 1 })
        add(cycle(getString(R.string.row_count_label), R.array.row_counts, { prefs.rowCount - 1 }) { prefs.rowCount = it + 1; refresh() })
        for (row in 0 until prefs.rowCount) {
            add(subheader(getString(R.string.row_n, row + 1)))
            add(choice(getString(R.string.row_title), { prefs.rowTitle(row).ifEmpty { getString(R.string.no_title) } }, onClick = false) {
                editText(getString(R.string.row_title), prefs.rowTitle(row), getString(R.string.row_title_hint)) {
                    prefs.setRowTitle(row, it)
                    refresh()
                }
            })
            add(cycle(getString(R.string.row_size_label), R.array.tile_sizes, { prefs.rowSize(row) }) { prefs.setRowSize(row, it) })
        }
        add(hint(getString(R.string.layout_hint)))
    }

    private fun connection() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val url = setupUrl
        if (url != null) {
            box.addView(ImageView(this).apply { setImageBitmap(qrBitmap(url, dp(200))) }, LinearLayout.LayoutParams(dp(200), dp(200)))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        texts.addView(text(if (url != null) getString(R.string.scan_qr_url, url) else getString(R.string.no_network), 16f))
        texts.addView(text(
            if (prefs.apiKey.isBlank()) getString(R.string.not_connected_yet) else getString(R.string.connected_to, prefs.serverUrl), 15f
        ).apply { alpha = 0.7f; setPadding(0, dp(16), 0, 0) })
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        add(box)
        add(hint(getString(R.string.connection_hint)))
    }

    // --- Actions ---

    private fun startSetup() {
        val server = SetupServer(prefs) { main.post { if (section == sections.lastIndex || section == 1) refresh() } }
        setupUrl = try { server.start() } catch (e: Exception) { null }
        if (setupUrl != null) setup = server
    }

    private fun editCity() = editText(getString(R.string.weather_city), prefs.weatherCity, getString(R.string.weather_city_input_hint)) { city ->
        if (city.isEmpty()) {
            prefs.weatherCity = ""
            refresh()
            return@editText
        }
        Thread {
            val place = try { Weather.geocode(city) } catch (e: Exception) { null }
            main.post {
                if (place == null) {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage(getString(R.string.city_not_found, city)).setPositiveButton(android.R.string.ok, null).show()
                } else {
                    prefs.weatherCity = city
                    refresh()
                }
            }
        }.start()
    }

    private fun editText(title: String, value: String, hint: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setSelection(text.length)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Views ---

    private fun add(view: View) = content.addView(view)

    private fun intervalName(s: Int) = if (s < 60) "$s s" else "${s / 60} min"

    // Black on white with a quiet zone, the most readable for phone cameras
    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private fun rounded(focused: Int, normal: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(focused) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(normal) })
    }

    private fun menuItem(label: String) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isFocusable = true
        isClickable = true
        background = rounded(0x33FFFFFF, Color.TRANSPARENT)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    }

    /** A row with the setting's name on the left and its value on the right, like Android TV. */
    private fun choice(label: String, value: () -> String, onClick: Boolean = true, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = rounded(0x33FFFFFF, 0x0FFFFFFF)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        row.addView(text(label, 17f), LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = text(value(), 16f).apply { alpha = 0.75f }
        row.addView(valueView)
        row.setOnClickListener {
            action()
            if (onClick) valueView.text = value()
        }
        return row
    }

    private fun cycle(label: String, choices: Int, get: () -> Int, set: (Int) -> Unit): View {
        val names = resources.getStringArray(choices)
        return choice(label, { names[get().coerceIn(names.indices)] }) { set((get() + 1) % names.size) }
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        background = rounded(0x33FFFFFF, Color.TRANSPARENT)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    }

    private fun subheader(value: String) = text(value.uppercase(), 13f).apply {
        alpha = 0.6f
        letterSpacing = 0.08f
        setPadding(0, dp(20), 0, 0)
    }

    private fun hint(value: String) = text(value, 14f).apply { alpha = 0.6f; setPadding(0, dp(16), 0, 0) }

    private fun text(value: String, sp: Float) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(value: Int) = Tiles.dp(this, value)
}
