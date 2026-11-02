package io.github.spartandou.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Immich slideshow behind the home screen: two stacked slides cross-fade.
 *
 * Built for the Shield's 2 GB: photos are decoded straight to GPU memory (HARDWARE bitmaps, no Java
 * heap copy) at screen size, at most two exist during a fade, and nothing runs while the home
 * screen is hidden.
 */
class PhotoWall(
    private val context: Context,
    private val slides: List<FrameLayout>,
    private val captionBox: View,
    private val caption: TextView,
    private val captionDate: TextView,
    private val prefs: Prefs,
    // Each new photo's blurred copy, for the automatic accent colour
    private val onPhoto: (Bitmap) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listFile = File(context.filesDir, "photos.json")
    private val lastPhotoFile = File(context.cacheDir, "last.jpg")

    // Only touched on the worker thread
    private var photos: List<Photo> = emptyList()
    private var index = 0
    private var listKey = ""
    private var listTime = 0L
    private var listDay = ""

    private var front = 0
    // What the front slide and the glass show now, recycled once replaced
    private var shownPhoto: Bitmap? = null
    private var shownBlur: Bitmap? = null
    private var started = false
    private var busy = false
    // A "continue watching" picture is shown instead of the photos
    private var media = false

    private val next = Runnable { showNext() }

    fun start() {
        started = true
        // Back from another app: the last photo comes back from disk over its blurred copy
        val restoring = photoView(front).drawable == null && lastPhotoFile.exists()
        if (restoring) showLastPhoto()
        main.removeCallbacks(next)
        main.postDelayed(next, if (photoView(front).drawable == null && !restoring) 0 else prefs.intervalSeconds * 1000L)
    }

    /**
     * Hidden behind another app: the full-size photo (~6 MB of graphics memory) is freed, only its
     * small blurred copy stays on screen for an instant, soft return.
     */
    fun stop() {
        started = false
        main.removeCallbacks(next)
        val slide = slides[front]
        slide.animate().cancel()
        slide.alpha = 1f
        clear(slides[1 - front])
        photoView(front).setImageDrawable(null)
        (slide.getChildAt(0) as ImageView).setImageBitmap(shownBlur)
        shownPhoto?.recycle()
        shownPhoto = null
    }

    /** Called when Android asks for memory back: the photos are dropped, the last one stays on disk. */
    fun release() {
        GlassView.showAll(null, 0)
        slides.forEach { slide ->
            slide.animate().cancel()
            clear(slide)
        }
        shownPhoto?.recycle()
        shownBlur?.recycle()
        shownPhoto = null
        shownBlur = null
        worker.execute { photos = emptyList(); listKey = "" }
    }

    /**
     * Shows a "continue watching" picture in place of the photos, until [resumePhotos]. Decoded on
     * the caller's thread: the photo worker may be busy downloading.
     */
    fun showMedia(bytes: ByteArray, stillWanted: () -> Boolean) {
        val bitmap = decode(bytes) ?: return
        val blur = Blur.fromJpeg(bytes)
        main.post {
            // The entry may have lost the focus meanwhile: then the photos simply go on
            if (started && stillWanted()) {
                media = true
                main.removeCallbacks(next)
                display(bitmap, blur, false, null)
            } else {
                bitmap.recycle()
                blur?.recycle()
            }
        }
    }

    /** Back to the photos: the last one at once (from disk), the next one after the usual interval. */
    fun resumePhotos() {
        if (!media) return
        media = false
        main.removeCallbacks(next)
        if (lastPhotoFile.exists()) worker.execute {
            val bytes = lastPhotoFile.readBytes()
            val bitmap = decode(bytes) ?: return@execute
            val blur = Blur.fromJpeg(bytes)
            main.post { if (started && !media) display(bitmap, blur, bitmap.height > bitmap.width, null) else bitmap.recycle() }
        }
        if (started) main.postDelayed(next, prefs.intervalSeconds * 1000L)
    }


    private fun clear(slide: FrameLayout) =
        (0 until slide.childCount).forEach { (slide.getChildAt(it) as ImageView).setImageDrawable(null) }

    // Instant picture on boot or after a release, before anything is downloaded
    private fun showLastPhoto() {
        if (!lastPhotoFile.exists()) return
        worker.execute {
            val bytes = lastPhotoFile.readBytes()
            val bitmap = decode(bytes) ?: return@execute
            val blur = Blur.fromJpeg(bytes)
            main.post { if (photoView(front).drawable == null) display(bitmap, blur, bitmap.height > bitmap.width, null) }
        }
    }

    private fun showNext() {
        if (!started || busy || media) return
        if (prefs.serverUrl.isBlank() || prefs.apiKey.isBlank()) {
            showMessage(context.getString(R.string.not_connected))
            return
        }
        if (prefs.albumIds.isEmpty() && !prefs.memories) {
            showMessage(context.getString(R.string.no_album))
            return
        }
        busy = true
        val key = prefs.sourceKey
        val client = ImmichClient(prefs.serverUrl, prefs.apiKey)
        val albumIds = prefs.albumIds
        val memories = prefs.memories
        worker.execute {
            var shown = false
            try {
                ensureList(client, key, albumIds, memories)
                if (photos.isEmpty()) {
                    main.post { showMessage(context.getString(R.string.no_photos)) }
                } else {
                    val photo = photos[index % photos.size]
                    index = (index + 1) % photos.size
                    val bytes = client.download(client.imageUrl(photo.id))
                    val bitmap = decode(bytes)
                    if (bitmap != null) {
                        val portrait = photo.portrait || bitmap.height > bitmap.width
                        val blur = Blur.fromJpeg(bytes)
                        lastPhotoFile.writeBytes(bytes)
                        shown = true
                        main.post { if (started && !media) display(bitmap, blur, portrait, photo) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Photo not loaded", e)
                main.post {
                    if (photoView(front).drawable == null) {
                        showMessage(context.getString(R.string.server_error, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            main.post {
                busy = false
                main.removeCallbacks(next)
                // A failure is retried soon, a shown photo stays for the whole interval
                if (started) main.postDelayed(next, if (shown) prefs.intervalSeconds * 1000L else RETRY_MS)
            }
        }
    }

    /** Albums are re-read every few hours, memories every day; the list is kept on disk between starts. */
    private fun ensureList(client: ImmichClient, key: String, albumIds: Set<String>, memories: Boolean) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        if (photos.isEmpty() && listFile.exists()) readList()
        val fresh = listKey == key && System.currentTimeMillis() - listTime < LIST_TTL_MS && (!memories || listDay == today)
        if (fresh && photos.isNotEmpty()) return

        val albumPhotos = albumIds.flatMap { client.albumPhotos(it, MAX_PER_ALBUM) }.distinctBy { it.id }.shuffled()
            .take(MAX_PHOTOS)
        val memoryPhotos = if (memories) client.todaysMemories() else emptyList()
        // Memories are spread at random among the album photos
        val mixed = albumPhotos.toMutableList()
        val known = albumPhotos.mapTo(HashSet()) { it.id }
        memoryPhotos.filter { known.add(it.id) }.forEach { mixed.add(Random.nextInt(mixed.size + 1), it) }

        photos = mixed
        index = 0
        listKey = key
        listTime = System.currentTimeMillis()
        listDay = today
        listFile.writeText(JSONObject()
            .put("key", key).put("time", listTime).put("day", today)
            .put("photos", JSONArray().apply { mixed.forEach { put(it.toJson()) } })
            .toString())
    }

    private fun readList() {
        try {
            val json = JSONObject(listFile.readText())
            val array = json.getJSONArray("photos")
            photos = (0 until array.length()).map { Photo.fromJson(array.getJSONObject(it)) }
            index = Random.nextInt(photos.size.coerceAtLeast(1))
            listKey = json.optString("key")
            listTime = json.optLong("time")
            listDay = json.optString("day")
        } catch (e: Exception) {
            Log.w(TAG, "Bad photo list cache", e)
        }
    }

    private fun display(bitmap: Bitmap, blur: Bitmap?, portrait: Boolean, photo: Photo?) {
        val back = 1 - front
        val slide = slides[back]
        photoView(back).apply {
            setImageBitmap(bitmap)
            scaleType = if (portrait) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        }
        // Portrait photos sit on a blurred, darkened copy of themselves; the glass surfaces use it too
        (slide.getChildAt(0) as ImageView).setImageBitmap(if (portrait) blur else null)
        GlassView.showAll(blur, FADE_MS)
        blur?.let(onPhoto)

        val oldPhoto = shownPhoto
        val oldBlur = shownBlur
        shownPhoto = bitmap
        shownBlur = blur
        slide.animate().cancel()
        slide.alpha = 0f
        slide.bringToFront()
        slide.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        slide.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            slide.setLayerType(View.LAYER_TYPE_NONE, null)
            // The previous photo is freed as soon as it is fully covered. Explicitly: the Java heap
            // stays tiny, so a GC (which would free it) can take many photos to come
            val old = slides[front]
            if (old !== slide) clear(old)
            front = back
            if (oldPhoto !== bitmap) oldPhoto?.recycle()
            if (oldBlur !== blur) oldBlur?.recycle()
        }.start()
        updateCaption(photo)
    }

    // Place on the first line, date below it; a single line when only one of them is shown
    private fun updateCaption(photo: Photo?) {
        val place = photo?.place?.takeIf { prefs.showPlace }
        val date = photo?.date?.takeIf { prefs.showDate }
        captionBox.animate().cancel()
        captionBox.alpha = 0f
        caption.text = place ?: date ?: ""
        captionDate.text = if (place != null) date ?: "" else ""
        captionDate.visibility = if (captionDate.text.isEmpty()) View.GONE else View.VISIBLE
        if (place != null || date != null) captionBox.animate().alpha(1f).setStartDelay(FADE_MS / 2).setDuration(FADE_MS).start()
    }

    private fun showMessage(text: String) {
        captionBox.animate().cancel()
        caption.text = text
        captionDate.visibility = View.GONE
        captionBox.alpha = 1f
    }

    private fun photoView(i: Int) = slides[i].getChildAt(1) as ImageView

    // Decodes at most at screen size
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var inSample = 1
        while (bounds.outWidth / (inSample * 2) >= MAX_W && bounds.outHeight / (inSample * 2) >= MAX_H) inSample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = inSample
            // Pixels go straight to the GPU, no second copy on the Java heap
            inPreferredConfig = Bitmap.Config.HARDWARE
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        private const val TAG = "PhotoWall"
        private const val LIST_TTL_MS = 6 * 60 * 60 * 1000L
        private const val RETRY_MS = 10_000L
        private const val FADE_MS = 1_200L
        // Keeps the list small in memory (~100 bytes a photo)
        private const val MAX_PER_ALBUM = 3_000
        private const val MAX_PHOTOS = 5_000
        private const val MAX_W = 1920
        private const val MAX_H = 1080
    }
}
