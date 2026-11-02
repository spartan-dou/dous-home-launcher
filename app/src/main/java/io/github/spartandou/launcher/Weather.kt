package io.github.spartandou.launcher

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Today's weather, or tomorrow's from 18:00, under the clock. Open-Meteo (free, no account) gets
 * only the coordinates of the town: the one chosen on the phone, else the one of the IP address.
 * At most one request an hour, only while the home screen is shown.
 */
class Weather(context: Context) {
    private val cache = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** What to show: a line icon (drawable) and the text. */
    data class Line(val icon: Int, val text: String)

    /** The line to show, from the cache (no network). Null when there is nothing yet. */
    fun line(): Line? {
        val json = cache.getString("forecast", null) ?: return null
        val daily = JSONObject(json).optJSONObject("daily") ?: return null
        val dates = daily.optJSONArray("time") ?: return null
        val now = Calendar.getInstance()
        val tomorrow = now.get(Calendar.HOUR_OF_DAY) >= EVENING_HOUR
        val wanted = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(
            Date(now.timeInMillis + if (tomorrow) DAY_MS else 0))
        val i = (0 until dates.length()).firstOrNull { dates.optString(it) == wanted } ?: return null
        val code = daily.optJSONArray("weather_code")?.optInt(i) ?: return null
        val min = daily.optJSONArray("temperature_2m_min")?.optDouble(i) ?: return null
        val max = daily.optJSONArray("temperature_2m_max")?.optDouble(i) ?: return null
        val (icon, label) = describe(code)
        val temps = "${Math.round(min)}° / ${Math.round(max)}°"
        return Line(icon, (if (tomorrow) "Demain  " else "") + "$temps  ·  $label")
    }

    /**
     * True when the cached forecast is too old or for another place. After a failure, not before a
     * pause: a service down is not asked again every few seconds.
     */
    fun stale(place: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFailure < RETRY_MS) return false
        return cache.getString("place_key", null) != place || now - cache.getLong("time", 0) > REFRESH_MS
    }

    /** Network: finds the place if needed and downloads the forecast. Worker thread only. */
    fun refresh(city: String) {
        try {
            val place = if (city.isBlank()) placeFromIp() else geocode(city) ?: throw IOException("ville introuvable : $city")
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2"
            cache.edit()
                .putString("forecast", get(url))
                .putString("place_key", city)
                .putString("place_name", place.name)
                .putLong("time", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            lastFailure = System.currentTimeMillis()
            throw e
        }
    }

    @Volatile
    private var lastFailure = 0L

    val placeName: String?
        get() = cache.getString("place_name", null)

    private fun placeFromIp(): Place {
        val json = JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude"))
        if (!json.optBoolean("success")) throw IOException("position introuvable")
        return Place(json.optString("city"), json.getDouble("latitude"), json.getDouble("longitude"))
    }

    companion object {
        private const val EVENING_HOUR = 18
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val REFRESH_MS = 60 * 60 * 1000L
        private const val RETRY_MS = 15 * 60 * 1000L

        /** Town name to coordinates, also used by the phone set-up page to check what was typed. */
        fun geocode(city: String): Place? {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=${Uri.encode(city.trim())}"
            val result = JSONObject(get(url)).optJSONArray("results")?.optJSONObject(0) ?: return null
            val name = listOfNotNull(result.optString("name").ifEmpty { null }, result.optString("country").ifEmpty { null })
                .joinToString(", ")
            return Place(name, result.getDouble("latitude"), result.getDouble("longitude"))
        }

        private fun get(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        // WMO weather codes, as used by Open-Meteo; white line icons like the other ones on screen
        private fun describe(code: Int): Pair<Int, String> = when (code) {
            0 -> R.drawable.ic_w_sun to "Ensoleillé"
            1 -> R.drawable.ic_w_partly to "Plutôt ensoleillé"
            2 -> R.drawable.ic_w_partly to "Éclaircies"
            3 -> R.drawable.ic_w_cloud to "Couvert"
            45, 48 -> R.drawable.ic_w_fog to "Brouillard"
            51, 53, 55, 56, 57 -> R.drawable.ic_w_rain to "Bruine"
            61, 63, 66 -> R.drawable.ic_w_rain to "Pluie"
            65, 67 -> R.drawable.ic_w_rain to "Forte pluie"
            71, 73, 75, 77 -> R.drawable.ic_w_snow to "Neige"
            80, 81, 82 -> R.drawable.ic_w_rain to "Averses"
            85, 86 -> R.drawable.ic_w_snow to "Averses de neige"
            95, 96, 99 -> R.drawable.ic_w_storm to "Orages"
            else -> R.drawable.ic_w_cloud to "Météo"
        }
    }
}
