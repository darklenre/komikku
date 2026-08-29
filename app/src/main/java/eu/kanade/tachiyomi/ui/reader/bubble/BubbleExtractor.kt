package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Size
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlin.math.max
import kotlin.math.min

object BubbleExtractor {

    /** The crop is decoded with this fraction of extra margin so the bubble's real outline
     *  (scallops, tail) that spills past the tight detection box has room, and so the CV pass has
     *  exterior context to find the edge against. */
    private const val CROP_MARGIN = 0.22f

    /** Working resolution for the CV mask refinement. */
    private const val WORK_MAX = 400

    /**
     * Extracts a speech bubble as a high-resolution [Bitmap] directly from the [page] image stream,
     * cut to the bubble shape (neural proto mask refined against the real pixels).
     */
    fun extractBubble(page: ReaderPage, bubble: Bubble, sourceSize: Size?): Bitmap? {
        val streamFn = page.stream ?: return null

        // Expand the tight detection box by a margin, clamped to the page.
        val bw = bubble.rect.width()
        val bh = bubble.rect.height()
        val exp = RectF(
            (bubble.rect.left - CROP_MARGIN * bw).coerceAtLeast(0f),
            (bubble.rect.top - CROP_MARGIN * bh).coerceAtLeast(0f),
            (bubble.rect.right + CROP_MARGIN * bw).coerceAtMost(1f),
            (bubble.rect.bottom + CROP_MARGIN * bh).coerceAtMost(1f),
        )
        // Where the tight box sits inside the expanded crop, as a 0..1 fraction.
        val inner = RectF(
            (bubble.rect.left - exp.left) / exp.width(),
            (bubble.rect.top - exp.top) / exp.height(),
            (bubble.rect.right - exp.left) / exp.width(),
            (bubble.rect.bottom - exp.top) / exp.height(),
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

        return processCroppedBitmap(rawCropped, bubble, inner)
    }

    /**
     * Cuts [cropped] to the bubble shape. [innerFrac] is the detection box as a 0..1 fraction of
     * [cropped] (the neural mask covers that sub-rect); when null the whole crop is used.
     * With no neural mask (detect-only engines) the result is a plain rounded rectangle.
     */
    fun processCroppedBitmap(cropped: Bitmap, bubble: Bubble, innerFrac: RectF? = null): Bitmap {
        val cropW = cropped.width
        val cropH = cropped.height
        if (cropW <= 8 || cropH <= 8) return cropped

        val result = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(cropped, 0f, 0f, null)

        val mask = bubble.maskBitmap
        if (mask == null) {
            // Detect-only engine (ogkalu): no shape info, keep the plain rounded rectangle.
            val r = RectF(0f, 0f, cropW.toFloat(), cropH.toFloat())
            val rr = min(cropW, cropH) * 0.12f
            val layer = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
            Canvas(layer).drawRoundRect(r, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.drawBitmap(
                layer,
                0f,
                0f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
            )
            layer.recycle()
            return result
        }

        // seg engine: cut to the bubble shape. Never a rectangle — CV-refined mask, or the plain
        // proto mask as a bubble-ish fallback.
        val shape = runCatching { buildShapeMask(cropped, mask, innerFrac) }.getOrNull()
            ?: runCatching { simpleProtoMask(cropW, cropH, mask, innerFrac) }.getOrNull()
        if (shape != null) {
            canvas.drawBitmap(
                shape,
                null,
                RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
            )
            shape.recycle()
            strokeAlphaEdge(result)
        }
        return result
    }

    /** Plain proto mask scaled into [innerFrac] and thresholded — bubble-shaped, never a rectangle. */
    private fun simpleProtoMask(cropW: Int, cropH: Int, mask: Bitmap, innerFrac: RectF?): Bitmap {
        val inner = innerFrac ?: RectF(0f, 0f, 1f, 1f)
        val iL = (inner.left * cropW).toInt().coerceIn(0, cropW - 1)
        val iT = (inner.top * cropH).toInt().coerceIn(0, cropH - 1)
        val iR = (inner.right * cropW).toInt().coerceIn(iL + 1, cropW)
        val iB = (inner.bottom * cropH).toInt().coerceIn(iT + 1, cropH)
        val ms = Bitmap.createScaledBitmap(mask, iR - iL, iB - iT, true)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val mpx = IntArray((iR - iL) * (iB - iT))
        ms.getPixels(mpx, 0, iR - iL, 0, 0, iR - iL, iB - iT)
        ms.recycle()
        for (i in mpx.indices) mpx[i] = if ((mpx[i] ushr 24) >= 128) -0x1 else 0x00FFFFFF
        out.setPixels(mpx, 0, iR - iL, iL, iT, iR - iL, iB - iT)
        return out
    }

    /** Paints a thin dark rim along the alpha boundary of [bmp] so the cut bubble reads as an outline. */
    private fun strokeAlphaEdge(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val p = IntArray(w * h)
        bmp.getPixels(p, 0, w, 0, 0, w, h)
        val ring = max(2, (min(w, h) * 0.012f).toInt())

        val opaque = BooleanArray(w * h) { (p[it] ushr 24) >= 128 }
        // Erode `ring` times; the rim is opaque pixels that erosion removes.
        var cur = opaque.copyOf()
        repeat(ring) {
            val nxt = cur.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!cur[i]) continue
                    if (x == 0 || x == w - 1 || y == 0 || y == h - 1 ||
                        !cur[i - 1] || !cur[i + 1] || !cur[i - w] || !cur[i + w]
                    ) {
                        nxt[i] = false
                    }
                }
            }
            cur = nxt
        }
        for (i in p.indices) if (opaque[i] && !cur[i]) p[i] = 0xFF1A1A1A.toInt()
        bmp.setPixels(p, 0, w, 0, 0, w, h)
    }

    /**
     * Coarse proto [mask] (placed at [innerFrac] of the crop) refined against [cropped]'s real
     * pixels: grow the coarse region over the bright bubble interior and one dark-outline ring,
     * bounded to just outside the detection box so it can't leak into panel gutters. Returns an
     * alpha mask sized to the working resolution (the caller scales it to the crop).
     */
    private fun buildShapeMask(cropped: Bitmap, mask: Bitmap, innerFrac: RectF?): Bitmap? {
        val cw = cropped.width
        val ch = cropped.height
        val scale = min(1f, WORK_MAX.toFloat() / max(cw, ch))
        val w = max(16, (cw * scale).toInt())
        val h = max(16, (ch * scale).toInt())

        val small = Bitmap.createScaledBitmap(cropped, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != cropped) small.recycle()

        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }

        // Coarse seed: proto mask scaled into the inner box, thresholded.
        val inner = innerFrac ?: RectF(0f, 0f, 1f, 1f)
        val iL = (inner.left * w).toInt().coerceIn(0, w - 1)
        val iT = (inner.top * h).toInt().coerceIn(0, h - 1)
        val iR = (inner.right * w).toInt().coerceIn(iL + 1, w)
        val iB = (inner.bottom * h).toInt().coerceIn(iT + 1, h)
        val iw = iR - iL
        val ih = iB - iT
        val ms = Bitmap.createScaledBitmap(mask, iw, ih, true)
        val mpx = IntArray(iw * ih)
        ms.getPixels(mpx, 0, iw, 0, 0, iw, ih)
        ms.recycle()

        val seed = BooleanArray(w * h)
        var seedCount = 0
        var seedLumSum = 0f
        for (y in 0 until ih) {
            for (x in 0 until iw) {
                if ((mpx[y * iw + x] ushr 24) >= 128) {
                    val idx = (iT + y) * w + (iL + x)
                    seed[idx] = true
                    seedCount++
                    seedLumSum += lum[idx]
                }
            }
        }
        if (seedCount < 6) return null

        // Bright = the bubble interior. Bias the threshold below the seed's mean luminance.
        val brightThr = (seedLumSum / seedCount) * 0.66f
        // Allow the region to reach a little outside the detection box, but not far (gutter guard).
        val slackX = (iw * 0.10f).toInt().coerceAtLeast(2)
        val slackY = (ih * 0.10f).toInt().coerceAtLeast(2)
        val minX = (iL - slackX).coerceAtLeast(0)
        val maxX = (iR + slackX).coerceAtMost(w)
        val minY = (iT - slackY).coerceAtLeast(0)
        val maxY = (iB + slackY).coerceAtMost(h)
        val outlinePx = max(2, (min(w, h) * 0.02f).toInt())

        val region = seed.copyOf()
        val darkDist = IntArray(w * h) // steps taken through dark pixels since the last bright one
        val queue = ArrayDeque<Int>()
        for (i in seed.indices) if (seed[i]) queue.addLast(i)

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % w
            val y = idx / w
            val d = darkDist[idx]
            val neigh = intArrayOf(
                if (x > 0) idx - 1 else -1,
                if (x < w - 1) idx + 1 else -1,
                if (y > 0) idx - w else -1,
                if (y < h - 1) idx + w else -1,
            )
            for (n in neigh) {
                if (n < 0 || region[n]) continue
                val nx = n % w
                val ny = n / w
                if (nx < minX || nx >= maxX || ny < minY || ny >= maxY) continue
                if (lum[n] >= brightThr) {
                    region[n] = true
                    darkDist[n] = 0
                    queue.addLast(n)
                } else if (d < outlinePx) {
                    region[n] = true
                    darkDist[n] = d + 1
                    queue.addLast(n)
                }
            }
        }

        var count = 0
        for (i in region.indices) if (region[i]) count++
        val boxArea = iw * ih
        if (count < boxArea * 0.15f || count > boxArea * 2f) return null

        val out = IntArray(w * h)
        for (i in out.indices) out[i] = if (region[i]) -0x1 else 0x00FFFFFF
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }
}
