package io.github.spartandou.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private lateinit var rowsBox: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var topScrim: View
    private lateinit var bottomScrim: View
    private lateinit var clockBox: LinearLayout
    private lateinit var captionBox: View
    private lateinit var focusedLabel: TextView
    private lateinit var allApps: View
    private lateinit var grid: GridLayout

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var apps: List<App> = emptyList()
    private var appsDirty = true
    private var gridDirty = true
    private var uiHidden = false
    private var layoutKey = ""

    // Pictures of the home row tiles, at the current tile size; the "all apps" ones are never kept
    private val rowImages = HashMap<String, TileImage>()

    private val hideUi = Runnable { setUiHidden(true) }

    // The scrim that fades with the apps: the one on their side of the screen
    private val dockScrim: View
        get() = if (prefs.dockOnTop) topScrim else bottomScrim

    private val tileWidth: Int
        get() = Tiles.dp(this, Prefs.TILE_WIDTHS[prefs.tileSize.coerceIn(Prefs.TILE_WIDTHS.indices)])

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
        rowsBox = findViewById(R.id.rows)
        dock = findViewById(R.id.dock)
        topScrim = findViewById(R.id.top_scrim)
        bottomScrim = findViewById(R.id.bottom_scrim)
        clockBox = findViewById(R.id.clock_box)
        captionBox = findViewById(R.id.caption_box)
        focusedLabel = findViewById(R.id.focused_label)
        allApps = findViewById(R.id.all_apps)
        grid = findViewById(R.id.grid)

        val slides = findViewById<FrameLayout>(R.id.slides)
        val captionGlass = findViewById<GlassView>(R.id.caption_glass).apply { cornerRadius = Tiles.dp(context, 18).toFloat() }
        val allAppsGlass = findViewById<GlassView>(R.id.all_apps_glass).apply { cornerRadius = 0f; tint = 0x99000000.toInt() }
        wall = PhotoWall(this, listOf(slides.getChildAt(0) as FrameLayout, slides.getChildAt(1) as FrameLayout),
            captionBox, findViewById(R.id.caption), prefs,
            listOf(findViewById(R.id.dock_glass), captionGlass, allAppsGlass))

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
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(hideUi)
    }

    override fun onStop() {
        super.onStop()
        wall.stop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
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
        }
        return super.dispatchKeyEvent(event)
    }

    /** Apps at the top or bottom, clock left / right / centre / hidden (on the other side of the apps). */
    private fun applyLayout() {
        val top = prefs.dockOnTop
        val m = Tiles.dp(this, 28)
        (dock.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (top) Gravity.TOP else Gravity.BOTTOM
            topMargin = if (top) m else 0
            bottomMargin = if (top) 0 else m
        }
        // The focused app name sits on the outer side of the rows
        dock.removeView(focusedLabel)
        dock.addView(focusedLabel, if (top) dock.childCount else 0)
        (focusedLabel.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = if (top) Tiles.dp(this@HomeActivity, 8) else 0
            bottomMargin = if (top) 0 else Tiles.dp(this@HomeActivity, 8)
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
        (clockBox.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = vertical or horizontal
            topMargin = if (top) 0 else Tiles.dp(this@HomeActivity, 32)
            bottomMargin = if (top) Tiles.dp(this@HomeActivity, 40) else 0
        }
        // The photo caption takes the corner the clock leaves free
        (captionBox.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = vertical or if (clock == Prefs.CLOCK_RIGHT) Gravity.START else Gravity.END
            topMargin = if (top) 0 else Tiles.dp(this@HomeActivity, 44)
            bottomMargin = if (top) Tiles.dp(this@HomeActivity, 44) else 0
            marginStart = Tiles.dp(this@HomeActivity, 56)
            marginEnd = Tiles.dp(this@HomeActivity, 56)
        }
        topScrim.alpha = 1f
        bottomScrim.alpha = 1f
        findViewById<View>(android.R.id.content).requestLayout()
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
                if (prefs.rows == null) {
                    // First start: a few apps to begin with, the user picks the rest from "all apps"
                    prefs.rows = listOf(loaded.filter { it.packageName != packageName }.take(6).map { it.packageName })
                }
                buildRows()
                if (allApps.visibility == View.VISIBLE) buildGrid()
            }
        }
    }

    private fun firstTile(): View? = ((rowsBox.getChildAt(0) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout)?.getChildAt(0)

    private fun rowTiles(index: Int): LinearLayout? = (rowsBox.getChildAt(index) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout

    /** One scroller per row; "All apps" closes the last row. */
    private fun buildRows(focusPackage: String? = null) {
        val focused = focusPackage ?: rowsBox.findFocus()?.tag as? String
        rowsBox.removeAllViews()
        val byPackage = apps.associateBy { it.packageName }
        val rows = prefs.rows.orEmpty().map { row -> row.mapNotNull { byPackage[it] } }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(emptyList()) }
        var toFocus: View? = null
        rows.forEachIndexed { rowIndex, row ->
            val line = newRow()
            row.forEach { app ->
                val tile = appTile(app, rowImages)
                tile.tag = app.packageName
                tile.setOnClickListener { launch(app) }
                tile.setOnLongClickListener { editFavourite(app); true }
                line.addView(tile, tileParams())
                if (app.packageName == focused) toFocus = tile
            }
            if (rowIndex == rows.size - 1) {
                val allTile = Tiles.create(this, getString(R.string.all_apps), prefs.accentColor) {
                    if (it) focusedLabel.text = getString(R.string.all_apps)
                }
                allTile.tag = ALL_APPS
                allTile.setOnClickListener { openAllApps() }
                Tiles.fill(this, allTile, getString(R.string.all_apps), null, getDrawable(R.drawable.ic_apps))
                line.addView(allTile, tileParams())
                if (focused == ALL_APPS) toFocus = allTile
            }
        }
        (toFocus ?: firstTile())?.requestFocus()
    }

    /** A tile whose picture is rendered on the worker thread, or taken from [cache] when it has it. */
    private fun appTile(app: App, cache: MutableMap<String, TileImage>?): android.widget.FrameLayout {
        val tile = Tiles.create(this, app.label, prefs.accentColor) { if (it) focusedLabel.text = app.label }
        val cached = cache?.get(app.packageName)
        if (cached != null) {
            Tiles.fill(this, tile, app.label, cached.banner, cached.icon)
        } else {
            val width = tileWidth
            worker.execute {
                val image = Apps.tileImage(this, app, width)
                main.post {
                    cache?.put(app.packageName, image)
                    Tiles.fill(this, tile, app.label, image.banner, image.icon)
                }
            }
        }
        return tile
    }

    private fun newRow(): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            val h = Tiles.dp(context, 14)
            val v = Tiles.dp(context, 8)
            setPadding(h, v, h, v)
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setFadingEdgeLength(0)
            // Wrap height: the default params of a scroller child would fill the whole screen
            addView(line, FrameLayout.LayoutParams(-2, -2))
        }
        rowsBox.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        return line
    }

    private fun buildGrid() {
        gridDirty = false
        grid.removeAllViews()
        val favourites = prefs.favourites
        val width = tileWidth
        // As many columns as fit the screen
        grid.columnCount = ((resources.displayMetrics.widthPixels - Tiles.dp(this, 96)) / (width + Tiles.dp(this, 2 * Tiles.MARGIN_DP))).coerceAtLeast(1)
        apps.forEach { app ->
            val star = if (app.packageName in favourites) "★ " else ""
            val tile = appTile(app, null)
            tile.contentDescription = star + app.label
            tile.setOnClickListener { closeAllApps(); launch(app) }
            tile.setOnLongClickListener { toggleFavourite(app); true }
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

    private fun toggleFavourite(app: App) {
        val rows = prefs.rows.orEmpty().map { it.toMutableList() }.toMutableList()
        val added = rows.none { it.remove(app.packageName) }
        if (added) {
            // New favourites go to the end of the first row
            if (rows.isEmpty()) rows += mutableListOf<String>()
            rows[0] += app.packageName
        }
        prefs.rows = rows
        Toast.makeText(this, getString(if (added) R.string.added else R.string.removed, app.label), Toast.LENGTH_SHORT).show()
        buildRows(ALL_APPS)
        gridDirty = true
    }

    private fun editFavourite(app: App) {
        val rows = prefs.rows.orEmpty().map { it.toMutableList() }.toMutableList()
        val r = rows.indexOfFirst { app.packageName in it }
        if (r < 0) return
        val i = rows[r].indexOf(app.packageName)

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (i > 0) actions += getString(R.string.move_left) to { rows[r].add(i - 1, rows[r].removeAt(i)) }
        if (i < rows[r].size - 1) actions += getString(R.string.move_right) to { rows[r].add(i + 1, rows[r].removeAt(i)) }
        if (r > 0) actions += getString(R.string.move_up) to { rows[r].removeAt(i); rows[r - 1] += app.packageName }
        // Moving down past the last row starts a new one, up to the maximum
        if (r < rows.size - 1 || (rows.size < Prefs.MAX_ROWS && rows[r].size > 1)) {
            actions += getString(R.string.move_down) to {
                rows[r].removeAt(i)
                if (r + 1 == rows.size) rows += mutableListOf<String>()
                rows[r + 1] += app.packageName
            }
        }
        actions += getString(R.string.remove) to { rows[r].removeAt(i) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                prefs.rows = rows
                gridDirty = true
                // Keeps the focus on the moved app
                buildRows(app.packageName)
            }
            .show()
    }

    private fun launch(app: App) {
        try {
            startActivity(app.intent())
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start ${app.packageName}", e)
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun tileParams() = tileWidth.let { w ->
        LinearLayout.LayoutParams(w, Tiles.height(w)).apply {
            val m = Tiles.dp(this@HomeActivity, Tiles.MARGIN_DP)
            setMargins(m, 0, m, 0)
        }
    }

    companion object {
        private const val TAG = "Home"
        private const val HIDE_DELAY_MS = 30_000L
        private const val ALL_APPS = "all_apps"
    }
}
