package io.github.spartandou.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Immich server, e.g. https://immich.example.com */
    var serverUrl: String
        get() = prefs.getString("server_url", "")!!
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    /** Immich API key, read-only permissions (asset.read, asset.view, album.read, memory.read). */
    var apiKey: String
        get() {
            val encrypted = prefs.getString("api_key_enc", null)
            if (encrypted == null) {
                // Stored in clear by earlier versions: encrypted on first read
                val plain = prefs.getString("api_key", "")!!
                if (plain.isNotEmpty()) apiKey = plain
                return plain
            }
            decrypted?.takeIf { it.first == encrypted }?.let { return it.second }
            val plain = Secret.decrypt(encrypted).orEmpty()
            decrypted = encrypted to plain
            return plain
        }
        set(value) {
            val plain = value.trim()
            val encrypted = if (plain.isEmpty()) null else Secret.encrypt(plain)
            decrypted = encrypted?.let { it to plain }
            // Without a working keystore the key stays in clear, as before, rather than being lost
            prefs.edit()
                .putString("api_key_enc", encrypted)
                .putString("api_key", if (encrypted == null) plain else null)
                .apply()
        }

    /** Tablet: the photo frame comes back whenever the screen goes off on the charger. */
    var keepFrame: Boolean
        get() = prefs.getBoolean("keep_frame", false)
        set(value) = prefs.edit().putBoolean("keep_frame", value).apply()

    /** Newer releases are downloaded and installed without being asked for. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(value) = prefs.edit().putBoolean("auto_update", value).apply()

    /** When the releases were last looked at (ms), to do it once a day at most. */
    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    var albumIds: Set<String>
        get() = prefs.getStringSet("album_ids", emptySet())!!
        set(value) = prefs.edit().putStringSet("album_ids", value).apply()

    /** Today's "on this day" memories mixed into the albums. */
    var memories: Boolean
        get() = prefs.getBoolean("memories", true)
        set(value) = prefs.edit().putBoolean("memories", value).apply()

    /** Only today's memories, when there are some; wins over [memories]. */
    var memoriesOnly: Boolean
        get() = prefs.getBoolean("memories_only", false)
        set(value) = prefs.edit().putBoolean("memories_only", value).apply()

    /** Key Home Assistant sends to drive the photo frame, made on first use. Short: it is typed by hand. */
    val frameKey: String
        get() = prefs.getString("frame_key", null) ?: SecureRandom().let { random ->
            String(CharArray(KEY_LENGTH) { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] })
        }.also { prefs.edit().putString("frame_key", it).apply() }

    /** Tells this device from another one in Home Assistant, whatever its address becomes. */
    val deviceId: String
        get() = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

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
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { AppRow.fromJson(array.getJSONObject(it)) }
            } catch (e: Exception) {
                // Damaged: as on a first start, rather than a home screen that never opens
                null
            }
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
        get() = appRows.orEmpty().filter { it.isApps }.flatMap { it.apps }.toSet()

    /** Apps left out of "all apps" (they stay on the rows they were put on). */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet("hidden_apps", emptySet())!!
        set(value) = prefs.edit().putStringSet("hidden_apps", value).apply()

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

    /**
     * Remote buttons that open an app, as stored: the key service compares this text to know when
     * to read [keyMappings] again. Earlier versions only had the Netflix button, on a short press.
     */
    val keyMappingsRaw: String
        get() = prefs.getString("key_mappings", null)
            ?: prefs.getString("netflix_key_app", "")!!.takeIf { it.isNotEmpty() }
                ?.let { JSONArray().put(KeyMapping(KeyMapping.NETFLIX, KeyMapping.SHORT, it).toJson()).toString() }
            ?: "[]"

    var keyMappings: List<KeyMapping>
        get() = try {
            val array = JSONArray(keyMappingsRaw)
            (0 until array.length()).map { KeyMapping.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) = prefs.edit().putString("key_mappings", JSONArray().apply { value.forEach { put(it.toJson()) } }.toString()).apply()

    /** Everything but the secrets, to carry the set-up over a reinstall or to another device. */
    fun exportJson(): JSONObject {
        val settings = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key !in SECRETS && value != null) settings.put(key, if (value is Set<*>) JSONArray(value) else value)
        }
        return JSONObject().put(EXPORT_MARK, 1).put("settings", settings)
    }

    /**
     * Takes over an export. Every setting is read back before it is kept: a value of the wrong
     * kind would otherwise crash the home screen at each start. Throws, changing nothing, when the
     * text is not an export or does not hold up.
     */
    fun importJson(json: JSONObject) {
        require(json.has(EXPORT_MARK)) { "ce n'est pas une configuration exportée" }
        val settings = json.getJSONObject("settings")
        val before = prefs.all
        val editor = prefs.edit()
        settings.keys().forEach { key ->
            if (key in SECRETS) return@forEach
            when (val value = settings.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is JSONArray -> editor.putStringSet(key, (0 until value.length()).mapTo(HashSet()) { value.getString(it) })
            }
        }
        editor.commit()
        try {
            readAll()
        } catch (e: Exception) {
            restore(before)
            throw IllegalArgumentException("un réglage n'a pas le bon format")
        }
    }

    // Every getter once: a stored value of the wrong kind throws here rather than on the home screen
    private fun readAll() {
        layoutKey; sourceKey; intervalSeconds; showDate; showPlace; autoHide; drift; weatherCity
        newsChannels; hiddenApps; keyMappings; visibleRows; favourites
    }

    private fun restore(values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.commit()
    }

    /** The "new" panel on the right: one poster at a time from the chosen channels. */
    var showNews: Boolean
        get() = prefs.getBoolean("show_news", true)
        set(value) = prefs.edit().putBoolean("show_news", value).apply()

    /** Chosen channels ("package|name"); null until chosen: the "recently added" ones. */
    var newsChannels: Set<String>?
        get() = prefs.getStringSet("news_channels", null)
        set(value) = prefs.edit().putStringSet("news_channels", value).apply()

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

    /** One row at a time, the focused app large in the middle, its neighbours smaller on each side. */
    var carousel: Boolean
        get() = prefs.getBoolean("carousel", false)
        set(value) = prefs.edit().putBoolean("carousel", value).apply()

    /** A very slow zoom on each photo (GPU only: the photo is not decoded again). */
    var drift: Boolean
        get() = prefs.getBoolean("drift", true)
        set(value) = prefs.edit().putBoolean("drift", value).apply()

    /** Changes whenever the photo source changes, so the home screen reloads the photo list. */
    val sourceKey: String
        get() = sha256("$serverUrl|$apiKey|${albumIds.sorted().joinToString(",")}|$memories|$memoriesOnly")

    /** Changes whenever the layout changes, so the home screen rebuilds it. */
    val layoutKey: String
        get() = "$dockOnTop|$visibleRows|${prefs.getString("app_rows", "")}|$pinnedOnTop|$clockPosition|$clockSize|$accent|$showNews|" +
            "$showWeather|$showStatus|$carousel"

    // A digest: this key is written next to the photo list, the API key must not be
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        // The decrypted API key with what it was read from: the keystore is not asked at every photo
        @Volatile
        private var decrypted: Pair<String, String>? = null

        val INTERVALS = intArrayOf(10, 15, 30, 60, 120, 300)
        const val MAX_VISIBLE_ROWS = 3
        private const val EXPORT_MARK = "dous_home_launcher"
        // Never exported nor imported: they belong to this device
        private val SECRETS = setOf("api_key", "api_key_enc", "frame_key", "device_id")
        // No 0/O, 1/I/L: read on a screen, typed on another
        private const val KEY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val KEY_LENGTH = 8
        // Right after the fixed colours in the list of choices
        const val ACCENT_AUTO = 6

        // Extra small, small, medium, large, in dp (16:9 banners)
        val TILE_WIDTHS = intArrayOf(112, 136, 168, 208)

        // Time text size in sp: small, medium, large
        // Width of the carousel's focused tile, in dp; its neighbours are scaled down from it
        const val CAROUSEL_WIDTH = 210
        // Compact clock: the date and weather sit beside the time
        val CLOCK_SIZES = intArrayOf(48, 60, 76)

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
 * all of them). An "inputs" row lists what the TV can show from elsewhere (HDMI, tuners).
 */
data class AppRow(
    var title: String,
    var size: Int,
    val apps: MutableList<String>,
    val continueWatching: Boolean = false,
    // Always on screen when two rows or more are visible; the others scroll past it
    var pinned: Boolean = false,
    val inputs: Boolean = false,
) {
    /** A row of apps, the only kind an app can be put on. */
    val isApps: Boolean
        get() = !continueWatching && !inputs

    fun toJson(): JSONObject = JSONObject().put("title", title).put("size", size).put("apps", JSONArray(apps))
        .apply {
            if (continueWatching) put("type", TYPE_CONTINUE)
            if (inputs) put("type", TYPE_INPUTS)
            if (pinned) put("pinned", true)
        }

    companion object {
        private const val TYPE_CONTINUE = "continue"
        private const val TYPE_INPUTS = "inputs"

        fun fromJson(o: JSONObject): AppRow {
            val apps = o.optJSONArray("apps")
            return AppRow(
                o.optString("title"),
                o.optInt("size", 2).coerceIn(Prefs.TILE_WIDTHS.indices),
                (0 until (apps?.length() ?: 0)).map { apps!!.getString(it) }.toMutableList(),
                o.optString("type") == TYPE_CONTINUE,
                o.optBoolean("pinned"),
                o.optString("type") == TYPE_INPUTS,
            )
        }
    }
}

/** A remote button that opens an app: on a short press, a long one, or two in a row. */
data class KeyMapping(val keyCode: Int, val gesture: Int, val app: String) {
    fun toJson(): JSONObject = JSONObject().put("key", keyCode).put("gesture", gesture).put("app", app)

    companion object {
        const val SHORT = 0
        const val LONG = 1
        const val DOUBLE = 2
        // The Shield remote's Netflix button
        const val NETFLIX = android.view.KeyEvent.KEYCODE_BUTTON_12

        fun fromJson(o: JSONObject) = KeyMapping(o.getInt("key"), o.optInt("gesture").coerceIn(SHORT, DOUBLE), o.getString("app"))
    }
}
