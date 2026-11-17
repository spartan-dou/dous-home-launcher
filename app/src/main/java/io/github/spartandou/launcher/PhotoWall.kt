package io.github.spartandou.launcher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
    // The photo frame's card: the date and the place each on its own line (their icons are their
    // rows' first child), instead of the home screen's single "pin, place · date" line
    private val stacked: Boolean = false,
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
    // Whether the photo on screen is a portrait one: how it fills the screen depends on the screen's way
    private var shownPortrait = false
    private var started = false
    private var busy = false
    // A "continue watching" picture is shown instead of the photos
    private var media = false
    // The slow zoom of each slide, while it is shown
    private val drifts = HashMap<FrameLayout, Animator>()
    // Pictures replaced on screen, freed when the fade that covers them ends
    private val retired = mutableListOf<Bitmap>()

    // A paused wall still loads its first photo
    private val next = Runnable { if (!paused || shownPhoto == null) showNext() }

    /** The photo on screen, null for anything else (a message, a "continue watching" picture). */
    var current: Photo? = null
        private set

    /** Paused: the photo stays until [skip], or until it is set back to false. */
    var paused = false
        set(value) {
            field = value
            if (!value && started && !media) schedule(prefs.intervalSeconds * 1000L)
        }

    /** Another photo at once: the next one, or the one before with a negative [step]. */
    fun skip(step: Int = 1) {
        if (!started || media) return
        main.removeCallbacks(next)
        showNext(step)
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(next)
        main.postDelayed(next, delayMs)
    }

    fun start() {
        started = true
        // Back from another app: the last photo comes back from disk over its blurred copy
        val restoring = photoView(front).drawable == null && lastPhotoFile.exists()
        if (restoring) showLastPhoto()
        schedule(if (photoView(front).drawable == null && !restoring) 0 else prefs.intervalSeconds * 1000L)
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
        slides.forEach { stopDrift(it) }
        clear(slides[1 - front])
        photoView(front).setImageDrawable(null)
        (slide.getChildAt(0) as ImageView).setImageBitmap(shownBlur)
        shownPhoto?.recycle()
        shownPhoto = null
        recycleRetired()
    }

    /** Called when Android asks for memory back: the photos are dropped, the last one stays on disk. */
    fun release() {
        // Only when the glass still shows this wall's photo: another wall may have taken over
        if (GlassView.shows(shownBlur)) GlassView.showAll(null, 0)
        slides.forEach { slide ->
            slide.animate().cancel()
            stopDrift(slide)
            clear(slide)
        }
        shownPhoto?.recycle()
        shownBlur?.recycle()
        shownPhoto = null
        shownBlur = null
        recycleRetired()
        worker.execute { photos = emptyList(); listKey = "" }
    }

    /** For a wall that will not be started again: frees its photos and ends its thread. */
    fun close() {
        release()
        worker.shutdown()
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
            main.post { if (started && !media) display(bitmap, blur, bitmap.height > bitmap.width, null) else drop(bitmap, blur) }
        }
        if (started) schedule(prefs.intervalSeconds * 1000L)
    }


    // A picture decoded for nothing (the home screen was left meanwhile): its graphics memory goes
    // back at once
    private fun drop(bitmap: Bitmap, blur: Bitmap?) {
        bitmap.recycle()
        blur?.recycle()
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
            main.post {
                if (started && !media && photoView(front).drawable == null) display(bitmap, blur, bitmap.height > bitmap.width, null)
                else drop(bitmap, blur)
            }
        }
    }

    private fun showNext(step: Int = 1) {
        if (!started || busy || media) return
        if (prefs.serverUrl.isBlank() || prefs.apiKey.isBlank()) {
            showMessage(context.getString(R.string.not_connected))
            return
        }
        if (prefs.albumIds.isEmpty() && !prefs.memories && !prefs.memoriesOnly) {
            showMessage(context.getString(R.string.no_album))
            return
        }
        busy = true
        val key = prefs.sourceKey
        val client = ImmichClient(prefs.serverUrl, prefs.apiKey)
        val albumIds = prefs.albumIds
        val memories = prefs.memories
        val memoriesOnly = prefs.memoriesOnly
        worker.execute {
            var shown = false
            try {
                ensureList(client, key, albumIds, memories, memoriesOnly)
                if (photos.isEmpty()) {
                    main.post { showMessage(context.getString(R.string.no_photos)) }
                } else {
                    // index is the photo after the one on screen: two back for the one before it
                    if (step < 0) index = (index - 2).mod(photos.size)
                    val photo = photos[index % photos.size]
                    index = (index + 1) % photos.size
                    val bytes = client.download(client.imageUrl(photo.id))
                    val bitmap = decode(bytes)
                    if (bitmap != null) {
                        val portrait = photo.portrait || bitmap.height > bitmap.width
                        val blur = Blur.fromJpeg(bytes)
                        lastPhotoFile.writeBytes(bytes)
                        shown = true
                        main.post { if (started && !media) display(bitmap, blur, portrait, photo) else drop(bitmap, blur) }
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
                // A failure is retried soon, a shown photo stays for the whole interval
                if (started) schedule(if (shown) prefs.intervalSeconds * 1000L else RETRY_MS)
            }
        }
    }

    /** Albums are re-read every few hours, memories every day; the list is kept on disk between starts. */
    private fun ensureList(client: ImmichClient, key: String, albumIds: Set<String>, memories: Boolean, memoriesOnly: Boolean) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        if (photos.isEmpty() && listFile.exists()) readList()
        val fresh = listKey == key && System.currentTimeMillis() - listTime < LIST_TTL_MS && (!(memories || memoriesOnly) || listDay == today)
        if (fresh && photos.isNotEmpty()) return

        val memoryPhotos = if (memories || memoriesOnly) client.todaysMemories() else emptyList()
        val mixed = if (memoriesOnly && memoryPhotos.isNotEmpty()) {
            memoryPhotos.distinctBy { it.id }.shuffled().toMutableList()
        } else {
            // "Memories only" on a day without any: the albums as usual
            val albumPhotos = albumIds.flatMap { client.albumPhotos(it, MAX_PER_ALBUM) }.distinctBy { it.id }.shuffled()
                .take(MAX_PHOTOS)
            // Memories are spread at random among the album photos
            val known = albumPhotos.mapTo(HashSet()) { it.id }
            albumPhotos.toMutableList().also { list ->
                memoryPhotos.filter { known.add(it.id) }.forEach { list.add(Random.nextInt(list.size + 1), it) }
            }
        }

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
        photoView(back).setImageBitmap(bitmap)
        fit(back, portrait, blur)
        GlassView.showAll(blur, FADE_MS)
        blur?.let(onPhoto)

        // Replaced pictures are freed once a fade ends, even when a newer picture cut that fade short
        shownPhoto?.takeIf { it !== bitmap }?.let { retired += it }
        shownBlur?.takeIf { it !== blur }?.let { retired += it }
        shownPhoto = bitmap
        shownBlur = blur
        shownPortrait = portrait
        slide.animate().cancel()
        slide.alpha = 0f
        slide.bringToFront()
        drift(slide)
        slide.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        slide.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            slide.setLayerType(View.LAYER_TYPE_NONE, null)
            // The previous photo is freed as soon as it is fully covered. Explicitly: the Java heap
            // stays tiny, so a GC (which would free it) can take many photos to come
            val old = slides[front]
            if (old !== slide) { clear(old); stopDrift(old) }
            front = back
            recycleRetired()
        }.start()
        current = photo
        updateCaption(photo)
    }

    // One line under the weather: pin, place, then the softer date
    private fun updateCaption(photo: Photo?) {
        val place = photo?.place?.takeIf { prefs.showPlace }
        val date = photo?.date?.takeIf { prefs.showDate }
        captionBox.animate().cancel()
        captionBox.alpha = 0f
        if (stacked) {
            caption.text = place.orEmpty()
            captionDate.text = date.orEmpty()
            row(caption).visibility = if (place != null) View.VISIBLE else View.GONE
            row(captionDate).visibility = if (date != null) View.VISIBLE else View.GONE
            row(caption).getChildAt(0).visibility = View.VISIBLE
        } else {
            caption.text = place ?: date ?: ""
            captionDate.text = if (place != null && date != null) "  ·  $date" else ""
            captionDate.visibility = if (captionDate.text.isEmpty()) View.GONE else View.VISIBLE
            pin.visibility = if (place != null) View.VISIBLE else View.GONE
        }
        if (place != null || date != null) captionBox.animate().alpha(1f).setStartDelay(FADE_MS / 2).setDuration(FADE_MS).start()
    }

    private fun showMessage(text: String) {
        current = null
        captionBox.animate().cancel()
        caption.text = text
        if (stacked) {
            row(captionDate).visibility = View.GONE
            row(caption).visibility = View.VISIBLE
            row(caption).getChildAt(0).visibility = View.GONE
        } else {
            captionDate.visibility = View.GONE
            pin.visibility = View.GONE
        }
        captionBox.alpha = 1f
    }

    // Everything replaced, except what is shown now (a picture can come back, e.g. the blur on stop)
    private fun recycleRetired() {
        retired.forEach { if (it !== shownPhoto && it !== shownBlur) it.recycle() }
        retired.clear()
    }

    private val pin: View get() = (captionBox as android.view.ViewGroup).getChildAt(0)

    private fun row(text: TextView) = text.parent as android.view.ViewGroup

    /**
     * The new photo zooms in by a few percent, then back, very slowly, around a random point. Only
     * the slide's scale changes: the GPU redraws the same picture, nothing is decoded again.
     */
    private fun drift(slide: FrameLayout) {
        drifts.remove(slide)?.cancel()
        slide.scaleX = 1f
        slide.scaleY = 1f
        if (!prefs.drift) return
        slide.pivotX = slide.width * Random.nextFloat()
        slide.pivotY = slide.height * Random.nextFloat()
        drifts[slide] = ObjectAnimator.ofPropertyValuesHolder(slide,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, DRIFT_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, DRIFT_SCALE)).apply {
            duration = DRIFT_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopDrift(slide: FrameLayout) {
        drifts.remove(slide)?.cancel()
        slide.scaleX = 1f
        slide.scaleY = 1f
    }

    private fun photoView(i: Int) = slides[i].getChildAt(1) as ImageView

    /**
     * A photo the same way as the screen fills it; the other way (a portrait photo on a landscape
     * screen, or the reverse on a tablet held upright) it is shown whole, on a blurred, darkened
     * copy of itself. The glass surfaces use that copy too.
     */
    private fun fit(i: Int, portrait: Boolean, blur: Bitmap?) {
        val screenPortrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val whole = portrait != screenPortrait
        photoView(i).scaleType = if (whole) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        (slides[i].getChildAt(0) as ImageView).setImageBitmap(if (whole) blur else null)
    }

    /** The screen turned (a tablet): the photo on screen fills it again the right way. */
    fun onRotated() {
        if (shownPhoto != null) fit(front, shownPortrait, shownBlur)
    }

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
        // 3 % over 30 s, then back: barely noticed, the photo just breathes
        private const val DRIFT_SCALE = 1.03f
        private const val DRIFT_MS = 30_000L
    }
}
