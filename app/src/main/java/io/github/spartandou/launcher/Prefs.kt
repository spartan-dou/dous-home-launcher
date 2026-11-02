package io.github.spartandou.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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
     * The app rows, as many as wanted, in order; null until set (first start picks some apps).
     * Stored as JSON. The first version kept up to three rows in separate keys: read once from them.
     */
    var appRows: List<AppRow>?
        get() {
            val json = prefs.getString("app_rows", null) ?: return legacyRows()
            val array = JSONArray(json)
            return (0 until array.length()).map { AppRow.fromJson(array.getJSONObject(it)) }
        }
        set(value) = prefs.edit().putString("app_rows", value?.let { rows -> JSONArray().apply { rows.forEach { put(it.toJson()) } }.toString() }).apply()

    private fun legacyRows(): List<AppRow>? {
        val stored = prefs.getString("rows", null) ?: return null
        val legacyTitles = arrayOf("Favoris", "Rangée 2", "Rangée 3")
        return stored.split(';').mapIndexed { i, row ->
            AppRow(
                prefs.getString("row_title_$i", null) ?: legacyTitles.getOrElse(i) { "Rangée ${i + 1}" },
                // Sizes were small/medium/large: "extra small" now comes first
                prefs.getInt("row_size_$i", if (i == 0) 1 else 0) + 1,
                row.split(',').filter { it.isNotEmpty() }.toMutableList(),
            )
        }
    }

    val favourites: Set<String>
        get() = appRows.orEmpty().filter { !it.continueWatching }.flatMap { it.apps }.toSet()

    // Layout choices, all applied when the home screen comes back
    var dockOnTop: Boolean
        get() = prefs.getBoolean("dock_top", false)
        set(value) = prefs.edit().putBoolean("dock_top", value).apply()

    /** How many rows show at once; the others are reached by moving up or down past them. */
    var visibleRows: Int
        get() = prefs.getInt("row_count", 1).coerceIn(1, MAX_VISIBLE_ROWS)
        set(value) = prefs.edit().putInt("row_count", value).apply()

    /** Where the pinned row stays: above the scrolling rows, or below them. */
    var pinnedOnTop: Boolean
        get() = prefs.getBoolean("pinned_top", true)
        set(value) = prefs.edit().putBoolean("pinned_top", value).apply()

    var clockSize: Int
        get() = prefs.getInt("clock_size", 1).coerceIn(CLOCK_SIZES.indices)
        set(value) = prefs.edit().putInt("clock_size", value).apply()

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
        get() = "$dockOnTop|$visibleRows|${prefs.getString("app_rows", "")}|$pinnedOnTop|$clockPosition|$clockSize|$accent|" +
            "$showWeather|$showStatus"

    companion object {
        val INTERVALS = intArrayOf(10, 15, 30, 60, 120, 300)
        const val MAX_VISIBLE_ROWS = 3
        // Right after the fixed colours in the list of choices
        const val ACCENT_AUTO = 6

        // Extra small, small, medium, large, in dp (16:9 banners)
        val TILE_WIDTHS = intArrayOf(112, 136, 168, 208)

        // Time text size in sp: small, medium, large
        val CLOCK_SIZES = intArrayOf(56, 76, 96)

        const val CLOCK_LEFT = 0
        const val CLOCK_RIGHT = 1
        const val CLOCK_CENTER = 2
        const val CLOCK_HIDDEN = 3

        // White, ice blue, lavender, pink, amber, mint: soft tones that stay readable on any photo
        val ACCENTS = intArrayOf(0xFFFFFFFF.toInt(), 0xFF8AB4F8.toInt(), 0xFFB39DDB.toInt(),
            0xFFF48FB1.toInt(), 0xFFFFCA80.toInt(), 0xFF80CBC4.toInt())
    }
}

/**
 * One row of the home screen: its title (empty: none), tile size and apps in order. A "continue
 * watching" row lists programs instead, and [apps] are then the apps they may come from (empty:
 * all of them).
 */
data class AppRow(
    var title: String,
    var size: Int,
    val apps: MutableList<String>,
    val continueWatching: Boolean = false,
    // Always on screen when two rows or more are visible; the others scroll past it
    var pinned: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("size", size).put("apps", JSONArray(apps))
        .apply { if (continueWatching) put("type", TYPE_CONTINUE); if (pinned) put("pinned", true) }

    companion object {
        private const val TYPE_CONTINUE = "continue"

        fun fromJson(o: JSONObject): AppRow {
            val apps = o.optJSONArray("apps")
            return AppRow(
                o.optString("title"),
                o.optInt("size", 2).coerceIn(Prefs.TILE_WIDTHS.indices),
                (0 until (apps?.length() ?: 0)).map { apps!!.getString(it) }.toMutableList(),
                o.optString("type") == TYPE_CONTINUE,
                o.optBoolean("pinned"),
            )
        }
    }
}
