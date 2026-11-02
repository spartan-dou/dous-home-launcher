package io.github.spartandou.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var wall: PhotoWall
    private lateinit var status: StatusBar
    private lateinit var weather: Weather
    private lateinit var rowsBox: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var topScrim: View
    private lateinit var bottomScrim: View
    private lateinit var clockBox: LinearLayout
    private lateinit var weatherView: TextView
    private lateinit var infoBox: LinearLayout
    private lateinit var statusBox: View
    private lateinit var allApps: View
    private lateinit var grid: GridLayout

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var apps: List<App> = emptyList()
    private var appsDirty = true
    private var gridDirty = true
    private var uiHidden = false
    private var layoutKey = ""
    private var weatherLoading = false

    // Pictures of the home row tiles, per row size; the "all apps" ones are never kept
    private val rowImages = HashMap<String, TileImage>()

    private val hideUi = Runnable { setUiHidden(true) }

    // Status every few seconds, weather checked each time too (it only goes online once an hour)
    private val tick = object : Runnable {
        override fun run() {
            if (prefs.showStatus) status.refresh()
            if (prefs.showWeather) updateWeather()
            main.postDelayed(this, TICK_MS)
        }
    }

    // The scrim that fades with the apps: the one on their side of the screen
    private val dockScrim: View
        get() = if (prefs.dockOnTop) topScrim else bottomScrim

    // The all-apps grid uses the first row's size
    private fun gridTileWidth() = Tiles.dp(this, Prefs.TILE_WIDTHS[prefs.appRows?.firstOrNull()?.size ?: 2])

    // Apps are read once, then again only when one is installed, updated or removed
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsDirty = true
            if (window.decorView.isShown) loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        prefs = Prefs(this)
        weather = Weather(this)
        rowsBox = findViewById(R.id.rows)
        dock = findViewById(R.id.dock)
        topScrim = findViewById(R.id.top_scrim)
        bottomScrim = findViewById(R.id.bottom_scrim)
        clockBox = findViewById(R.id.clock_box)
        weatherView = findViewById(R.id.weather)
        infoBox = findViewById(R.id.info_box)
        statusBox = findViewById(R.id.status_box)
        allApps = findViewById(R.id.all_apps)
        grid = findViewById(R.id.grid)
        status = StatusBar(this, findViewById(R.id.status_box))

        val slides = findViewById<FrameLayout>(R.id.slides)
        findViewById<GlassView>(R.id.all_apps_glass).apply { cornerRadius = 0f; tint = 0x99000000.toInt() }
        wall = PhotoWall(this, listOf(slides.getChildAt(0) as FrameLayout, slides.getChildAt(1) as FrameLayout),
            findViewById(R.id.caption_box), findViewById(R.id.caption), findViewById(R.id.caption_date), prefs) { blur ->
            if (prefs.accent == Prefs.ACCENT_AUTO) Accent.set(Accent.fromPhoto(blur), animate = true)
        }
        // The focused tile's frame and the glass (lightly tinted) follow the accent colour
        Accent.onChange = {
            window.decorView.findFocus()?.invalidate()
            GlassView.invalidateAll()
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onStart() {
        super.onStart()
        wall.start()
        // Layout settings may have changed in "Réglages de l'accueil"
        if (layoutKey != prefs.layoutKey) {
            layoutKey = prefs.layoutKey
            applyLayout()
            rowImages.clear()
            if (apps.isNotEmpty()) { buildRows(); gridDirty = true }
        }
        if (appsDirty) loadApps()
    }

    override fun onResume() {
        super.onResume()
        setUiHidden(false)
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(hideUi)
        main.removeCallbacks(tick)
    }

    override fun onStop() {
        super.onStop()
        wall.stop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        Accent.onChange = null
        super.onDestroy()
    }

    // Home pressed while already home: back to the first app
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeAllApps()
        setUiHidden(false)
        firstTile()?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // The home screen has nowhere to go back to
        if (allApps.visibility == View.VISIBLE) closeAllApps()
    }

    // Android wants memory back while another app runs: drop photos and the apps grid, both come back fast
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            wall.release()
            grid.removeAllViews()
            gridDirty = true
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (uiHidden) {
                // The first key only brings the apps back, it never launches anything
                setUiHidden(false)
                return true
            }
            scheduleHide()
            if (allApps.visibility != View.VISIBLE && shiftRows(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * More rows than visible ones: moving up from the top visible row, or down from the bottom one,
     * slides the window of visible rows by one, keeping the focus in the same column.
     */
    private fun shiftRows(keyCode: Int): Boolean {
        val up = keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (!up && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val focus = currentFocus ?: return false
        // The round buttons sit on the first band: up from them slides too (the way back from an empty row)
        val onButton = focus.tag == ALL_APPS || focus.tag == SETTINGS
        // Down from them just goes to the tiles below
        if (onButton && !up) return false
        val band = if (onButton) 0 else rowLines.indexOfFirst { it === focus.parent }
        if (band < 0) return false
        val total = prefs.appRows.orEmpty().size
        // Rows read top to bottom on screen whatever the dock position
        val step = when {
            up && band == 0 && rowOffset > 0 -> -1
            !up && band == rowLines.size - 1 && rowOffset + rowLines.size < total -> 1
            else -> return false
        }
        val column = if (onButton) 0 else rowLines[band].indexOfChild(focus)
        rowOffset += step
        // No focused app to follow: that would bring the old row back into view
        buildRows(NO_FOCUS)
        val line = rowLines[if (step < 0) 0 else rowLines.size - 1]
        (line.getChildAt(column.coerceAtMost(line.childCount - 1))?.takeIf { it.isFocusable } ?: firstTile())?.requestFocus()
        return true
    }

    // A long press on Menu opens the settings
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** Apps at the top or bottom; clock left / right / centre / hidden; status and caption in the free corner. */
    private fun applyLayout() {
        val top = prefs.dockOnTop
        val m = Tiles.dp(this, 28)
        (dock.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (top) Gravity.TOP else Gravity.BOTTOM
            topMargin = if (top) m else 0
            bottomMargin = if (top) 0 else m
        }

        val vertical = if (top) Gravity.BOTTOM else Gravity.TOP
        val clock = prefs.clockPosition
        clockBox.visibility = if (clock == Prefs.CLOCK_HIDDEN) View.GONE else View.VISIBLE
        val horizontal = when (clock) {
            Prefs.CLOCK_RIGHT -> Gravity.END
            Prefs.CLOCK_CENTER -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        clockBox.gravity = horizontal
        (clockBox.getChildAt(0) as TextView).setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.CLOCK_SIZES[prefs.clockSize].toFloat())
        (clockBox.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = vertical or horizontal
            topMargin = if (top) 0 else Tiles.dp(this@HomeActivity, 32)
            bottomMargin = if (top) Tiles.dp(this@HomeActivity, 40) else 0
        }
        val infoSide = if (clock == Prefs.CLOCK_RIGHT) Gravity.START else Gravity.END
        infoBox.gravity = infoSide
        (infoBox.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = vertical or infoSide
            topMargin = if (top) 0 else Tiles.dp(this@HomeActivity, 24)
            bottomMargin = if (top) Tiles.dp(this@HomeActivity, 24) else 0
        }
        statusBox.visibility = if (prefs.showStatus) View.VISIBLE else View.GONE
        if (!prefs.showWeather) weatherView.visibility = View.GONE
        if (prefs.accent != Prefs.ACCENT_AUTO) Accent.set(prefs.accentColor, animate = false)
        topScrim.alpha = 1f
        bottomScrim.alpha = 1f
        findViewById<View>(android.R.id.content).requestLayout()
    }

    private fun updateWeather() {
        weather.line()?.let { weatherView.text = it; weatherView.visibility = View.VISIBLE }
        val city = prefs.weatherCity
        if (weatherLoading || !weather.stale(city)) return
        weatherLoading = true
        worker.execute {
            val ok = try { weather.refresh(city); true } catch (e: Exception) { Log.w(TAG, "Weather not loaded", e); false }
            main.post {
                weatherLoading = false
                if (ok && prefs.showWeather) weather.line()?.let { weatherView.text = it; weatherView.visibility = View.VISIBLE }
            }
        }
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideUi)
        if (prefs.autoHide && allApps.visibility != View.VISIBLE) main.postDelayed(hideUi, HIDE_DELAY_MS)
    }

    private fun setUiHidden(hidden: Boolean) {
        uiHidden = hidden
        val alpha = if (hidden) 0f else 1f
        val duration = if (hidden) 800L else 200L
        dock.animate().alpha(alpha).setDuration(duration).start()
        dockScrim.animate().alpha(alpha).setDuration(duration).start()
        if (!hidden) scheduleHide()
    }

    private fun loadApps() {
        appsDirty = false
        worker.execute {
            val loaded = Apps.load(this)
            main.post {
                apps = loaded
                rowImages.clear()
                gridDirty = true
                if (prefs.appRows == null) {
                    // First start: a few apps to begin with, the user picks the rest from "all apps"
                    prefs.appRows = listOf(AppRow("Favoris", 2, loaded.filter { it.packageName != packageName }.take(6).map { it.packageName }.toMutableList()))
                }
                buildRows()
                if (allApps.visibility == View.VISIBLE) buildGrid()
            }
        }
    }

    private val rowLines = mutableListOf<LinearLayout>()

    // Index of the first visible row, when there are more rows than visible ones
    private var rowOffset = 0

    private fun firstTile(): View? = rowLines.firstNotNullOfOrNull { it.getChildAt(0)?.takeIf { v -> v.isFocusable } }

    /**
     * The visible window of rows, one band each (title, glass, tiles). "All apps" and the settings
     * are two small round buttons on the first band's title line, they take no app's place.
     */
    private fun buildRows(focusPackage: String? = null) {
        val focused = focusPackage ?: rowsBox.findFocus()?.tag as? String
        rowsBox.removeAllViews()
        rowLines.clear()
        val byPackage = apps.associateBy { it.packageName }
        val rows = prefs.appRows.orEmpty().ifEmpty { listOf(AppRow("", 2, mutableListOf())) }
        val visible = prefs.visibleRows.coerceAtMost(rows.size)
        rowOffset = rowOffset.coerceIn(0, rows.size - visible)
        // A focused app on another row brings its row into view
        rows.indexOfFirst { focused in it.apps }.takeIf { it >= 0 }?.let { r ->
            if (r < rowOffset) rowOffset = r else if (r >= rowOffset + visible) rowOffset = r - visible + 1
        }
        var toFocus: View? = null
        for (b in 0 until visible) {
            val r = rowOffset + b
            val row = rows[r]
            val position = if (rows.size > visible) "${r + 1} / ${rows.size}" else null
            val (line, name) = newBand(row.title, first = b == 0, position = position)
            val width = Tiles.dp(this, Prefs.TILE_WIDTHS[row.size])
            val rowApps = row.apps.mapNotNull { byPackage[it] }
            rowApps.forEach { app ->
                val tile = appTile(app, width, rowImages, name)
                tile.tag = app.packageName
                tile.setOnClickListener { launch(app) }
                tile.setOnLongClickListener { editFavourite(app); true }
                line.addView(tile, tileParams(width))
                if (app.packageName == focused) toFocus = tile
            }
            if (rowApps.isEmpty()) {
                // An empty row says how to fill it instead of collapsing
                line.addView(TextView(this).apply {
                    text = getString(R.string.empty_row)
                    setTextColor(0x99FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Tiles.dp(context, 12), 0, 0, 0)
                }, LinearLayout.LayoutParams(-2, Tiles.height(width)))
            }
        }
        if (focused == ALL_APPS || focused == SETTINGS) toFocus = rowsBox.findViewWithTag(focused)
        (toFocus ?: firstTile())?.requestFocus()
    }

    /** A tile whose picture is rendered on the worker thread, or taken from [cache] when it has it. */
    private fun appTile(app: App, width: Int, cache: MutableMap<String, TileImage>?, name: TextView?): FrameLayout {
        val tile = Tiles.create(this, app.label) { name?.text = if (it) app.label else "" }
        val key = "${app.packageName}@$width"
        val cached = cache?.get(key)
        if (cached != null) {
            Tiles.fill(this, tile, app.label, cached.banner, cached.icon)
        } else {
            worker.execute {
                val image = Apps.tileImage(this, app, width)
                main.post {
                    cache?.put(key, image)
                    Tiles.fill(this, tile, app.label, image.banner, image.icon)
                }
            }
        }
        return tile
    }

    /**
     * A row's band: title line (title, focused app name, and on the first band the two round
     * buttons; the row position when some rows are hidden), then its own glass and scroller.
     */
    private fun newBand(title: String, first: Boolean, position: String?): Pair<LinearLayout, TextView> {
        val band = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(Tiles.dp(context, 22), 0, Tiles.dp(context, 8), Tiles.dp(context, 6))
        }
        if (title.isNotEmpty()) header.addView(shadowed(title, 17f, Color.WHITE).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, 0, Tiles.dp(context, 14), 0)
        })
        val name = shadowed("", 15f, 0xB3FFFFFF.toInt())
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        position?.let { header.addView(shadowed(it, 14f, 0x99FFFFFF.toInt()).apply { setPadding(0, 0, Tiles.dp(context, 12), 0) }) }
        if (first) {
            header.addView(roundButton(R.drawable.ic_apps, getString(R.string.all_apps), ALL_APPS, name) { openAllApps() }
                .apply { setOnLongClickListener { openSettings(); true } })
            header.addView(roundButton(R.drawable.ic_settings, getString(R.string.settings_name), SETTINGS, name) { openSettings() })
        }
        band.addView(header, LinearLayout.LayoutParams(-1, -2))

        val glassBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        glassBox.addView(GlassView(this), FrameLayout.LayoutParams(-1, -1))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            val pad = Tiles.dp(context, 14)
            setPadding(pad, pad, pad, pad)
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            // Tiles past the edge fade out instead of being cut, and the scroll keeps the focused
            // tile this far from the edge (the fading edge length is also the scroll margin)
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Tiles.dp(context, 56))
            // Wrap height: the default params of a scroller child would fill the whole screen
            addView(line, FrameLayout.LayoutParams(-2, -2))
        }
        // match_parent width: with two stretched children FrameLayout sizes the glass to the scroller
        glassBox.addView(scroller, FrameLayout.LayoutParams(-1, -2))
        band.addView(glassBox, LinearLayout.LayoutParams(-1, -2))

        rowsBox.addView(band, LinearLayout.LayoutParams(-1, -2).apply {
            if (rowsBox.childCount > 0) topMargin = Tiles.dp(this@HomeActivity, 14)
        })
        rowLines += line
        return line to name
    }

    /** Small round icon button of the first band's title line; its name shows while focused. */
    private fun roundButton(icon: Int, label: String, tag: String, name: TextView, onClick: () -> Unit): View {
        val size = Tiles.dp(this, 40)
        return FrameLayout(this).apply {
            this.tag = tag
            contentDescription = label
            isFocusable = true
            isClickable = true
            isLongClickable = true
            // Focus fills the disc with the accent colour and darkens the icon
            background = AccentDisc()
            val image = android.widget.ImageView(context).apply { setImageResource(icon) }
            addView(image, FrameLayout.LayoutParams(Tiles.dp(context, 22), Tiles.dp(context, 22), Gravity.CENTER))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { view, focused ->
                name.text = if (focused) label else ""
                image.setColorFilter(if (focused) 0xFF15181E.toInt() else Color.WHITE)
                val scale = if (focused) 1.15f else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = Tiles.dp(context, 10) }
        }
    }

    private fun shadowed(value: String, sp: Float, color: Int) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
        maxLines = 1
    }

    private fun buildGrid() {
        gridDirty = false
        grid.removeAllViews()
        val favourites = prefs.favourites
        val width = gridTileWidth()
        // As many columns as fit the screen
        grid.columnCount = ((resources.displayMetrics.widthPixels - Tiles.dp(this, 96)) / (width + Tiles.dp(this, 2 * Tiles.MARGIN_DP))).coerceAtLeast(1)
        apps.forEach { app ->
            val star = if (app.packageName in favourites) "★ " else ""
            val tile = appTile(app, width, null, null)
            tile.contentDescription = star + app.label
            tile.setOnClickListener { closeAllApps(); launch(app) }
            tile.setOnLongClickListener { chooseRow(app); true }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                this.width = width
                height = Tiles.height(width)
                val m = Tiles.dp(this@HomeActivity, Tiles.MARGIN_DP)
                setMargins(m, m, m, m)
            })
        }
    }

    private fun openAllApps() {
        main.removeCallbacks(hideUi)
        if (gridDirty || grid.childCount == 0) buildGrid()
        allApps.visibility = View.VISIBLE
        dock.visibility = View.INVISIBLE
        grid.getChildAt(0)?.requestFocus()
    }

    private fun closeAllApps() {
        if (allApps.visibility != View.VISIBLE) return
        allApps.visibility = View.GONE
        // Frees the pictures of every app
        grid.removeAllViews()
        gridDirty = true
        dock.visibility = View.VISIBLE
        rowsBox.findViewWithTag<View>(ALL_APPS)?.requestFocus()
        scheduleHide()
    }

    /** From "all apps": put the app on a row (one choice per row, by title), or take it off the home screen. */
    private fun chooseRow(app: App) {
        val rows = prefs.appRows.orEmpty().toMutableList().ifEmpty { mutableListOf(AppRow("", 2, mutableListOf())) }
        val current = rows.indexOfFirst { app.packageName in it.apps }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        rows.indices.filter { it != current }.forEach { r ->
            val label = if (rows.size == 1) getString(R.string.add_home) else getString(R.string.add_to_row, rowName(rows[r], r))
            actions += label to { rows.forEach { it.apps.remove(app.packageName) }; rows[r].apps += app.packageName }
        }
        if (current >= 0) actions += getString(R.string.remove) to { rows[current].apps.remove(app.packageName) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                prefs.appRows = rows
                val onHome = rows.any { app.packageName in it.apps }
                Toast.makeText(this, getString(if (onHome) R.string.added else R.string.removed, app.label), Toast.LENGTH_SHORT).show()
                buildRows(ALL_APPS)
                gridDirty = true
            }
            .show()
    }

    private fun editFavourite(app: App) {
        val rows = prefs.appRows.orEmpty()
        val r = rows.indexOfFirst { app.packageName in it.apps }
        if (r < 0) return
        val apps = rows[r].apps
        val i = apps.indexOf(app.packageName)

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (i > 0) actions += getString(R.string.move_left) to { apps.add(i - 1, apps.removeAt(i)) }
        if (i < apps.size - 1) actions += getString(R.string.move_right) to { apps.add(i + 1, apps.removeAt(i)) }
        // Any other row, hidden ones included: the moved app brings its new row into view
        rows.indices.filter { it != r }.forEach { other ->
            actions += getString(R.string.move_to_row, rowName(rows[other], other)) to { apps.removeAt(i); rows[other].apps += app.packageName }
        }
        actions += getString(R.string.remove) to { apps.removeAt(i) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                prefs.appRows = rows
                gridDirty = true
                // Keeps the focus on the moved app
                buildRows(app.packageName)
            }
            .show()
    }

    private fun rowName(row: AppRow, index: Int) = row.title.ifEmpty { getString(R.string.row_n, index + 1) }

    private fun launch(app: App) {
        try {
            startActivity(app.intent())
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start ${app.packageName}", e)
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun tileParams(width: Int) = LinearLayout.LayoutParams(width, Tiles.height(width)).apply {
        val m = Tiles.dp(this@HomeActivity, Tiles.MARGIN_DP)
        setMargins(m, 0, m, 0)
    }

    companion object {
        private const val TAG = "Home"
        private const val HIDE_DELAY_MS = 30_000L
        private const val TICK_MS = 5_000L
        private const val ALL_APPS = "all_apps"
        private const val SETTINGS = "settings"
        private const val NO_FOCUS = ""
    }
}
