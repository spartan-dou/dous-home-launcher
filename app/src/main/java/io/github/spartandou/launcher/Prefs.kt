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

    /** Ordered package names of the apps on the home row, null until set (first start picks some). */
    var favourites: List<String>?
        get() = prefs.getString("favourites", null)?.split(',')?.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("favourites", value?.joinToString(",")).apply()

    /** Changes whenever the photo source changes, so the home screen reloads the photo list. */
    val sourceKey: String
        get() = "$serverUrl|$apiKey|${albumIds.sorted().joinToString(",")}|$memories"

    companion object {
        val INTERVALS = intArrayOf(10, 15, 30, 60, 120, 300)
    }
}
