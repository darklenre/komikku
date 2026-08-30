package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tier-1 speech-balloon extractor: a classical flood fill of the balloon's uniform light interior,
 * bounded by its dark ink outline. Pixel-exact for the common case (white/light balloon, closed
 * border) and ~free (no model, no GPU). Returns null — so the caller falls back to [SamRefiner] —
 * for anything it can't do confidently: coloured / borderless / dark balloons, balloons over a
 * white background, heavy texture. Everything is judged against a validation gate so a wrong shape
 * is never returned in place of a fallback.
 */
object BubbleFloodExtractor {

    /** Luma at/above this (0..255) can be balloon fill; below is treated as ink / art. */
    private const val MIN_FILL_LUMA = 188f

    /** Fill must be near-grey: max-min channel spread at/below this. */
    private const val MAX_FILL_SAT = 46f

    /** A pixel joins the fill if its luma is within this of the sampled fill luma. */
    private const val LUMA_TOL = 42f

    /** The fill's own detected saturation must not exceed this. */
    private const val SEED_SAT_TOL = 40f

    /** Bound the fill to the lobe box grown by this fraction each side (stops a broken border leaking). */
    private const val BOUND_GROW = 0.6f

    /** Fraction of the sampling window a luma bin must cover to be accepted as the fill mode. */
    private const val FILL_MODE_MIN_FRAC = 0.10f

    /**
     * Alpha (0..1, `outW`x`outH`) of the balloon [lobeNorm] within the crop bitmap, whose extent is
     * [cropNorm] (both normalised 0..1 of the page). Null if the classical fill isn't confident.
     */
    fun mask(crop: Bitmap, lobeNorm: RectF, cropNorm: RectF, outW: Int, outH: Int): FloatArray? {
        if (outW < 16 || outH < 16) return null
        val work = if (crop.width == outW && crop.height == outH) {
            crop
        } else {
            Bitmap.createScaledBitmap(crop, outW, outH, true)
        }
        val px = IntArray(outW * outH)
        work.getPixels(px, 0, outW, 0, 0, outW, outH)
        if (work !== crop) work.recycle()

        val luma = FloatArray(px.size)
        val sat = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            sat[i] = (max(r, max(g, b)) - min(r, min(g, b))).toFloat()
        }

        fun cx(n: Float) = ((n - cropNorm.left) / cropNorm.width().coerceAtLeast(1e-4f) * outW)
        fun cy(n: Float) = ((n - cropNorm.top) / cropNorm.height().coerceAtLeast(1e-4f) * outH)
        val lx0 = cx(lobeNorm.left).coerceIn(0f, outW - 1f)
        val ly0 = cy(lobeNorm.top).coerceIn(0f, outH - 1f)
        val lx1 = cx(lobeNorm.right).coerceIn(lx0 + 1f, outW.toFloat())
        val ly1 = cy(lobeNorm.bottom).coerceIn(ly0 + 1f, outH.toFloat())
        val lobeW = lx1 - lx0
        val lobeH = ly1 - ly0

        // Sample the inner lobe box for the fill's luma mode.
        val ix0 = (lx0 + lobeW * 0.18f).toInt().coerceIn(0, outW - 1)
        val iy0 = (ly0 + lobeH * 0.18f).toInt().coerceIn(0, outH - 1)
        val ix1 = (lx1 - lobeW * 0.18f).toInt().coerceIn(ix0 + 1, outW)
        val iy1 = (ly1 - lobeH * 0.18f).toInt().coerceIn(iy0 + 1, outH)
        val bins = IntArray(32)
        var samples = 0
        var satSum = 0f
        var satCount = 0
        for (y in iy0 until iy1 step max(1, (iy1 - iy0) / 24)) {
            for (x in ix0 until ix1 step max(1, (ix1 - ix0) / 24)) {
                val i = y * outW + x
                samples++
                if (luma[i] >= MIN_FILL_LUMA) {
                    bins[(luma[i].toInt() / 8).coerceIn(0, 31)]++
                    satSum += sat[i]
                    satCount++
                }
            }
        }
        if (samples == 0 || satCount < samples * 0.25f) return null
        var bestBin = -1
        var bestCount = 0
        for (b in 31 downTo 24) {
            if (bins[b] > bestCount) {
                bestCount = bins[b]
                bestBin = b
            }
        }
        if (bestBin < 0 || bestCount < samples * FILL_MODE_MIN_FRAC) return null
        val fillLuma = bestBin * 8f + 4f
        if (satCount > 0 && satSum / satCount > SEED_SAT_TOL) return null

        fun isFill(i: Int) = luma[i] >= fillLuma - LUMA_TOL &&
            luma[i] <= 255f &&
            sat[i] <= MAX_FILL_SAT

        // Bound region: lobe box grown, clamped.
        val bl = (lx0 - lobeW * BOUND_GROW).toInt().coerceIn(0, outW - 1)
        val bt = (ly0 - lobeH * BOUND_GROW).toInt().coerceIn(0, outH - 1)
        val br = (lx1 + lobeW * BOUND_GROW).toInt().coerceIn(bl + 1, outW)
        val bb = (ly1 + lobeH * BOUND_GROW).toInt().coerceIn(bt + 1, outH)
        val boundArea = (br - bl) * (bb - bt)

        // Seeds: grid inside the inner lobe box that matches the fill.
        val inside = BooleanArray(px.size)
        val queue = ArrayDeque<Int>()
        var seeds = 0
        for (sy in 0 until 5) {
            for (sx in 0 until 5) {
                val x = (ix0 + (ix1 - ix0) * sx / 4).coerceIn(ix0, ix1 - 1)
                val y = (iy0 + (iy1 - iy0) * sy / 4).coerceIn(iy0, iy1 - 1)
                val i = y * outW + x
                if (!inside[i] && isFill(i)) {
                    inside[i] = true
                    queue.addLast(i)
                    seeds++
                }
            }
        }
        if (seeds < 3) return null

        // Flood the interior, 8-connected, within the bound region.
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % outW
            val y = i / outW
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < bl || nx >= br || ny < bt || ny >= bb) continue
                    val ni = ny * outW + nx
                    if (inside[ni] || !isFill(ni)) continue
                    inside[ni] = true
                    queue.addLast(ni)
                }
            }
        }

        // Fill text holes: flood "outside" from the bound border over non-fill pixels; a bound-region
        // pixel the outside flood never reaches is an interior hole.
        val outside = BooleanArray(px.size)
        for (x in bl until br) {
            for (y in intArrayOf(bt, bb - 1)) {
                val i = y * outW + x
                if (!inside[i] && !outside[i]) {
                    outside[i] = true
                    queue.addLast(i)
                }
            }
        }
        for (y in bt until bb) {
            for (x in intArrayOf(bl, br - 1)) {
                val i = y * outW + x
                if (!inside[i] && !outside[i]) {
                    outside[i] = true
                    queue.addLast(i)
                }
            }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % outW
            val y = i / outW
            for (d in 0 until 4) {
                val nx = x + intArrayOf(1, -1, 0, 0)[d]
                val ny = y + intArrayOf(0, 0, 1, -1)[d]
                if (nx < bl || nx >= br || ny < bt || ny >= bb) continue
                val ni = ny * outW + nx
                if (inside[ni] || outside[ni]) continue
                outside[ni] = true
                queue.addLast(ni)
            }
        }
        val solid = BooleanArray(px.size)
        var area = 0
        var minX = br
        var minY = bb
        var maxX = bl
        var maxY = bt
        var touchL = false
        var touchR = false
        var touchT = false
        var touchB = false
        for (y in bt until bb) {
            for (x in bl until br) {
                val i = y * outW + x
                if (inside[i] || !outside[i]) {
                    solid[i] = true
                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (x == bl) touchL = true
                    if (x == br - 1) touchR = true
                    if (y == bt) touchT = true
                    if (y == bb - 1) touchB = true
                }
            }
        }

        // Validation gate — otherwise return null and let SAM handle it.
        if (area < boundArea * 0.10f || area > boundArea * 0.92f) return null
        if (touchL && touchR && touchT && touchB) return null // filled the whole bound = border leak
        val bboxArea = (maxX - minX + 1).toFloat() * (maxY - minY + 1)
        if (bboxArea <= 0f || area / bboxArea < 0.5f) return null // too stringy for a balloon
        // The balloon must enclose most of its own text box.
        val coverL = max(minX.toFloat(), lx0)
        val coverT = max(minY.toFloat(), ly0)
        val coverR = min(maxX + 1f, lx1)
        val coverB = min(maxY + 1f, ly1)
        val covered = max(0f, coverR - coverL) * max(0f, coverB - coverT)
        if (covered < lobeW * lobeH * 0.75f) return null

        val alpha = FloatArray(px.size)
        for (i in solid.indices) if (solid[i]) alpha[i] = 1f
        return alpha
    }

    /** OR-composite [b] into [a] (both `n` long), keeping the larger alpha. */
    internal fun orInto(a: FloatArray, b: FloatArray) {
        for (i in a.indices) if (b[i] > a[i]) a[i] = b[i]
    }

    /** Rough px size for a mask window that keeps a [nativeLong]-px crop under [cap]. */
    internal fun workLong(nativeLong: Int, cap: Int): Int = min(cap, max(16, nativeLong)).let {
        // round to even for clean bilinear halving
        (it / 2.0).roundToInt() * 2
    }
}
