package io.github.spartandou.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** One "continue watching" entry an app published to the Android TV home screen. */
data class Program(
    val id: Long,
    val packageName: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val background: String?,
    val intentUri: String?,
    // 0..1 when the app says how far it got, else null
    val progress: Float?,
)

/**
 * The Watch Next list of the TV provider (what "Continue watching" shows on the stock home screen).
 * Reading other apps' entries needs READ_TV_LISTINGS, which a launcher asks for once.
 */
object WatchNext {
    private val URI = Uri.parse("content://android.media.tv/watch_next_program")

    /** Most recently watched first; only from [sources] when it is not empty. Worker thread. */
    fun load(context: Context, sources: Collection<String>): List<Program> {
        val programs = mutableListOf<Pair<Long, Program>>()
        try {
            context.contentResolver.query(URI, null, null, null, null)?.use { c ->
                fun col(name: String) = c.getColumnIndex(name)
                val cId = col("_id"); val cPkg = col("package_name"); val cTitle = col("title")
                val cEpisode = col("episode_title"); val cSeason = col("season_display_number"); val cNumber = col("episode_display_number")
                val cPoster = col("poster_art_uri"); val cThumb = col("thumbnail_uri"); val cIntent = col("intent_uri")
                val cPosition = col("last_playback_position_millis"); val cDuration = col("duration_millis")
                val cEngaged = col("last_engagement_time_utc_millis"); val cBrowsable = col("browsable")
                fun text(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i)?.ifBlank { null } else null
                fun number(i: Int) = if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                while (c.moveToNext()) {
                    if (cBrowsable >= 0 && !c.isNull(cBrowsable) && c.getInt(cBrowsable) == 0) continue
                    val pkg = text(cPkg) ?: continue
                    if (sources.isNotEmpty() && pkg !in sources) continue
                    val episode = listOfNotNull(
                        text(cSeason)?.let { "S$it" }, text(cNumber)?.let { "É$it" }, text(cEpisode),
                    ).joinToString(" · ").ifEmpty { null }
                    val duration = number(cDuration)
                    val position = number(cPosition)
                    val thumb = text(cThumb)
                    val poster = text(cPoster)
                    programs += number(cEngaged) to Program(
                        id = number(cId),
                        packageName = pkg,
                        title = text(cTitle) ?: continue,
                        subtitle = episode,
                        // Landscape still for the tile, the biggest picture for the background
                        image = thumb ?: poster,
                        background = poster ?: thumb,
                        intentUri = text(cIntent),
                        progress = if (duration > 0 && position > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null,
                    )
                }
            }
        } catch (e: Exception) {
            // No permission yet, or no TV provider
            Log.w("WatchNext", "Not readable", e)
        }
        return programs.sortedByDescending { it.first }.map { it.second }
    }

    /** A row an app publishes to the Android TV home screen ("Recently added"...). */
    data class Channel(val key: String, val packageName: String, val name: String)

    private val CHANNELS = Uri.parse("content://android.media.tv/channel")
    private val PREVIEWS = Uri.parse("content://android.media.tv/preview_program")

    /** The apps' published channels. Keyed by app and name: ids change when an app republishes. Worker thread. */
    fun channels(context: Context): Map<Long, Channel> {
        val channels = mutableMapOf<Long, Channel>()
        try {
            context.contentResolver.query(CHANNELS, arrayOf("_id", "package_name", "display_name"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val pkg = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    channels[c.getLong(0)] = Channel("$pkg|$name", pkg, name)
                }
            }
        } catch (e: Exception) {
            Log.w("WatchNext", "Channels not readable", e)
        }
        return channels
    }

    /** The default choice: the "recently added" kind of channels. */
    fun isRecentlyAdded(channel: Channel) =
        Regex("ajout|added|nouveau|new|r[ée]cent", RegexOption.IGNORE_CASE).containsMatchIn(channel.name)

    /**
     * Programs of the chosen channels, taken in turn from each channel so one app does not fill the
     * list. [keys] null: the default choice. Worker thread.
     */
    fun news(context: Context, keys: Set<String>?): List<Pair<Program, Channel>> {
        val channels = channels(context).filterValues { if (keys == null) isRecentlyAdded(it) else it.key in keys }
        if (channels.isEmpty()) return emptyList()
        val byChannel = linkedMapOf<Long, MutableList<Program>>()
        try {
            context.contentResolver.query(PREVIEWS, null, null, null, null)?.use { c ->
                fun col(name: String) = c.getColumnIndex(name)
                val cId = col("_id"); val cChannel = col("channel_id"); val cTitle = col("title"); val cPoster = col("poster_art_uri")
                val cThumb = col("thumbnail_uri"); val cIntent = col("intent_uri"); val cEpisode = col("episode_title"); val cBrowsable = col("browsable")
                fun text(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i)?.ifBlank { null } else null
                while (c.moveToNext()) {
                    if (cBrowsable >= 0 && !c.isNull(cBrowsable) && c.getInt(cBrowsable) == 0) continue
                    val channelId = c.getLong(cChannel)
                    val channel = channels[channelId] ?: continue
                    byChannel.getOrPut(channelId) { mutableListOf() } += Program(
                        id = c.getLong(cId),
                        packageName = channel.packageName,
                        title = text(cTitle) ?: continue,
                        subtitle = text(cEpisode),
                        image = (text(cPoster) ?: text(cThumb))?.let { portrait(it) },
                        background = text(cPoster) ?: text(cThumb),
                        intentUri = text(cIntent),
                        progress = null,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("WatchNext", "Previews not readable", e)
        }
        val lists = byChannel.map { (id, list) -> channels.getValue(id) to list }
        val mixed = mutableListOf<Pair<Program, Channel>>()
        val longest = lists.maxOfOrNull { it.second.size } ?: 0
        for (i in 0 until longest) lists.forEach { (channel, list) -> list.getOrNull(i)?.let { mixed += it to channel } }
        return mixed
    }

    /**
     * Jellyfin (and Wholphin, which shows Jellyfin libraries) often publish a landscape still; the
     * same item's "Primary" image is its portrait poster. Asked at the panel's size, not full size.
     */
    private val JELLYFIN_IMAGE = Regex("""^(.*/Items/[^/]+/Images/)(Thumb|Backdrop|Banner|Primary)(/\d+)?(\?.*)?$""")

    fun portrait(uri: String): String =
        JELLYFIN_IMAGE.matchEntire(uri)?.let { "${it.groupValues[1]}Primary?maxHeight=600&quality=90" } ?: uri

    /** Packages that publish entries, for choosing the sources. Worker thread. */
    fun publishers(context: Context): List<String> = load(context, emptyList()).map { it.packageName }.distinct()

    /** The picture behind an entry (http(s) or content URI), raw bytes. Worker thread. */
    fun bytes(context: Context, uri: String): ByteArray? = try {
        if (uri.startsWith("http")) {
            val connection = URL(uri).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
            } finally {
                connection.disconnect()
            }
        } else {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }
    } catch (e: Exception) {
        Log.w("WatchNext", "Picture not loaded", e)
        null
    }

    /** Decodes at about [width] wide, at most: tile pictures stay small. */
    fun decode(bytes: ByteArray, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Opens the entry where the app said, else the app itself. */
    fun intent(context: Context, program: Program): Intent? {
        program.intentUri?.let { uri ->
            try {
                return Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (e: Exception) {
                Log.w("WatchNext", "Bad intent for ${program.title}", e)
            }
        }
        return context.packageManager.getLeanbackLaunchIntentForPackage(program.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(program.packageName)
    }
}
