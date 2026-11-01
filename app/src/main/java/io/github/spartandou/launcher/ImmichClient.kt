package io.github.spartandou.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Photo(val id: String, val portrait: Boolean, val place: String?, val date: String?) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", id).put("p", portrait).put("l", place ?: "").put("d", date ?: "")

    companion object {
        fun fromJson(o: JSONObject) =
            Photo(o.getString("i"), o.optBoolean("p"), o.optString("l").ifEmpty { null }, o.optString("d").ifEmpty { null })
    }
}

data class Album(val id: String, val name: String, val count: Int)

/**
 * Immich API with an API key. Read-only permissions are enough:
 * album.read (album list), asset.read (album content), asset.view (previews), memory.read (memories).
 */
class ImmichClient(private val baseUrl: String, private val apiKey: String) {

    fun albums(): List<Album> = (request("GET", "/api/albums") as JSONArray).objects()
        .mapNotNull { a -> a.text("id")?.let { Album(it, a.text("albumName") ?: "?", a.optInt("assetCount")) } }
        .sortedBy { it.name.lowercase() }

    fun albumPhotos(albumId: String, max: Int): List<Photo> {
        val photos = mutableListOf<Photo>()
        var page: Int? = 1
        while (page != null && photos.size < max) {
            val body = JSONObject()
                .put("albumIds", JSONArray().put(albumId))
                .put("type", "IMAGE")
                .put("withExif", true)
                .put("size", PAGE_SIZE)
                .put("page", page)
            val assets = (request("POST", "/api/search/metadata", body.toString()) as JSONObject).optJSONObject("assets")
            assets?.optJSONArray("items").objects().mapNotNullTo(photos) { it.toPhoto() }
            page = assets.text("nextPage")?.toIntOrNull()
        }
        return photos
    }

    /** Today's "on this day" memories. */
    fun todaysMemories(): List<Photo> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return (request("GET", "/api/memories?for=$today") as JSONArray).objects()
            .flatMap { it.optJSONArray("assets").objects() }
            .filter { it.optString("type") == "IMAGE" }
            .mapNotNull { it.toPhoto() }
    }

    fun imageUrl(photoId: String) = "$baseUrl/api/assets/$photoId/thumbnail?size=preview"

    fun download(url: String): ByteArray {
        val connection = open(url)
        try {
            connection.readTimeout = 30_000
            check(connection, url)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toPhoto(): Photo? {
        val id = text("id") ?: return null
        if (optBoolean("isTrashed")) return null
        val exif = optJSONObject("exifInfo")
        var width = number("width") ?: exif?.number("exifImageWidth")
        var height = number("height") ?: exif?.number("exifImageHeight")
        // EXIF orientations 5 to 8 are rotated by 90°: width and height are swapped
        if (exif.text("orientation")?.toIntOrNull() in 5..8) width = height.also { height = width }
        val portrait = width != null && height != null && height > width

        val place = listOfNotNull(exif.text("city"), exif.text("country")).joinToString(", ").ifEmpty { null }
        val date = (exif.text("dateTimeOriginal") ?: text("localDateTime"))?.take(10)?.let { iso ->
            try {
                SimpleDateFormat("d MMMM yyyy", Locale.FRENCH).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)!!)
            } catch (e: Exception) {
                null
            }
        }
        return Photo(id, portrait, place, date)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("x-api-key", apiKey)
    }

    private fun check(connection: HttpURLConnection, what: String) {
        when (val code = connection.responseCode) {
            in 200..299 -> return
            401 -> throw IOException("clé d'API refusée")
            403 -> throw IOException("permission manquante sur la clé d'API")
            else -> throw IOException("HTTP $code sur ${what.removePrefix(baseUrl).substringBefore('?')}")
        }
    }

    private fun request(method: String, path: String, body: String? = null): Any {
        val connection = open("$baseUrl$path")
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            check(connection, "$baseUrl$path")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject?.text(key: String): String? =
        if (this == null || isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONObject.number(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val PAGE_SIZE = 1000
    }
}
