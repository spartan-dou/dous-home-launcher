package io.github.spartandou.launcher

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Immich server, e.g. https://immich.example.com */
    var serverUrl: String
        get() = prefs.getString("server_url", "")!!
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    /** Immich API key, read-only permissions (asset.read, asset.view, album.read, memory.read). */
    var apiKey: String
        get() = prefs.getString("api_key", "")!!
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var albumIds: Set<String>
        get() = prefs.getStringSet("album_ids", emptySet())!!
        set(value) = prefs.edit().putStringSet("album_ids", value).apply()

    /** Today's "on this day" memories mixed into the albums. */
    var memories: Boolean
        get() = prefs.getBoolean("memories", true)
        set(value) = prefs.edit().putBoolean("memories", value).apply()

    var intervalSeconds: Int
        get() = prefs.getInt("interval", 30)
        set(value) = prefs.edit().putInt("interval", value).apply()

    var showDate: Boolean
        get() = prefs.getBoolean("show_date", true)
        set(value) = prefs.edit().putBoolean("show_date", value).apply()

    var showPlace: Boolean
        get() = prefs.getBoolean("show_place", true)
        set(value) = prefs.edit().putBoolean("show_place", value).apply()

    // Only fades the apps away after a while: the photo and the clock stay
    var autoHide: Boolean
        get() = prefs.getBoolean("auto_hide", true)
        set(value) = prefs.edit().putBoolean("auto_hide", value).apply()

    /**
     * Apps of the home rows, each an ordered list of package names (a row may be empty); null until
     * set (first start picks some). Always read through [homeRows], which fits it to [rowCount].
     */
    var rows: List<List<String>>?
        get() = prefs.getString("rows", null)?.split(';')?.map { row -> row.split(',').filter { it.isNotEmpty() } }
        set(value) = prefs.edit().putString("rows", value?.joinToString(";") { it.joinToString(",") }).apply()

    /** Exactly [rowCount] rows: the apps of rows beyond it join the last one. */
    val homeRows: List<List<String>>
        get() {
            val stored = rows.orEmpty()
            val count = rowCount
            return List(count) { i ->
                if (i < count - 1) stored.getOrElse(i) { emptyList() } else stored.drop(i).flatten()
            }
        }

    val favourites: Set<String>
        get() = rows.orEmpty().flatten().toSet()

    // Layout choices, all applied when the home screen comes back
    var dockOnTop: Boolean
        get() = prefs.getBoolean("dock_top", false)
        set(value) = prefs.edit().putBoolean("dock_top", value).apply()

    var rowCount: Int
        get() = prefs.getInt("row_count", 1).coerceIn(1, MAX_ROWS)
        set(value) = prefs.edit().putInt("row_count", value).apply()

    /** Size of each row: 0 small, 1 medium, 2 large. */
    fun rowSize(row: Int): Int = prefs.getInt("row_size_$row", if (row == 0) 1 else 0).coerceIn(TILE_WIDTHS.indices)

    fun setRowSize(row: Int, size: Int) = prefs.edit().putInt("row_size_$row", size).apply()

    var clockPosition: Int
        get() = prefs.getInt("clock_position", CLOCK_LEFT)
        set(value) = prefs.edit().putInt("clock_position", value).apply()

    /** Index in [ACCENTS], or [ACCENT_AUTO] for a colour taken from each photo. */
    var accent: Int
        get() = prefs.getInt("accent", ACCENT_AUTO)
        set(value) = prefs.edit().putInt("accent", value).apply()

    val accentColor: Int
        get() = ACCENTS[accent.coerceIn(ACCENTS.indices)]

    var showWeather: Boolean
        get() = prefs.getBoolean("show_weather", true)
        set(value) = prefs.edit().putBoolean("show_weather", value).apply()

    /** Weather place chosen on the phone; empty means found from the IP address. */
    var weatherCity: String
        get() = prefs.getString("weather_city", "")!!
        set(value) = prefs.edit().putString("weather_city", value).apply()

    var showStatus: Boolean
        get() = prefs.getBoolean("show_status", true)
        set(value) = prefs.edit().putBoolean("show_status", value).apply()

    /** Changes whenever the photo source changes, so the home screen reloads the photo list. */
    val sourceKey: String
        get() = "$serverUrl|$apiKey|${albumIds.sorted().joinToString(",")}|$memories"

    /** Changes whenever the layout changes, so the home screen rebuilds it. */
    val layoutKey: String
        get() = "$dockOnTop|$rowCount|${(0 until MAX_ROWS).joinToString { rowSize(it).toString() }}|$clockPosition|$accent|" +
            "$showWeather|$showStatus"

    companion object {
        val INTERVALS = intArrayOf(10, 15, 30, 60, 120, 300)
        const val MAX_ROWS = 3
        // Right after the fixed colours, so the settings button cycles through it too
        const val ACCENT_AUTO = 6

        // Small, medium, large, in dp (16:9 banners)
        val TILE_WIDTHS = intArrayOf(136, 168, 208)

        const val CLOCK_LEFT = 0
        const val CLOCK_RIGHT = 1
        const val CLOCK_CENTER = 2
        const val CLOCK_HIDDEN = 3

        // White, ice blue, lavender, pink, amber, mint: soft tones that stay readable on any photo
        val ACCENTS = intArrayOf(0xFFFFFFFF.toInt(), 0xFF8AB4F8.toInt(), 0xFFB39DDB.toInt(),
            0xFFF48FB1.toInt(), 0xFFFFCA80.toInt(), 0xFF80CBC4.toInt())
    }
}
