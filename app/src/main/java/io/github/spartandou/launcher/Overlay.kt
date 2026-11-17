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

    class Value(val icon: String, val value: String, val unit: String)

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
        notes.add(0, Note(emoji(message), json.text("link"), tag, minutes?.let { now + (it * 60_000).toLong() } ?: 0))
        while (notes.size > MAX_NOTES) notes.removeAt(notes.size - 1)
    }

    fun setValues(sensors: JSONArray, now: Long) {
        values = (0 until sensors.length()).mapNotNull { sensors.optJSONObject(it) }.mapNotNull { sensor ->
            val value = sensor.text("value") ?: return@mapNotNull null
            Value(emoji(sensor.text("icon").orEmpty()), value, sensor.text("unit").orEmpty())
        }
        valuesTime = now
    }

    fun values(now: Long): List<Value> = if (now - valuesTime > VALUES_TTL_MS) emptyList() else values

    /**
     * A pictograph sent without its emoji selector (🛋, ☀, ❤...) is drawn in black and white:
     * the selector is added. Not before a selector or a skin tone already there, nor below U+2300
     * (©, ™, arrows: text), nor to flags' letters or skin tones themselves.
     */
    fun emoji(text: String): String {
        val out = StringBuilder(text.length + 4)
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            out.appendCodePoint(c)
            i += Character.charCount(c)
            val pictograph = c in 0x2300..0x2BFF || c in 0x1F000..0x1FAFF
            val excluded = c in 0x1F1E6..0x1F1FF || c in 0x1F3FB..0x1F3FF
            val next = if (i < text.length) text.codePointAt(i) else -1
            val followed = next == 0xFE0E || next == 0xFE0F || next in 0x1F3FB..0x1F3FF
            if (pictograph && !excluded && !followed) out.append('\uFE0F')
        }
        return out.toString()
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
}
