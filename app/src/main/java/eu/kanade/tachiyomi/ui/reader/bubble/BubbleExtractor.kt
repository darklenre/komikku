package eu.kanade.tachiyomi.ui.reader.bubble

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.LruCache
import android.util.Size
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cuts one balloon out of the page for the floating Bubble Zoom view.
 *
 * The crop is the detection box grown by [CROP_MARGIN] on every side, decoded at native resolution
 * (capped at [CROP_MAX_SIDE]). With the "sam" cutout method the balloon shape comes from
 * [SamRefiner.cropMask] run on that crop; otherwise, or when SAM is unavailable or unsure, a rounded
 * rectangle around the box is used. Masks are cached per balloon, and [prewarm] fills the cache for
 * a whole page in the background on devices where that is cheap.
 */
object BubbleExtractor {

    /** Margin around the detection box, per side, as a fraction of the box: room for tails and scallops. */
    private const val CROP_MARGIN = 0.35f

    /** Longest side the crop is decoded at; larger crops are subsampled by a power of two. */
    private const val CROP_MAX_SIDE = 1600

    /** Rounded-rectangle fallback: box grown by this fraction, corner radius as a share of its short side. */
    private const val FALLBACK_GROW = 0.06f
    private const val FALLBACK_RADIUS = 0.12f

    private class CachedMask(val alpha: ByteArray, val w: Int, val h: Int)

    /** Balloon masks (one byte per crop px) keyed by page key + balloon rect. */
    private val masks = object : LruCache<String, CachedMask>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CachedMask) = value.alpha.size
    }

    /** A decoded crop: [box] is the detection box in [bitmap] px, [source] the crop region in page px. */
    private class Crop(val bitmap: Bitmap, val source: Rect, val box: RectF)

    /** Extracts [bubble] from [page] as a shaped, full-resolution [BubbleCutout]. */
    fun extractBubble(page: ReaderPage, bubble: Bubble, sourceSize: Size?): BubbleCutout? {
        val streamFn = page.stream ?: return null
        val prefs = runCatching { Injekt.get<ReaderPreferences>() }.getOrNull()
        val method = prefs?.bubbleZoomCroppingMethod()?.get() ?: "sam"
        val withContext = prefs?.bubbleZoomCutoutContext()?.get() ?: false
        val context = runCatching { Injekt.get<Application>() as Context }.getOrNull()
        val bytes = snapshot(streamFn) ?: return null
        val crop = decodeCrop(bytes, bubble.rect, sourceSize) ?: return null
        val alpha = if (method == "sam" && context != null) {
            maskFor(context, maskKey(bubbleKeyFor(page), bubble.rect), crop, foreground = true)
        } else {
            null
        }
        return compose(crop, bubble, alpha, withContext)
    }

    /**
     * Computes and caches the masks of [bubbles] on page [pageKey] ahead of the user, checking
     * [stillWanted] between balloons. Only when the SAM encoder runs on the GPU: on CPU a page of
     * balloons costs seconds, so there the cutout is computed on demand instead.
     */
    fun prewarm(
        context: Context,
        pageKey: String,
        streamFn: () -> InputStream,
        bubbles: List<Bubble>,
        stillWanted: () -> Boolean,
    ) {
        if (bubbles.isEmpty() || !SamRefiner.prepare(context) || !SamRefiner.isFast) return
        val bytes = snapshot(streamFn) ?: return
        for (b in bubbles) {
            if (!stillWanted()) return
            val key = maskKey(pageKey, b.rect)
            if (masks.get(key) != null) continue
            val crop = decodeCrop(bytes, b.rect, null) ?: continue
            try {
                maskFor(context, key, crop, foreground = false)
            } finally {
                crop.bitmap.recycle()
            }
        }
    }

    private fun maskKey(pageKey: String, r: RectF) = "$pageKey|${r.left},${r.top},${r.right},${r.bottom}"

    private fun maskFor(context: Context, key: String, crop: Crop, foreground: Boolean): FloatArray? {
        val w = crop.bitmap.width
        val h = crop.bitmap.height
        masks.get(key)?.takeIf { it.w == w && it.h == h }?.let { m ->
            return FloatArray(w * h) { (m.alpha[it].toInt() and 0xFF) / 255f }
        }
        val alpha = runCatching { SamRefiner.cropMask(context, crop.bitmap, crop.box, foreground) }
            .onFailure { logcat(LogPriority.ERROR) { "BubbleExtractor: SAM failed: ${it.message}" } }
            .getOrNull() ?: return null
        masks.put(key, CachedMask(ByteArray(w * h) { (alpha[it] * 255f + 0.5f).toInt().toByte() }, w, h))
        return alpha
    }

    /**
     * Reads the page bytes once. Decoding runs off this immutable array, never off the live stream:
     * decoding the same stream concurrently with the page holder corrupts the native decoder's buffer
     * (SIGSEGV in `BitmapFactory.decodeStream`), which a `runCatching` can't catch.
     */
    private fun snapshot(streamFn: () -> InputStream): ByteArray? =
        runCatching { streamFn().use { it.readBytes() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun decodeCrop(bytes: ByteArray, rect: RectF, sourceSize: Size?): Crop? = runCatching {
        @Suppress("DEPRECATION")
        val decoder = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            } else {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            }
            ) ?: return null
        try {
            val sW = sourceSize?.width ?: decoder.width
            val sH = sourceSize?.height ?: decoder.height
            val mx = CROP_MARGIN * rect.width()
            val my = CROP_MARGIN * rect.height()
            val left = ((rect.left - mx).coerceAtLeast(0f) * sW).toInt().coerceIn(0, decoder.width - 1)
            val top = ((rect.top - my).coerceAtLeast(0f) * sH).toInt().coerceIn(0, decoder.height - 1)
            val right = ((rect.right + mx).coerceAtMost(1f) * sW).toInt().coerceIn(left + 1, decoder.width)
            val bottom = ((rect.bottom + my).coerceAtMost(1f) * sH).toInt().coerceIn(top + 1, decoder.height)
            val source = Rect(left, top, right, bottom)
            var sample = 1
            while (max(source.width(), source.height()) / sample > CROP_MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = decoder.decodeRegion(source, opts) ?: return null
            val sx = bmp.width.toFloat() / source.width()
            val sy = bmp.height.toFloat() / source.height()
            val box = RectF(
                (rect.left * sW - left) * sx,
                (rect.top * sH - top) * sy,
                (rect.right * sW - left) * sx,
                (rect.bottom * sH - top) * sy,
            )
            Crop(bmp, source, box)
        } finally {
            decoder.recycle()
        }
    }.onFailure { logcat(LogPriority.WARN) { "BubbleExtractor: crop decode failed: ${it.message}" } }.getOrNull()

    /**
     * The balloon cut out of [crop] with [alpha] (or the rounded-rect fallback); with [withContext] the
     * opaque crop is kept too, for the overlay to draw dimmed behind it.
     */
    private fun compose(crop: Crop, bubble: Bubble, alpha: FloatArray?, withContext: Boolean): BubbleCutout {
        val src = crop.bitmap
        val w = src.width
        val h = src.height
        val a = alpha ?: roundedRectAlpha(w, h, crop.box)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val ai = (a[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (ai shl 24) or (px[i] and 0x00FFFFFF)
        }
        val cut = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

        // Frame on the balloon body: its text, or the box when the detector found no text block.
        val box = crop.box
        val body = if (bubble.text.isNotEmpty()) {
            val u = RectF(bubble.text[0])
            bubble.text.forEach { u.union(it) }
            val br = bubble.rect
            // text rects are page-normalised like bubble.rect: map them through the box's transform
            RectF(
                box.left + (u.left - br.left) / br.width() * box.width(),
                box.top + (u.top - br.top) / br.height() * box.height(),
                box.left + (u.right - br.left) / br.width() * box.width(),
                box.top + (u.bottom - br.top) / br.height() * box.height(),
            )
        } else {
            box
        }
        // `cut` is built from a pixel copy, so the crop can go unless the overlay draws it as context.
        val ctx = if (withContext) {
            src
        } else {
            src.recycle()
            null
        }
        return BubbleCutout(
            bitmap = cut,
            context = ctx,
            source = crop.source,
            bodyOffsetX = body.centerX() / w - 0.5f,
            bodyOffsetY = body.centerY() / h - 0.5f,
        )
    }

    /** Alpha of a rounded rectangle around [box] grown by [FALLBACK_GROW], 1 px anti-aliased. */
    private fun roundedRectAlpha(w: Int, h: Int, box: RectF): FloatArray {
        val l = (box.left - FALLBACK_GROW * box.width()).coerceAtLeast(0f)
        val t = (box.top - FALLBACK_GROW * box.height()).coerceAtLeast(0f)
        val r = (box.right + FALLBACK_GROW * box.width()).coerceAtMost(w.toFloat())
        val b = (box.bottom + FALLBACK_GROW * box.height()).coerceAtMost(h.toFloat())
        val rad = FALLBACK_RADIUS * min(r - l, b - t)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val py = y + 0.5f
            val cy = py.coerceIn(t + rad, b - rad)
            for (x in 0 until w) {
                val px = x + 0.5f
                val cx = px.coerceIn(l + rad, r - rad)
                val d = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)) - rad
                out[y * w + x] = (0.5f - d).coerceIn(0f, 1f)
            }
        }
        return out
    }
}
