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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * the right; a value is picked from a list. Plain framework views (no AndroidX). Immich is
 * connected from a phone (QR code).
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    // Focused lines take the home screen's accent colour (the photo's in automatic mode), see-through
    private val focusColor by lazy {
        val accent = if (prefs.accent == Prefs.ACCENT_AUTO) Accent.color else prefs.accentColor
        (accent and 0x00FFFFFF) or 0x59000000
    }
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private var setup: SetupServer? = null
    private var setupUrl: String? = null
    private var section = -1
    // Row being edited in the "Rows" section, -1 for the list of rows
    private var editedRow = -1
    private var apps: List<App>? = null
    private var rendering = false

    private val sections = listOf(
        R.string.section_photos to ::photos,
        R.string.section_albums to ::albums,
        R.string.section_weather to ::weatherSection,
        R.string.section_display to ::display,
        R.string.section_rows to ::rows,
        R.string.section_news to ::newsSection,
        R.string.section_remote to ::remote,
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
                // Not while a section is rebuilt: removing the focused line moves the focus to the menu
                item.setOnFocusChangeListener { _, focused -> if (focused && !rendering) show(i) }
                // OK on a section goes into it
                item.setOnClickListener { content.requestFocus() }
                menu.addView(item)
            }
        }
        root.addView(menu, LinearLayout.LayoutParams(dp(300), -1))

        // Up or down past the first or last line stays put instead of jumping into the menu
        // (which would switch sections); only left goes back to the menu
        content = object : LinearLayout(this) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                val next = super.focusSearch(focused, direction)
                val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
                return if (vertical && focused != null && next != null && !isInside(next, this)) focused else next
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(56), dp(40))
        }
        // Not focusable itself: right from the menu lands on the first line, not on the scroll area
        contentScroll = ScrollView(this).apply { addView(content); isFillViewport = true; isFocusable = false }
        root.addView(contentScroll, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)

        startSetup()
        // Labels of the installed apps, for the rows section
        Thread { val loaded = Apps.load(this); main.post { apps = loaded } }.start()
        // Not connected yet: open on the connection section
        val start = if (prefs.apiKey.isBlank()) sections.lastIndex else 0
        items[start].requestFocus()
        show(start)
    }

    override fun onDestroy() {
        setup?.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back from a row's page goes to the list of rows, not out of the settings
        if (section == ROWS && editedRow >= 0) {
            editedRow = -1
            refresh(focus = 1)
        } else {
            super.onBackPressed()
        }
    }

    private fun show(i: Int) {
        if (i == section) return
        section = i
        editedRow = -1
        render()
        contentScroll.scrollTo(0, 0)
    }

    private fun render() {
        rendering = true
        try {
            content.removeAllViews()
            content.addView(text(getString(sections[section].first), 30f).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(16))
            })
            sections[section].second()
        } finally {
            rendering = false
        }
    }

    /** Rebuilds the section; the focus stays on the same line (or [focus], an index in the content). */
    private fun refresh(focus: Int? = null) {
        val focused = currentFocus
        val index = focus ?: (0 until content.childCount).firstOrNull { content.getChildAt(it).let { v -> v === focused || (v is ViewGroup && focused != null && isInside(focused, v)) } }
        val scroll = contentScroll.scrollY
        render()
        // A new page (explicit focus) starts at the top, the same page keeps its scroll
        contentScroll.post { contentScroll.scrollTo(0, if (focus != null) 0 else scroll) }
        index?.let { i -> (content.getChildAt(i.coerceAtMost(content.childCount - 1)))?.requestFocus() }
    }

    private fun isInside(view: View, group: ViewGroup): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === group) return true
            parent = parent.parent
        }
        return false
    }

    // --- Sections ---

    private fun photos() {
        add(check(getString(R.string.memories), prefs.memories) { prefs.memories = it })
        val intervals = Prefs.INTERVALS.map { if (it < 60) "$it s" else "${it / 60} min" }.toTypedArray()
        add(pick(getString(R.string.interval_label), intervals, { Prefs.INTERVALS.indexOf(prefs.intervalSeconds) }) {
            prefs.intervalSeconds = Prefs.INTERVALS[it]
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
                            ellipsize = TextUtils.TruncateAt.END
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
        }) { editCity() })
        add(hint(getString(R.string.weather_city_help)))
    }

    private fun display() {
        add(check(getString(R.string.auto_hide), prefs.autoHide) { prefs.autoHide = it })
        add(check(getString(R.string.show_status), prefs.showStatus) { prefs.showStatus = it })
        add(check(getString(R.string.drift), prefs.drift) { prefs.drift = it })
        add(pick(getString(R.string.clock_label), array(R.array.clock_positions), { prefs.clockPosition }) { prefs.clockPosition = it })
        add(pick(getString(R.string.clock_size_label), array(R.array.clock_sizes), { prefs.clockSize }) { prefs.clockSize = it })
        add(pick(getString(R.string.accent_label), array(R.array.accents), { prefs.accent }) { prefs.accent = it })
    }

    private fun rows() = if (editedRow >= 0) rowPage(editedRow) else rowList()

    private fun rowList() {
        val rows = prefs.appRows.orEmpty()
        add(pick(getString(R.string.visible_rows_label), array(R.array.row_counts), { prefs.visibleRows - 1 }) { prefs.visibleRows = it + 1 })
        add(pick(getString(R.string.dock_label), array(R.array.dock_positions), { if (prefs.dockOnTop) 1 else 0 }) { prefs.dockOnTop = it == 1 })
        if (rows.any { it.pinned }) {
            add(pick(getString(R.string.pinned_position), array(R.array.pinned_positions), { if (prefs.pinnedOnTop) 0 else 1 }) { prefs.pinnedOnTop = it == 0 })
        }
        add(subheader(getString(R.string.your_rows)))
        val sizes = array(R.array.tile_sizes)
        rows.forEachIndexed { i, row ->
            val summary = if (row.continueWatching) {
                getString(R.string.continue_summary) + " · " + sizes[row.size]
            } else {
                val installed = apps?.let { list -> row.apps.count { pkg -> list.any { it.packageName == pkg } } } ?: row.apps.size
                resources.getQuantityString(R.plurals.app_count, installed, installed) + " · " + sizes[row.size]
            }
            add(choice(rowName(row, i), { if (row.pinned) "$summary · ${getString(R.string.pinned)}" else summary }) { editedRow = i; refresh(focus = 1) })
        }
        add(action(getString(R.string.add_row)) {
            val updated = rows + AppRow(getString(R.string.row_n, rows.size + 1), 1, mutableListOf())
            prefs.appRows = updated
            editedRow = updated.lastIndex
            refresh(focus = 1)
        })
        // One "continue watching" row at most, placed and set up like the others
        if (rows.none { it.continueWatching }) add(action(getString(R.string.add_continue_row)) {
            val updated = rows + AppRow(getString(R.string.continue_title), 1, mutableListOf(), continueWatching = true)
            prefs.appRows = updated
            editedRow = updated.lastIndex
            refresh(focus = 1)
        })
        add(hint(getString(R.string.rows_hint)))
    }

    /** The apps whose entries the "continue watching" row shows; none ticked means all of them. */
    private fun continueSources(row: AppRow, save: () -> Unit) {
        add(subheader(getString(R.string.continue_sources)))
        add(hint(getString(R.string.continue_sources_hint)))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(box)
        Thread {
            val publishers = (WatchNext.publishers(this) + row.apps).distinct()
            main.post {
                if (isDestroyed || content.indexOfChild(box) < 0) return@post
                if (publishers.isEmpty()) box.addView(hint(getString(R.string.continue_none)))
                // By name, alphabetically
                publishers.map { pkg ->
                    pkg to try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                }.sortedWith(compareBy(frenchOrder) { it.second }).forEach { (pkg, label) ->
                    box.addView(check(label, pkg in row.apps) { checked ->
                        if (checked) row.apps += pkg else row.apps.remove(pkg)
                        save()
                    })
                }
            }
        }.start()
    }

    private fun rowPage(r: Int) {
        val rows = prefs.appRows.orEmpty().toMutableList()
        if (r !in rows.indices) { editedRow = -1; rowList(); return }
        val row = rows[r]
        fun save() { prefs.appRows = rows }

        add(action(getString(R.string.back_to_rows)) { editedRow = -1; refresh(focus = 1) })
        add(subheader(rowName(row, r)))
        add(choice(getString(R.string.row_title), { row.title.ifEmpty { getString(R.string.no_title) } }) {
            editText(getString(R.string.row_title), row.title, getString(R.string.row_title_hint)) { row.title = it; save(); refresh() }
        })
        add(pick(getString(R.string.row_size_label), array(R.array.tile_sizes), { row.size }) { row.size = it; save() })
        // One pinned row at most: ticking this one unpins the others
        add(check(getString(R.string.pin_row), row.pinned) { checked ->
            rows.forEach { it.pinned = false }
            row.pinned = checked
            save()
        })
        add(hint(getString(R.string.pin_row_hint)))

        if (row.continueWatching) continueSources(row) { save() } else {
            add(subheader(getString(R.string.row_apps)))
            val labels = apps.orEmpty().associate { it.packageName to it.label }
            // Apps uninstalled since they were put on the row are left out (home skips them too)
            row.apps.toList().forEachIndexed { i, pkg ->
                if (apps != null && pkg !in labels) return@forEachIndexed
                add(choice(labels[pkg] ?: pkg, { "" }) {
                    val actions = mutableListOf<Pair<String, () -> Unit>>()
                    if (i > 0) actions += getString(R.string.move_left) to { row.apps.add(i - 1, row.apps.removeAt(i)) }
                    if (i < row.apps.size - 1) actions += getString(R.string.move_right) to { row.apps.add(i + 1, row.apps.removeAt(i)) }
                    rows.indices.filter { it != r && !rows[it].continueWatching }.forEach { other ->
                        actions += getString(R.string.move_to_row, rowName(rows[other], other)) to { row.apps.removeAt(i); rows[other].apps += pkg }
                    }
                    actions += getString(R.string.remove) to { row.apps.removeAt(i) }
                    menu(labels[pkg] ?: pkg, actions) { save(); refresh() }
                })
            }
            add(action(getString(R.string.add_app)) {
                // Apps on another row move here; the ones already on this row are not offered
                val candidates = apps.orEmpty().filter { it.packageName !in row.apps && it.packageName != packageName }
                menu(getString(R.string.add_app), candidates.map { app ->
                    app.label to { rows.filter { !it.continueWatching }.forEach { it.apps.remove(app.packageName) }; row.apps += app.packageName }
                }) { save(); refresh() }
            })
        }

        add(subheader(getString(R.string.row_order)))
        if (r > 0) add(action(getString(R.string.row_up)) { rows.add(r - 1, rows.removeAt(r)); save(); editedRow = r - 1; refresh() })
        if (r < rows.size - 1) add(action(getString(R.string.row_down)) { rows.add(r + 1, rows.removeAt(r)); save(); editedRow = r + 1; refresh() })
        add(action(getString(R.string.delete_row)) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.delete_row_confirm, rowName(row, r)))
                .setPositiveButton(R.string.delete) { _, _ -> rows.removeAt(r); save(); editedRow = -1; refresh(focus = 1) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
    }

    private fun remote() {
        val labels = apps.orEmpty()
        add(choice(getString(R.string.netflix_key), {
            labels.firstOrNull { it.packageName == prefs.netflixKeyApp }?.label ?: getString(R.string.key_default)
        }) { valueView ->
            val choices = listOf(getString(R.string.key_default) to "") + labels.map { it.label to it.packageName }
            val current = choices.indexOfFirst { it.second == prefs.netflixKeyApp }.coerceAtLeast(0)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.netflix_key)
                .setSingleChoiceItems(choices.map { it.first }.toTypedArray(), current) { dialog, which ->
                    prefs.netflixKeyApp = choices[which].second
                    valueView.text = choices[which].first
                    dialog.dismiss()
                }
                .show()
        })
        // The button only works through the accessibility service, which the user turns on
        val enabled = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains("$packageName/")
        add(hint(getString(if (enabled) R.string.key_service_on else R.string.key_service_off)))
    }

    /** The "new" panel: on or off, and the channels it takes its posters from. */
    private fun newsSection() {
        add(check(getString(R.string.show_news), prefs.showNews) { prefs.showNews = it })
        add(subheader(getString(R.string.news_channels)))
        add(hint(getString(R.string.news_channels_hint)))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(box)
        Thread {
            val channels = WatchNext.channels(this).values.distinctBy { it.key }
            main.post {
                if (isDestroyed || content.indexOfChild(box) < 0) return@post
                if (channels.isEmpty()) box.addView(hint(getString(R.string.continue_none)))
                // Until chosen, the "recently added" channels are the ones used
                val chosen = prefs.newsChannels ?: channels.filter { WatchNext.isRecentlyAdded(it) }.map { it.key }.toSet()
                channels.map { it to "${appLabel(it.packageName)} · ${it.name}" }
                    .sortedWith(compareBy(frenchOrder) { it.second })
                    .forEach { (channel, label) ->
                        box.addView(check(label, channel.key in chosen) { checked ->
                            val current = prefs.newsChannels ?: chosen
                            prefs.newsChannels = if (checked) current + channel.key else current - channel.key
                        })
                    }
            }
        }.start()
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
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
        val server = SetupServer(prefs) { main.post { if (section == sections.lastIndex || section == ALBUMS) refresh() } }
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

    private fun menu(title: String, actions: List<Pair<String, () -> Unit>>, after: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second(); after() }
            .show()
    }

    private fun rowName(row: AppRow, index: Int) = row.title.ifEmpty { getString(R.string.row_n, index + 1) }

    // --- Views ---

    private fun add(view: View) = content.addView(view)

    private fun array(id: Int): Array<String> = resources.getStringArray(id)

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
        background = rounded(focusColor, Color.TRANSPARENT)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    }

    /** A line with the setting's name on the left and its value on the right, like Android TV. */
    private fun choice(label: String, value: () -> String, onClick: (TextView) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = rounded(focusColor, 0x0FFFFFFF)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        row.addView(text(label, 17f), LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = text(value(), 16f).apply { alpha = 0.75f }
        row.addView(valueView)
        row.setOnClickListener { onClick(valueView) }
        return row
    }

    /** OK opens the list of all the values, the current one ticked; the line updates in place. */
    private fun pick(label: String, names: Array<String>, get: () -> Int, set: (Int) -> Unit): View =
        choice(label, { names[get().coerceIn(names.indices)] }) { valueView ->
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setSingleChoiceItems(names, get().coerceIn(names.indices)) { dialog, which ->
                    set(which)
                    valueView.text = names[which]
                    dialog.dismiss()
                }
                .show()
        }

    /** A line that does something (no value), e.g. "Add a row". */
    private fun action(label: String, onClick: () -> Unit): View = choice(label, { "" }) { onClick() }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        background = rounded(focusColor, Color.TRANSPARENT)
        buttonTintList = android.content.res.ColorStateList.valueOf((focusColor or 0xFF000000.toInt()))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    }

    private fun subheader(value: String) = text(value.uppercase(), 13f).apply {
        alpha = 0.6f
        letterSpacing = 0.08f
        setPadding(0, dp(24), 0, 0)
    }

    private fun hint(value: String) = text(value, 14f).apply { alpha = 0.6f; setPadding(0, dp(16), 0, 0) }

    private fun text(value: String, sp: Float) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(value: Int) = Tiles.dp(this, value)

    companion object {
        private const val ALBUMS = 1
        private const val ROWS = 4
    }
}
