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
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var wall: PhotoWall
    private lateinit var row: LinearLayout
    private lateinit var dock: View
    private lateinit var bottomScrim: View
    private lateinit var focusedLabel: TextView
    private lateinit var allApps: View
    private lateinit var grid: GridLayout

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var apps: List<App> = emptyList()
    private var appsDirty = true
    private var gridDirty = true
    private var uiHidden = false

    private val hideUi = Runnable { setUiHidden(true) }

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
        row = findViewById(R.id.row)
        dock = findViewById(R.id.dock)
        bottomScrim = findViewById(R.id.bottom_scrim)
        focusedLabel = findViewById(R.id.focused_label)
        allApps = findViewById(R.id.all_apps)
        grid = findViewById(R.id.grid)

        val slides = findViewById<FrameLayout>(R.id.slides)
        val captionGlass = findViewById<GlassView>(R.id.caption_glass).apply { cornerRadius = Tiles.dp(context, 18).toFloat() }
        val allAppsGlass = findViewById<GlassView>(R.id.all_apps_glass).apply { cornerRadius = 0f; tint = 0x99000000.toInt() }
        wall = PhotoWall(this, listOf(slides.getChildAt(0) as FrameLayout, slides.getChildAt(1) as FrameLayout),
            findViewById(R.id.caption_box), findViewById(R.id.caption), prefs,
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

    // Home pressed while already home: back to the first app of the row
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeAllApps()
        setUiHidden(false)
        row.getChildAt(0)?.requestFocus()
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

    private fun scheduleHide() {
        main.removeCallbacks(hideUi)
        if (prefs.autoHide && allApps.visibility != View.VISIBLE) main.postDelayed(hideUi, HIDE_DELAY_MS)
    }

    private fun setUiHidden(hidden: Boolean) {
        uiHidden = hidden
        val alpha = if (hidden) 0f else 1f
        dock.animate().alpha(alpha).setDuration(if (hidden) 800 else 200).start()
        bottomScrim.animate().alpha(alpha).setDuration(if (hidden) 800 else 200).start()
        if (!hidden) scheduleHide()
    }

    private fun loadApps() {
        appsDirty = false
        worker.execute {
            val loaded = Apps.load(this)
            main.post {
                apps = loaded
                gridDirty = true
                if (prefs.favourites == null) {
                    // First start: a few apps to begin with, the user picks the rest from "all apps"
                    prefs.favourites = loaded.filter { it.packageName != packageName }.take(6).map { it.packageName }
                }
                buildRow()
                if (allApps.visibility == View.VISIBLE) buildGrid()
            }
        }
    }

    private fun buildRow() {
        val focusedIndex = (0 until row.childCount).firstOrNull { row.getChildAt(it).hasFocus() }
        row.removeAllViews()
        val byPackage = apps.associateBy { it.packageName }
        prefs.favourites.orEmpty().mapNotNull { byPackage[it] }.forEach { app ->
            val tile = Tiles.create(this, app.label, app.banner, app.icon) { if (it) focusedLabel.text = app.label }
            tile.setOnClickListener { launch(app) }
            tile.setOnLongClickListener { editFavourite(app); true }
            row.addView(tile, tileParams())
        }
        val allTile = Tiles.create(this, getString(R.string.all_apps), null, getDrawable(R.drawable.ic_apps)) {
            if (it) focusedLabel.text = getString(R.string.all_apps)
        }
        allTile.setOnClickListener { openAllApps() }
        row.addView(allTile, tileParams())
        (row.getChildAt(focusedIndex ?: 0) ?: row.getChildAt(0)).requestFocus()
    }

    private fun buildGrid() {
        gridDirty = false
        grid.removeAllViews()
        val favourites = prefs.favourites.orEmpty().toSet()
        apps.forEach { app ->
            val star = if (app.packageName in favourites) "★ " else ""
            val tile = Tiles.create(this, app.label, app.banner, app.icon) { if (it) focusedLabel.text = app.label }
            tile.contentDescription = star + app.label
            tile.setOnClickListener { closeAllApps(); launch(app) }
            tile.setOnLongClickListener { toggleFavourite(app); true }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = Tiles.dp(this@HomeActivity, Tiles.WIDTH_DP)
                height = Tiles.dp(this@HomeActivity, Tiles.HEIGHT_DP)
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
        dock.visibility = View.VISIBLE
        row.getChildAt(row.childCount - 1)?.requestFocus()
        scheduleHide()
    }

    private fun toggleFavourite(app: App) {
        val favourites = prefs.favourites.orEmpty().toMutableList()
        val added = favourites.remove(app.packageName).not()
        if (added) favourites += app.packageName
        prefs.favourites = favourites
        Toast.makeText(this, getString(if (added) R.string.added else R.string.removed, app.label), Toast.LENGTH_SHORT).show()
        buildRow()
        gridDirty = true
    }

    private fun editFavourite(app: App) {
        val actions = arrayOf(getString(R.string.move_left), getString(R.string.move_right), getString(R.string.remove))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions) { _, which ->
                val favourites = prefs.favourites.orEmpty().toMutableList()
                val index = favourites.indexOf(app.packageName)
                when (which) {
                    0 -> if (index > 0) favourites.add(index - 1, favourites.removeAt(index))
                    1 -> if (index in 0 until favourites.size - 1) favourites.add(index + 1, favourites.removeAt(index))
                    2 -> favourites.remove(app.packageName)
                }
                prefs.favourites = favourites
                gridDirty = true
                buildRow()
                // Keeps the focus on the moved app
                val newIndex = favourites.indexOf(app.packageName)
                if (newIndex >= 0) row.getChildAt(newIndex)?.requestFocus()
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

    private fun tileParams() = LinearLayout.LayoutParams(Tiles.dp(this, Tiles.WIDTH_DP), Tiles.dp(this, Tiles.HEIGHT_DP)).apply {
        val m = Tiles.dp(this@HomeActivity, Tiles.MARGIN_DP)
        setMargins(m, 0, m, 0)
    }

    companion object {
        private const val TAG = "Home"
        private const val HIDE_DELAY_MS = 30_000L
    }
}
