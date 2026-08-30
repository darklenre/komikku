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
import android.util.Log
import android.util.Size
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a detected speech bubble out of the page image for the reader's floating Bubble Zoom.
 *
 * The bubble's detection box is decoded from the page stream at native resolution with a small
 * margin, then shaped by [ReaderPreferences.bubbleZoomCroppingMethod]:
 *  - `"none"`: a rounded rectangle.
 *  - `"sam"`: an EdgeSAM box-prompted mask ([SamRefiner]) — falls back to the rounded rectangle
 *    when SAM is unavailable.
 * The silhouette is also traced to a vector [BubbleCutout.outline] (stroked live by the overlay when
 * [ReaderPreferences.bubbleZoomOutline] is on) and its body centre is found for tail-aware framing.
 */
object BubbleExtractor {

    /** Extra margin decoded around the detection box so the balloon art that bulges past the boxed
     *  text (scallops, tail, a linked balloon's outer lobes) is inside the crop the mask is built in. */
    private const val CROP_MARGIN = 0.26f

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

        // Build the mask before recycling [cropped] — the Tier-1 flood extractor reads its pixels.
        val shape = if (method == "sam") {
            compositeMask(context, page, streamFn, bubble, expandedCrop, cropped, cropW, cropH)
        } else {
            null
        }
        if (cropped !== result) cropped.recycle()

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
            // One subpath per blob: after the union-SAM glue a linked balloon is normally one
            // connected shape, but if the glue didn't reach every lobe each disconnected piece still
            // needs its outline. The raw trace is one point per boundary pixel (a dense staircase
            // Chaikin only nibbles), so decimate first then round hard (x3).
            val contours = traceAllContours(inside, gw, gh)
            if (contours.isEmpty()) {
                null
            } else {
                Path().apply {
                    for (contour in contours) {
                        val smooth = chaikinClosed(chaikinClosed(chaikinClosed(decimateClosed(contour, 100))))
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

    /**
     * Alpha mask for [bubble] over the crop, as an `w`x`h` ARGB bitmap (upscaled onto the crop by the
     * caller). Each lobe — the whole bubble if it isn't a linked merge — is masked on its own by the
     * classical [BubbleFloodExtractor] first, falling back to [SamRefiner] per lobe; the per-lobe
     * masks are OR-composited. Null if nothing produced a mask (→ rounded-rectangle fallback).
     */
    private fun compositeMask(
        context: Context?,
        page: ReaderPage?,
        streamFn: (() -> InputStream)?,
        bubble: Bubble,
        expandedCrop: RectF?,
        crop: Bitmap,
        cropW: Int,
        cropH: Int,
    ): Bitmap? {
        val exp = expandedCrop ?: bubble.rect
        val w = min(cropW, WORK_MAX)
        val h = max(16, (cropH.toFloat() / cropW * w).toInt())
        val lobes = bubble.lobes ?: listOf(bubble.rect)
        val dbg = Log.isLoggable("BZDump", Log.VERBOSE)
        if (dbg) {
            dbgLog(bubble, exp, w, h)
            dbgBitmap(context, "crop", crop)
        }
        val acc = FloatArray(w * h)
        var any = false
        val guide by lazy { grayGuide(crop, w, h) }
        for ((li, lobe) in lobes.withIndex()) {
            val flood = runCatching { BubbleFloodExtractor.mask(crop, lobe, exp, w, h) }.getOrNull()
            var alpha = flood
            if (dbg) dbgAlpha(context, "lobe${li}_flood", flood, w, h)
            if (alpha == null) {
                // Tier-2: SAM, then snap its ~few-px-off boundary onto the real ink edge.
                val sam = samLobeAlpha(context, page, streamFn, lobe, exp, w, h)
                if (dbg) dbgAlpha(context, "lobe${li}_sam", sam, w, h)
                alpha = sam?.let { snapToEdges(it, guide, w, h) }
                if (dbg) dbgAlpha(context, "lobe${li}_sam_snapped", alpha, w, h)
            }
            if (alpha != null) {
                BubbleFloodExtractor.orInto(acc, alpha)
                any = true
            }
        }
        if (!any) return null
        if (dbg) dbgAlpha(context, "composite_lobes", acc, w, h)

        // A truly linked balloon is one connected shape, but per-lobe masks meet only where the
        // lobes happen to touch. Glue them with a single EdgeSAM call on the *union* box — SAM
        // over-connects a wide box, so its mask spans the pinch necks — kept only inside the narrow
        // bridge zones between adjacent lobes, so none of SAM's looser outer boundary leaks in.
        if (bubble.lobes != null && bubble.lobes.size > 1) {
            val bridge = bridgeZones(bubble.lobes, exp, w, h)
            if (bridge != null) {
                val union = samLobeAlpha(context, page, streamFn, bubble.rect, exp, w, h)
                if (dbg) dbgAlpha(context, "union_sam", union, w, h)
                if (union != null) {
                    for (i in acc.indices) if (bridge[i] && union[i] >= 0.5f && acc[i] < 1f) acc[i] = 1f
                }
            }
        }

        // Clip to a rounded box around the lobes: a balloon sitting against a panel frame can flood /
        // segment into the gutter and up to the frame line, which then shows in the cutout corner.
        // The real balloon fits inside the lobe boxes plus a small bulge; the frame is past that.
        clipToLobeRegion(acc, lobes, exp, w, h)
        if (dbg) dbgAlpha(context, "composite_final", acc, w, h)

        val out = IntArray(w * h)
        for (i in out.indices) {
            val a = (acc[i] * 255f).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or 0x00FFFFFF
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    /**
     * Zeroes [acc] outside a rounded rectangle around the lobe boxes (union grown ~15%, corners
     * rounded at ~30% of the short side). Removes the panel frame / gutter a mask grabbed in a
     * corner while leaving room for the balloon's own bulge past its text box.
     */
    private fun clipToLobeRegion(acc: FloatArray, lobes: List<RectF>, cropNorm: RectF, w: Int, h: Int) {
        fun sx(n: Float) = ((n - cropNorm.left) / cropNorm.width().coerceAtLeast(1e-4f) * w)
        fun sy(n: Float) = ((n - cropNorm.top) / cropNorm.height().coerceAtLeast(1e-4f) * h)
        var x0 = w.toFloat()
        var y0 = h.toFloat()
        var x1 = 0f
        var y1 = 0f
        for (l in lobes) {
            x0 = min(x0, sx(l.left))
            y0 = min(y0, sy(l.top))
            x1 = max(x1, sx(l.right))
            y1 = max(y1, sy(l.bottom))
        }
        if (x1 <= x0 || y1 <= y0) return
        val grow = 0.10f
        val gx0 = x0 - (x1 - x0) * grow
        val gy0 = y0 - (y1 - y0) * grow
        val gx1 = x1 + (x1 - x0) * grow
        val gy1 = y1 + (y1 - y0) * grow
        val rad = min(gx1 - gx0, gy1 - gy0) * 0.30f
        val ix0 = gx0 + rad
        val iy0 = gy0 + rad
        val ix1 = gx1 - rad
        val iy1 = gy1 - rad
        val rad2 = rad * rad
        for (y in 0 until h) {
            val ry = max(max(iy0 - y, y - iy1), 0f)
            for (x in 0 until w) {
                val i = y * w + x
                if (acc[i] <= 0f) continue
                val rx = max(max(ix0 - x, x - ix1), 0f)
                if (rx * rx + ry * ry > rad2) acc[i] = 0f
            }
        }
    }

    /**
     * Mask of the "bridge zones": for every pair of adjacent lobes (a small facing gap, well
     * overlapped on the other axis) a rectangle covering that gap plus a few px into each lobe, over
     * the overlap band. Null if no pair qualifies. Used to confine the union-SAM glue in [compositeMask].
     */
    private fun bridgeZones(lobes: List<RectF>, cropNorm: RectF, w: Int, h: Int): BooleanArray? {
        fun sx(n: Float) = ((n - cropNorm.left) / cropNorm.width().coerceAtLeast(1e-4f) * w)
        fun sy(n: Float) = ((n - cropNorm.top) / cropNorm.height().coerceAtLeast(1e-4f) * h)
        val mask = BooleanArray(w * h)
        var any = false
        val pad = max(4, min(w, h) / 40)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
            val cx0 = x0.coerceIn(0, w - 1)
            val cy0 = y0.coerceIn(0, h - 1)
            val cx1 = x1.coerceIn(cx0 + 1, w)
            val cy1 = y1.coerceIn(cy0 + 1, h)
            for (y in cy0 until cy1) for (x in cx0 until cx1) mask[y * w + x] = true
            any = true
        }
        for (i in lobes.indices) {
            for (j in i + 1 until lobes.size) {
                val a = lobes[i]
                val b = lobes[j]
                val axl = sx(a.left)
                val axr = sx(a.right)
                val ayt = sy(a.top)
                val ayb = sy(a.bottom)
                val bxl = sx(b.left)
                val bxr = sx(b.right)
                val byt = sy(b.top)
                val byb = sy(b.bottom)
                val ovX = min(axr, bxr) - max(axl, bxl)
                val ovY = min(ayb, byb) - max(ayt, byt)
                if (ovX > 0f) { // stacked
                    val gap = max(ayt, byt) - min(ayb, byb)
                    val nearer = min(ayb - ayt, byb - byt)
                    if (gap <= nearer * 0.7f) {
                        val lo = min(ayb, byb) - pad
                        val hi = max(ayt, byt) + pad
                        fill(max(axl, bxl).toInt(), lo.toInt(), min(axr, bxr).toInt(), hi.toInt())
                    }
                } else if (ovY > 0f) { // side by side
                    val gap = max(axl, bxl) - min(axr, bxr)
                    val nearer = min(axr - axl, bxr - bxl)
                    if (gap <= nearer * 0.7f) {
                        val lo = min(axr, bxr) - pad
                        val hi = max(axl, bxl) + pad
                        fill(lo.toInt(), max(ayt, byt).toInt(), hi.toInt(), min(ayb, byb).toInt())
                    }
                }
            }
        }
        return if (any) mask else null
    }

    /** One lobe's EdgeSAM box-prompted alpha (0..1, `w`x`h`), or null if SAM is unavailable. */
    private fun samLobeAlpha(
        context: Context?,
        page: ReaderPage?,
        streamFn: (() -> InputStream)?,
        lobeNorm: RectF,
        cropNorm: RectF,
        w: Int,
        h: Int,
    ): FloatArray? {
        if (context == null || page == null || streamFn == null) return null
        val bmp = runCatching {
            SamRefiner.shapeMask(
                context = context,
                pageKey = bubbleKeyFor(page),
                streamFn = streamFn,
                boxNorm = lobeNorm,
                cropNorm = cropNorm,
                outW = w,
                outH = h,
            )
        }.getOrNull() ?: return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return FloatArray(px.size) { (px[it] ushr 24) / 255f }
    }

    /** Grayscale (0..1) of [crop] resampled to `w`x`h`, as the guide image for [snapToEdges]. */
    private fun grayGuide(crop: Bitmap, w: Int, h: Int): FloatArray {
        val scaled = if (crop.width == w && crop.height == h) crop else Bitmap.createScaledBitmap(crop, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== crop) scaled.recycle()
        return FloatArray(px.size) {
            val p = px[it]
            (0.299f * ((p ushr 16) and 0xFF) + 0.587f * ((p ushr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
    }

    /**
     * Guided-filter edge refinement: pulls the soft mask [p]'s boundary onto the guide image's own
     * edges (the balloon's ink outline), then re-thresholds. The result is clamped to a few-px
     * dilation of [p] so it can only tighten / nudge the edge, never balloon out to an unrelated
     * strong edge (a panel frame the balloon sits against). Where the guide has no edge it degrades
     * to a light smoothing, so it is safe to apply unconditionally.
     */
    private fun snapToEdges(p: FloatArray, guide: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val r = max(2, min(w, h) / 48)
        val eps = 1e-3f
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val ip = FloatArray(n) { guide[it] * p[it] }
        val mI = boxBlur(guide, w, h, r)
        val mP = boxBlur(p, w, h, r)
        val mII = boxBlur(ii, w, h, r)
        val mIP = boxBlur(ip, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = mII[i] - mI[i] * mI[i]
            val covIp = mIP[i] - mI[i] * mP[i]
            a[i] = covIp / (varI + eps)
            b[i] = mP[i] - a[i] * mI[i]
        }
        val mA = boxBlur(a, w, h, r)
        val mB = boxBlur(b, w, h, r)
        // Allow-band: p thresholded and blurred by ~2r, kept where it's still non-zero = a cheap dilation.
        val hard = FloatArray(n) { if (p[it] >= 0.5f) 1f else 0f }
        val allow = boxBlur(hard, w, h, 2 * r + 1)
        val out = FloatArray(n)
        for (i in 0 until n) {
            if (allow[i] < 1e-3f) {
                out[i] = 0f
                continue
            }
            val q = mA[i] * guide[i] + mB[i]
            out[i] = if (q >= 0.5f) 1f else 0f
        }
        return out
    }

    /** Separable box blur of a full `w`x`h` plane (radius [r]), edge-clamped. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val dst = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (k in -r..r) s += src[row + (x + k).coerceIn(0, w - 1)]
                tmp[row + x] = s * norm
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var s = 0f
                for (k in -r..r) s += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                dst[y * w + x] = s * norm
            }
        }
        return dst
    }

    // ---- debug dump: `adb shell setprop log.tag.BZDump VERBOSE`, files under
    //      getExternalFilesDir("bzdump"); `adb pull /sdcard/Android/data/<pkg>/files/bzdump` ----

    @Volatile private var dbgId = ""

    private fun dbgDir(context: Context?): File? =
        context?.getExternalFilesDir("bzdump")?.also { it.mkdirs() }

    private fun dbgLog(bubble: Bubble, exp: RectF, w: Int, h: Int) {
        dbgId = System.currentTimeMillis().toString()
        Log.v("BZDump", "id=$dbgId rect=${bubble.rect.toShortString()} exp=${exp.toShortString()} work=${w}x$h lobes=${bubble.lobes?.size ?: 1}")
        val lobes = bubble.lobes ?: return
        for (i in lobes.indices) {
            for (j in i + 1 until lobes.size) {
                val a = lobes[i]
                val b = lobes[j]
                val gapY = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
                val gapX = maxOf(a.left, b.left) - minOf(a.right, b.right)
                Log.v(
                    "BZDump",
                    "  id=$dbgId lobe$i-$j gapY=$gapY (hA=${a.height()} hB=${b.height()}) " +
                        "gapX=$gapX (wA=${a.width()} wB=${b.width()})",
                )
            }
        }
    }

    private fun dbgBitmap(context: Context?, tag: String, bmp: Bitmap) {
        val dir = dbgDir(context) ?: return
        runCatching {
            FileOutputStream(File(dir, "${dbgId}_$tag.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun dbgAlpha(context: Context?, tag: String, a: FloatArray?, w: Int, h: Int) {
        a ?: return
        val dir = dbgDir(context) ?: return
        val px = IntArray(w * h) {
            val v = (a[it] * 255f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        runCatching {
            FileOutputStream(File(dir, "${dbgId}_$tag.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bmp.recycle()
    }

    /**
     * Boundary trace of every opaque blob in [inside] (each ≥ 16 cells), one closed pixel-centre
     * polygon per blob, so a linked balloon whose lobes stay disconnected still gets an outline on
     * each. Order follows row-major discovery of the blobs.
     */
    internal fun traceAllContours(inside: BooleanArray, w: Int, h: Int): List<FloatArray> {
        val work = inside.copyOf()
        val out = ArrayList<FloatArray>()
        val q = ArrayDeque<Int>()
        val dx4 = intArrayOf(1, -1, 0, 0)
        val dy4 = intArrayOf(0, 0, 1, -1)
        while (true) {
            var seed = -1
            for (i in work.indices) {
                if (work[i]) {
                    seed = i
                    break
                }
            }
            if (seed < 0) break
            val comp = BooleanArray(work.size)
            work[seed] = false
            comp[seed] = true
            q.addLast(seed)
            var area = 0
            while (q.isNotEmpty()) {
                val i = q.removeFirst()
                area++
                val x = i % w
                val y = i / w
                for (d in 0 until 4) {
                    val nx = x + dx4[d]
                    val ny = y + dy4[d]
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val ni = ny * w + nx
                    if (!work[ni]) continue
                    work[ni] = false
                    comp[ni] = true
                    q.addLast(ni)
                }
            }
            if (area >= 16) traceContour(comp, w, h)?.let { out.add(it) }
        }
        return out
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

    /**
     * Keeps ~[targetPts] evenly spaced vertices of a closed polygon (x,y pairs). Returned unchanged
     * if it already has fewer, or if [targetPts] is too small to describe a shape.
     */
    internal fun decimateClosed(p: FloatArray, targetPts: Int): FloatArray {
        val n = p.size / 2
        if (targetPts < 4 || n <= targetPts) return p
        val step = n.toFloat() / targetPts
        val out = FloatArray(targetPts * 2)
        var o = 0
        var k = 0
        while (k < targetPts) {
            val idx = (k * step).toInt().coerceIn(0, n - 1)
            out[o++] = p[idx * 2]
            out[o++] = p[idx * 2 + 1]
            k++
        }
        return out
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
