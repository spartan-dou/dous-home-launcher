package io.github.spartandou.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Home Assistant puts on the photo frame: notifications and the values under the clock. Kept
 * for the life of the process, so closing and reopening the frame does not lose them. Main thread
 * only.
 */
object Overlay {
    class Note(val message: String, val link: String?, val tag: String?, val until: Long)

    class Value(val icon: String, val text: String)

    private const val MAX_NOTES = 3
    // Home Assistant sends the values again every minute: older ones are no longer true
    private const val VALUES_TTL_MS = 5 * 60 * 1000L

    private val notes = ArrayList<Note>()
    private var values: List<Value> = emptyList()
    private var valuesTime = 0L

    /** Newest first, without the ones whose time is over. */
    fun notes(now: Long): List<Note> {
        notes.removeAll { it.until in 1..now }
        return notes
    }

    /** When the next notification ends, 0 when none does. */
    fun nextEnd(): Long = notes.filter { it.until > 0 }.minOfOrNull { it.until } ?: 0

    /**
     * As on a phone: a message replaces the one with the same tag (or the same text) and goes on
     * top; an empty message clears the tagged one, or all of them without a tag.
     */
    fun notify(json: JSONObject, now: Long) {
        val message = json.text("message").orEmpty().trim()
        val tag = json.text("tag")
        if (message.isEmpty()) {
            if (tag == null) notes.clear() else notes.removeAll { it.tag == tag }
            return
        }
        notes.removeAll { if (tag != null) it.tag == tag else it.tag == null && it.message == message }
        val minutes = json.optDouble("duration", 0.0).takeIf { !it.isNaN() && it > 0 }
        notes.add(0, Note(message, json.text("link"), tag, minutes?.let { now + (it * 60_000).toLong() } ?: 0))
        while (notes.size > MAX_NOTES) notes.removeAt(notes.size - 1)
    }

    fun setValues(sensors: JSONArray, now: Long) {
        values = (0 until sensors.length()).mapNotNull { sensors.optJSONObject(it) }.mapNotNull { sensor ->
            val value = sensor.text("value") ?: return@mapNotNull null
            Value(sensor.text("icon").orEmpty(), listOfNotNull(value, sensor.text("unit")).joinToString(" "))
        }
        valuesTime = now
    }

    fun values(now: Long): List<Value> = if (now - valuesTime > VALUES_TTL_MS) emptyList() else values

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
}
