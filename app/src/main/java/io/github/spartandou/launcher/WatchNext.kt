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
