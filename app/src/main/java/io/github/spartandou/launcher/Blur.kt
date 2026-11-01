package io.github.spartandou.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Android 11 has no GPU blur (RenderEffect is Android 12+), so each photo gets one small blurred
 * copy (~240x135, ~130 KB) that the glass surfaces stretch: the stretch adds to the softness.
 */
object Blur {
    private const val TARGET_WIDTH = 240

    fun fromJpeg(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        // The JPEG decoder does the downscale almost for free
        val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val width = TARGET_WIDTH
        val height = (small.height * TARGET_WIDTH / small.width).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(small, width, height, true)
        if (scaled !== small) small.recycle()

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = IntArray(pixels.size)
        // Three box passes look like a gaussian blur
        repeat(3) {
            boxPass(pixels, buffer, width, height, RADIUS, horizontal = true)
            boxPass(buffer, pixels, width, height, RADIUS, horizontal = false)
        }
        scaled.setPixels(pixels, 0, width, 0, 0, width, height)
        return scaled
    }

    private const val RADIUS = 5

    private fun boxPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            var r = 0; var g = 0; var b = 0
            fun at(i: Int): Int {
                val p = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * width + p] else src[p * width + line]
            }
            for (i in -radius..radius) {
                val c = at(i)
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until length) {
                val out = if (horizontal) line * width + i else i * width + line
                dst[out] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val add = at(i + radius + 1)
                val remove = at(i - radius)
                r += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                b += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }
}
