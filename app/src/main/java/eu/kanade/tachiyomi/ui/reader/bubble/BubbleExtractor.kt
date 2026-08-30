package eu.kanade.tachiyomi.ui.reader.bubble

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Size
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a detected speech bubble out of the page image for the reader's floating Bubble Zoom.
 *
 * The bubble's detection box is decoded from the page stream at native resolution with a small
 * margin, then shaped by [ReaderPreferences.bubbleZoomCroppingMethod]:
 *  - `"none"`: a rounded rectangle.
 *  - `"sam"`: a MobileSAM box-prompted mask ([SamRefiner]) — falls back to the rounded rectangle
 *    when SAM is unavailable.
 * The silhouette is also traced to a vector [BubbleCutout.outline] (stroked live by the overlay when
 * [ReaderPreferences.bubbleZoomOutline] is on) and its body centre is found for tail-aware framing.
 */
object BubbleExtractor {

    /** Extra margin decoded around the tight detection box so scallops / the tail have room. */
    private const val CROP_MARGIN = 0.22f

    /** Working resolution the shape mask is built at before being upscaled onto the crop. */
    private const val WORK_MAX = 400

    /** Fallback sticker-outline width (percent of the cutout's short side) if the pref can't be read. */
    private const val OUTLINE_PCT_DEFAULT = 3

    /** Short-side resolution the silhouette is traced / eroded at (keeps the vector outline light). */
    private const val SILHOUETTE_GRID = 256

    /** Extracts [bubble] from [page] as a shaped, high-resolution [BubbleCutout] (bitmap + live outline). */
    fun extractBubble(page: ReaderPage, bubble: Bubble, sourceSize: Size?): BubbleCutout? {
        val streamFn = page.stream ?: return null

        val prefs = runCatching { Injekt.get<ReaderPreferences>() }.getOrNull()
        val method = prefs?.bubbleZoomCroppingMethod()?.get() ?: "sam"
        val outline = prefs?.bubbleZoomOutline()?.get() ?: true
        val outlinePct = prefs?.bubbleZoomOutlineWidth()?.get() ?: OUTLINE_PCT_DEFAULT
        val context = runCatching { Injekt.get<Application>() as Context }.getOrNull()

        // Expand the tight detection box by a margin, clamped to the page.
        val bw = bubble.rect.width()
        val bh = bubble.rect.height()
        val exp = RectF(
            (bubble.rect.left - CROP_MARGIN * bw).coerceAtLeast(0f),
            (bubble.rect.top - CROP_MARGIN * bh).coerceAtLeast(0f),
            (bubble.rect.right + CROP_MARGIN * bw).coerceAtMost(1f),
            (bubble.rect.bottom + CROP_MARGIN * bh).coerceAtMost(1f),
        )

        val rawCropped = try {
            streamFn().use { stream ->
                val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(stream)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(stream, false)
                } ?: return null

                val sW = sourceSize?.width ?: decoder.width
                val sH = sourceSize?.height ?: decoder.height

                val left = (exp.left * sW).toInt().coerceIn(0, decoder.width - 1)
                val top = (exp.top * sH).toInt().coerceIn(0, decoder.height - 1)
                val right = (exp.right * sW).toInt().coerceIn(left + 1, decoder.width)
                val bottom = (exp.bottom * sH).toInt().coerceIn(top + 1, decoder.height)

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val cropped = decoder.decodeRegion(Rect(left, top, right, bottom), options)
                decoder.recycle()
                cropped
            }
        } catch (t: Throwable) {
            null
        } ?: return null

        return processCroppedBitmap(rawCropped, bubble, method, outline, outlinePct, page, exp, streamFn, context)
    }

    /**
     * Cuts [cropped] to the bubble shape per [method] and, when [outline] is set, adds the sticker
     * border ([outlinePct] = its width as a percentage of the cutout's short side). [expandedCrop]
     * is the crop's rect in 0..1 page coordinates (needed to place the page-space SAM mask). SAM
     * degrades to a rounded rectangle when it is unavailable.
     */
    fun processCroppedBitmap(
        cropped: Bitmap,
        bubble: Bubble,
        method: String = "sam",
        outline: Boolean = true,
        outlinePct: Int = OUTLINE_PCT_DEFAULT,
        page: ReaderPage? = null,
        expandedCrop: RectF? = null,
        streamFn: (() -> InputStream)? = null,
        context: Context? = null,
    ): BubbleCutout {
        val cropW = cropped.width
        val cropH = cropped.height
        if (cropW <= 8 || cropH <= 8) return BubbleCutout(cropped)

        val result = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(cropped, 0f, 0f, null)
        if (cropped !== result) cropped.recycle()

        val shape = if (method == "sam") {
            samShape(context, page, streamFn, bubble, expandedCrop, cropW, cropH)
        } else {
            null
        }

        if (shape == null) {
            applyRoundedRect(canvas, cropW, cropH)
        } else {
            // FILTER_BITMAP_FLAG: bilinear-upscale the low-res mask so its edge doesn't staircase.
            canvas.drawBitmap(
                shape,
                null,
                RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                },
            )
            shape.recycle()
        }

        val info = analyzeSilhouette(result, if (outline) outlinePct else 0)
        return BubbleCutout(result, info.path, info.fraction, info.bodyOffsetX, info.bodyOffsetY)
    }

    private class Silhouette(
        val path: Path?,
        val fraction: Float,
        val bodyOffsetX: Float,
        val bodyOffsetY: Float,
    )

    /**
     * Traces the shaped bitmap's alpha silhouette (on a coarse [SILHOUETTE_GRID] grid) into a
     * smoothed closed [Path] in the unit square, and finds the bubble *body* centre by eroding away
     * the thin tail — so the overlay can frame on the body and stroke the outline live.
     * [outlinePct] <= 0 skips the path.
     */
    private fun analyzeSilhouette(bmp: Bitmap, outlinePct: Int): Silhouette {
        val w = bmp.width
        val h = bmp.height
        val stride = max(1, min(w, h) / SILHOUETTE_GRID)
        val gw = w / stride
        val gh = h / stride
        if (gw < 8 || gh < 8) return Silhouette(null, 0f, 0f, 0f)

        val inside = BooleanArray(gw * gh)
        val row = IntArray(w)
        for (gy in 0 until gh) {
            bmp.getPixels(row, 0, w, 0, gy * stride, w, 1)
            for (gx in 0 until gw) inside[gy * gw + gx] = (row[gx * stride] ushr 24) >= 128
        }

        val (bcx, bcy) = bodyCentre(inside, gw, gh)

        val path = if (outlinePct <= 0) {
            null
        } else {
            traceContour(inside, gw, gh)?.let { contour ->
                val smooth = chaikinClosed(chaikinClosed(contour))
                Path().apply {
                    moveTo(smooth[0] / gw, smooth[1] / gh)
                    var i = 2
                    while (i < smooth.size) {
                        lineTo(smooth[i] / gw, smooth[i + 1] / gh)
                        i += 2
                    }
                    close()
                }
            }
        }
        return Silhouette(path, outlinePct / 100f, bcx - 0.5f, bcy - 0.5f)
    }

    /**
     * Centre (0..1) of the bubble body: erode the silhouette a few cells to drop the thin tail, then
     * take the bounding box of what survives. Falls back to (0.5, 0.5) if erosion clears everything.
     */
    private fun bodyCentre(inside: BooleanArray, gw: Int, gh: Int): Pair<Float, Float> {
        val iters = max(1, min(gw, gh) / 12)
        var cur = inside
        repeat(iters) {
            val next = BooleanArray(cur.size)
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    val i = y * gw + x
                    if (!cur[i]) continue
                    next[i] = x > 0 && cur[i - 1] && x < gw - 1 && cur[i + 1] &&
                        y > 0 && cur[i - gw] && y < gh - 1 && cur[i + gw]
                }
            }
            cur = next
        }
        var minX = gw
        var minY = gh
        var maxX = -1
        var maxY = -1
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                if (!cur[y * gw + x]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return 0.5f to 0.5f
        return (minX + maxX + 1) / 2f / gw to (minY + maxY + 1) / 2f / gh
    }

    /** DST_IN a 12%-radius rounded rectangle onto [canvas]. */
    private fun applyRoundedRect(canvas: Canvas, w: Int, h: Int) {
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rr = min(w, h) * 0.12f
        Canvas(layer).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), rr, rr, Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.drawBitmap(
            layer,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
        )
        layer.recycle()
    }

    /** A MobileSAM box-prompted alpha mask for this bubble, sized to the crop. Null if unavailable. */
    private fun samShape(
        context: Context?,
        page: ReaderPage?,
        streamFn: (() -> InputStream)?,
        bubble: Bubble,
        expandedCrop: RectF?,
        cropW: Int,
        cropH: Int,
    ): Bitmap? {
        if (context == null || page == null || streamFn == null || expandedCrop == null) return null
        val w = min(cropW, WORK_MAX)
        val h = max(16, (cropH.toFloat() / cropW * w).toInt())
        return runCatching {
            SamRefiner.shapeMask(
                context = context,
                pageKey = bubbleKeyFor(page),
                streamFn = streamFn,
                boxNorm = bubble.rect,
                cropNorm = expandedCrop,
                outW = w,
                outH = h,
            )
        }.getOrNull()
    }

    /**
     * Moore-neighbour boundary trace of the first (row-major) opaque blob in [inside]. Returns the
     * pixel-centre polygon (x,y pairs, closed), or null if the blob is tiny / absent.
     */
    internal fun traceContour(inside: BooleanArray, w: Int, h: Int): FloatArray? {
        // Moore ring, clockwise from East.
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        fun solid(x: Int, y: Int) = x in 0 until w && y in 0 until h && inside[y * w + x]

        var sx = -1
        var sy = -1
        run {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (inside[y * w + x]) {
                        sx = x
                        sy = y
                        return@run
                    }
                }
            }
        }
        if (sx < 0) return null

        val pts = ArrayList<Float>(4096)
        var px = sx
        var py = sy
        var bx = sx - 1 // backtrack: the background pixel we came from (scan order => West)
        var by = sy
        val maxSteps = 4 * (w + h) + 32
        var steps = 0
        while (true) {
            pts.add(px + 0.5f)
            pts.add(py + 0.5f)
            var bi = 0
            for (k in 0 until 8) {
                if (px + dx[k] == bx && py + dy[k] == by) {
                    bi = k
                    break
                }
            }
            var foundIdx = -1
            var prevX = bx
            var prevY = by
            for (k in 1..8) {
                val d = (bi + k) and 7
                val cx = px + dx[d]
                val cy = py + dy[d]
                if (solid(cx, cy)) {
                    foundIdx = d
                    break
                }
                prevX = cx
                prevY = cy
            }
            if (foundIdx < 0) break // isolated pixel
            bx = prevX
            by = prevY
            px += dx[foundIdx]
            py += dy[foundIdx]
            if (px == sx && py == sy) break
            if (++steps > maxSteps) break
        }
        return if (pts.size >= 24) pts.toFloatArray() else null
    }

    /** One round of Chaikin corner-cutting on a closed polygon (x,y pairs). Doubles the point count. */
    internal fun chaikinClosed(p: FloatArray): FloatArray {
        val n = p.size / 2
        if (n < 4) return p
        val out = FloatArray(n * 4)
        var o = 0
        for (i in 0 until n) {
            val ax = p[i * 2]
            val ay = p[i * 2 + 1]
            val j = (i + 1) % n
            val bx = p[j * 2]
            val by = p[j * 2 + 1]
            out[o++] = ax * 0.75f + bx * 0.25f
            out[o++] = ay * 0.75f + by * 0.25f
            out[o++] = ax * 0.25f + bx * 0.75f
            out[o++] = ay * 0.25f + by * 0.75f
        }
        return out
    }
}
