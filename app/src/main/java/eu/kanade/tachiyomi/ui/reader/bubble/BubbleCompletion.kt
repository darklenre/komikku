package eu.kanade.tachiyomi.ui.reader.bubble

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shape completion for linked balloons. Two balloons that share one fill are separated by the mask
 * along a cut through that fill, which leaves the cutout open and ragged on that side.
 *
 * [completeNeck] (tried first) finds where the shared shape pinches between the two texts and closes the
 * balloon there, whatever path the mask's cut took (it often runs along the last line of letters).
 * [complete] (fallback) starts from the mask's own cut instead. Both rebuild the missing stretch of outline is rebuilt as a smooth curve continuing the balloon's own
 * contour at both ends (Hermite spline with the contour tangents), the new bulge is painted with the
 * balloon's fill and the rebuilt boundary gets an outline in the balloon's ink colour — so the cutout
 * reads as a whole, standalone balloon.
 *
 * Pure (no Android types); all coordinates are crop px, grids row-major `w`x`h`.
 */
object BubbleCompletion {

    /** [linked]: the neighbour text box the balloon was separated from, when known. */
    class Result(val mask: BooleanArray, val pixels: IntArray, val linked: FloatArray? = null)

    /**
     * @param argb crop pixels (ARGB), [luma] their 0..255 luminance, [m] the balloon mask
     * @param others the other balloons' boxes `[l,t,r,b]`; a cut only counts when the light area beyond
     *   it belongs to one of them (otherwise it is an open edge, not a linked balloon)
     * @param ownText this balloon's text boxes, to tell which side of the cut is ours
     * @return the completed mask and repainted pixels, or null when there is nothing (safe) to complete
     */
    fun complete(
        argb: IntArray,
        luma: FloatArray,
        m: BooleanArray,
        w: Int,
        h: Int,
        others: List<FloatArray>,
        ownText: List<FloatArray>,
    ): Result? {
        val inner = erode4(m, w, h, 3)
        val fill = medianOf(luma, inner) ?: return null
        if (fill < MIN_FILL) return null
        val ink = BooleanArray(w * h) { luma[it] < fill - max(60f, 0.35f * fill) }
        val brightOut = BooleanArray(w * h) { !m[it] && luma[it] > fill - 40f }
        val edge = edgeOf(m, w, h)
        val brightNear = dilate4(brightOut, w, h, 1)
        val cutPx = BooleanArray(w * h) { edge[it] && luma[it] > fill - 40f && brightNear[it] }
        val cut = findCut(cutPx, ink, brightOut, w, h, others) ?: return null
        val ax = cut.ax
        val ay = cut.ay
        val bx = cut.bx
        val by = cut.by
        val ab = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        if (ab < 6f) return null
        val nx = -(by - ay) / ab
        val ny = (bx - ax) / ab

        // which side of the chord A-B is ours: our text, else the mask's centroid
        var tcx = 0f
        var tcy = 0f
        if (ownText.isNotEmpty()) {
            for (t in ownText) {
                tcx += (t[0] + t[2]) / 2
                tcy += (t[1] + t[3]) / 2
            }
            tcx /= ownText.size
            tcy /= ownText.size
        } else {
            var n = 0
            for (i in m.indices) {
                if (!m[i]) continue
                tcx += i % w
                tcy += i / w
                n++
            }
            tcx /= max(n, 1)
            tcy /= max(n, 1)
        }
        val sText = if ((tcx - ax) * nx + (tcy - ay) * ny < 0f) -1f else 1f
        var dev = 0f
        for (p in cut.run) dev = max(dev, abs((p % w - ax) * nx + (p / w - ay) * ny))
        val ext = 0.15f * ab + 3f
        val side = FloatArray(w * h)
        val farZone = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val s = (x - ax) * nx + (y - ay) * ny
            side[i] = s
            val along = ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / ab
            farZone[i] = s * sText < 0f && along >= -ext && along <= ab + ext && abs(s) <= dev + ext
        }
        val keep = BooleanArray(w * h) { m[it] && !farZone[it] }

        // tangents from the balloon's own outline near each end (original edge minus the cut run)
        val runMask = BooleanArray(w * h).also { r -> cut.run.forEach { r[it] = true } }
        val runNear = dilate4(runMask, w, h, 3)
        val outline = BooleanArray(w * h) { edge[it] && !runNear[it] }
        val reach = max(5f, 0.25f * ab)
        val ta = tangent(outline, w, h, ax, ay, bx, by, reach) ?: return null
        val tb = tangent(outline, w, h, bx, by, ax, ay, reach) ?: return null

        // the new stretch of outline, and the bulge between it and the chord
        val curve = hermite(ax, ay, bx, by, ta[0] * ab, ta[1] * ab, -tb[0] * ab, -tb[1] * ab, CURVE_POINTS)
        var meanSide = 0f
        for (k in 0 until CURVE_POINTS) {
            meanSide += ((curve[2 * k] - ax) * nx + (curve[2 * k + 1] - ay) * ny) * sText
        }
        if (meanSide > 0f) return null // would bulge into our own balloon
        val bulge = fillPolygon(curve, w, h)

        var m2 = fillHoles(BooleanArray(w * h) { keep[it] || bulge[it] }, w, h)
        val trim = 0.35f * ab + 4f
        for ((px, py, tv) in listOf(Triple(ax, ay, ta), Triple(bx, by, tb))) {
            // nothing may stick out past the outline's tangent line near the junction
            var qx = -tv[1]
            var qy = tv[0]
            if ((tcx - px) * qx + (tcy - py) * qy < 0f) {
                qx = -qx
                qy = -qy
            }
            for (i in m2.indices) {
                if (!m2[i]) continue
                val dx = i % w - px
                val dy = i / w - py
                if (dx * dx + dy * dy <= trim * trim && dx * qx + dy * qy < -1f) m2[i] = false
            }
        }
        m2 = largestBlob(m2, w, h)
        val before = m.count { it }
        val after = m2.count { it }
        if (after > 1.35f * before || after < 0.8f * before) return null

        // outline look: thickness = ink band area / edge length; colour = the dark core of the stroke
        val edgeBand = dilate4(edge, w, h, 6)
        val band = BooleanArray(w * h) { ink[it] && m[it] && edgeBand[it] }
        val bandN = band.count { it }
        val edgeN = edge.count { it }
        val t = (bandN.toFloat() / max(edgeN, 1)).coerceIn(1f, max(1f, 0.03f * min(w, h))).coerceAtLeast(1.5f)
        val inkRgb = if (bandN > 0) {
            val cutoff = percentile(luma, band, 0.3f)
            medianRgb(argb, BooleanArray(w * h) { band[it] && luma[it] <= cutoff })
        } else {
            0x141414
        }
        val fillRgb = medianRgb(argb, BooleanArray(w * h) { inner[it] && luma[it] > fill - 20f })

        val out = argb.copyOf()
        val ti = ceil(t).toInt()
        val zone = dilate4(BooleanArray(w * h) { bulge[it] || farZone[it] }, w, h, ti + 1)
        val keepCore = erode4(keep, w, h, ti + 2)
        for (i in out.indices) {
            if (m2[i] && zone[i] && !keepCore[i] && (!ink[i] || bulge[i])) out[i] = (out[i] and 0xFF000000.toInt()) or fillRgb
        }
        // outline along the rebuilt boundary (bulge and both junctions), coverage from the distance
        // to the boundary so it joins the original stroke seamlessly
        val dist = chamferInside(m2, w, h)
        val rebuilt = dilate4(bulge, w, h, ti + 2)
        for (i in out.indices) {
            if (!m2[i]) continue
            val dx1 = i % w - ax
            val dy1 = i / w - ay
            val dx2 = i % w - bx
            val dy2 = i / w - by
            val near = rebuilt[i] || dx1 * dx1 + dy1 * dy1 <= trim * trim || dx2 * dx2 + dy2 * dy2 <= trim * trim
            if (!near) continue
            val cov = (t + 0.5f - dist[i]).coerceIn(0f, 1f)
            if (cov > 0f) out[i] = (out[i] and 0xFF000000.toInt()) or blend(out[i], inkRgb, cov)
        }
        return Result(m2, out)
    }

    /**
     * Neck-based completion. F = the fill shared with our text (light pixels plus text boxes); when it
     * also holds a neighbour's text, the balloons are linked and the neck notches are, on each side of
     * the axis between the two texts, the F-boundary points closest to that axis. The chord between the
     * notches separates the balloons; only the area around it changes, the mask's edge elsewhere is kept.
     *
     * @param ownText this balloon's text boxes, [otherText] the other balloons' text boxes (crop px)
     * @return the completed mask and repainted pixels, or null when the balloon is not cut at a neck
     */
    fun completeNeck(
        argb: IntArray,
        luma: FloatArray,
        m: BooleanArray,
        w: Int,
        h: Int,
        ownText: List<FloatArray>,
        otherText: List<FloatArray>,
    ): Result? {
        if (ownText.isEmpty()) return null
        val n = w * h
        val inner = erode4(m, w, h, 3)
        val fill = medianOf(luma, inner) ?: return null
        if (fill < MIN_FILL) return null
        val ink = BooleanArray(n) { luma[it] < fill - max(60f, 0.35f * fill) }
        val own = boxMask(ownText, w, h, 1)
        val near = otherText.filter { it[2] > 0 && it[0] < w && it[3] > 0 && it[1] < h }
        if (near.isEmpty()) return null
        val nearBox = boxMask(near, w, h, 1)
        val light = BooleanArray(n) { luma[it] > fill - 40f || own[it] || nearBox[it] }
        val lab = labels(light, w, h)
        val ids = HashSet<Int>()
        for (i in 0 until n) if (own[i] && lab[i] > 0) ids += lab[i]
        if (ids.isEmpty()) return null
        val f = fillHoles(BooleanArray(n) { lab[it] in ids }, w, h)

        var c1x = 0f
        var c1y = 0f
        for (t in ownText) {
            c1x += (t[0] + t[2]) / 2
            c1y += (t[1] + t[3]) / 2
        }
        c1x /= ownText.size
        c1y /= ownText.size
        // the neighbour whose text sits in the same fill (the nearest, if several)
        val linked = near.filter {
            val cx = ((it[0] + it[2]) / 2).toInt().coerceIn(0, w - 1)
            val cy = ((it[1] + it[3]) / 2).toInt().coerceIn(0, h - 1)
            f[cy * w + cx]
        }.minByOrNull { hypot((it[0] + it[2]) / 2 - c1x, (it[1] + it[3]) / 2 - c1y) } ?: return null
        val c2x = (linked[0] + linked[2]) / 2
        val c2y = (linked[1] + linked[3]) / 2
        val len = hypot(c2x - c1x, c2y - c1y)
        if (len < 10f) return null
        val ux = (c2x - c1x) / len
        val uy = (c2y - c1y) / len

        // F boundary (the crop border is not an outline), minus the text boxes' straight edges
        val tbox = boxMask(ownText + near, w, h, 3)
        val edgeF = BooleanArray(n) { i ->
            if (!f[i]) return@BooleanArray false
            val x = i % w
            (x > 0 && !f[i - 1]) || (x < w - 1 && !f[i + 1]) || (i >= w && !f[i - w]) || (i < n - w && !f[i + w])
        }
        val notch = FloatArray(4)
        for ((k, sgn) in floatArrayOf(1f, -1f).withIndex()) {
            var best = Float.MAX_VALUE
            for (i in 0 until n) {
                if (!edgeF[i] || tbox[i]) continue
                val dx = i % w - c1x
                val dy = i / w - c1y
                val tt = dx * ux + dy * uy
                val ss = -dx * uy + dy * ux
                if (tt <= 0.15f * len || tt >= 0.85f * len || ss * sgn <= 2f) continue
                if (abs(ss) < best) {
                    best = abs(ss)
                    notch[2 * k] = (i % w).toFloat()
                    notch[2 * k + 1] = (i / w).toFloat()
                }
            }
            if (best == Float.MAX_VALUE) return null
        }
        val ax = notch[0]
        val ay = notch[1]
        val bx = notch[2]
        val by = notch[3]
        val ab = hypot(bx - ax, by - ay)
        if (ab < 6f) return null
        // a real neck is a pinch: much narrower than our balloon across the same direction
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (!m[i]) continue
            val p = ((i % w) * (bx - ax) + (i / w) * (by - ay)) / ab
            lo = min(lo, p)
            hi = max(hi, p)
        }
        if (ab > 0.8f * (hi - lo)) return null
        if (!inkNear(ink, w, h, ay.toInt() * w + ax.toInt(), 4) || !inkNear(ink, w, h, by.toInt() * w + bx.toInt(), 4)) {
            return null
        }
        // the mask must actually be cut through the shared fill near the neck (light edge, light beyond)...
        val mEdge = edgeOf(m, w, h)
        val beyond = dilate4(BooleanArray(n) { !m[it] && luma[it] > fill - 40f }, w, h, 2)
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
        var cutN = 0
        for (i in 0 until n) {
            if (mEdge[i] && luma[i] > fill - 40f && beyond[i] && hypot(i % w - mx, i / w - my) <= 0.75f * ab) cutN++
        }
        if (cutN < 0.25f * ab) return null
        // ...and close to the chord: the neck rebuilds a cut, it does not grow the balloon into the art
        val toM = distTo(m, w, h)
        val chordD = FloatArray(CHORD_SAMPLES) { k ->
            val t = k.toFloat() / (CHORD_SAMPLES - 1)
            val x = (ax + (bx - ax) * t).toInt().coerceIn(0, w - 1)
            val y = (ay + (by - ay) * t).toInt().coerceIn(0, h - 1)
            toM[y * w + x]
        }
        chordD.sort()
        if (chordD[CHORD_SAMPLES / 2] > 0.22f * ab) return null

        val cnx = -(by - ay) / ab
        val cny = (bx - ax) / ab
        val sText = if ((c1x - ax) * cnx + (c1y - ay) * cny < 0f) -1f else 1f
        val side = FloatArray(n)
        val along = FloatArray(n)
        val ours = BooleanArray(n)
        val neckband = BooleanArray(n)
        for (i in 0 until n) {
            val dx = i % w - ax
            val dy = i / w - ay
            side[i] = dx * cnx + dy * cny
            along[i] = (dx * (bx - ax) + dy * (by - ay)) / ab
            ours[i] = side[i] * sText >= 0f
            neckband[i] = along[i] >= -2f && along[i] <= ab + 2f && abs(side[i]) <= 0.5f * ab
        }
        // our balloon = (mask or the shared fill near the neck, plus our own text) on our side of the chord
        var base = fillHoles(BooleanArray(n) { (m[it] || (f[it] && (neckband[it] || own[it]))) && ours[it] }, w, h)
        val c1 = c1y.toInt().coerceIn(0, h - 1) * w + c1x.toInt().coerceIn(0, w - 1)
        if (base[c1]) base = componentAt(base, w, h, c1)

        // contour tangents at the notches: F boundary on our side, away from the chord
        val keepE = BooleanArray(n) { edgeF[it] && ours[it] && abs(side[it]) > 2f }
        val reach = max(6f, 0.3f * ab)
        val ta = tangent(keepE, w, h, ax, ay, bx, by, reach) ?: return null
        val tb = tangent(keepE, w, h, bx, by, ax, ay, reach) ?: return null
        val k = TANGENT_SCALE * ab
        val curve = hermite(ax, ay, bx, by, ta[0] * k, ta[1] * k, -tb[0] * k, -tb[1] * k, CURVE_POINTS)
        var meanSide = 0f
        for (j in 0 until CURVE_POINTS) meanSide += ((curve[2 * j] - ax) * cnx + (curve[2 * j + 1] - ay) * cny) * sText
        if (meanSide > 0f) return null // would bulge into our own balloon
        val poly = fillPolygon(
            curve.copyOf(2 * CURVE_POINTS + 2).also {
                it[2 * CURVE_POINTS] = ax
                it[2 * CURVE_POINTS + 1] = ay
            },
            w,
            h,
        )
        val bulge = BooleanArray(n) { poly[it] && along[it] >= 0f && along[it] <= ab } // no overshoot past the notches
        var shape = fillHoles(BooleanArray(n) { base[it] || bulge[it] }, w, h)

        // outline: grow over the ink stroke along the new edge only; past A/B the outline is the neighbour's
        val nearBright = distTo(BooleanArray(n) { !shape[it] && luma[it] > fill - 40f }, w, h)
        val grow = BooleanArray(n) { ink[it] && nearBright[it] <= 6f && ours[it] && along[it] > 0f && along[it] < ab }
        repeat(6) {
            val ring = dilate4(shape, w, h, 1)
            val prev = shape
            shape = BooleanArray(n) { prev[it] || (ring[it] && grow[it]) }
        }
        shape = fillHoles(shape, w, h)
        // only the neck changes: elsewhere the mask's (ink-snapped) edge is kept as is
        val rDisc = reach + 4f
        val local = dilate4(BooleanArray(n) { bulge[it] || (neckband[it] && ours[it]) }, w, h, 6).also { l ->
            for (i in 0 until n) {
                if (hypot(i % w - ax, i / w - ay) <= rDisc || hypot(i % w - bx, i / w - by) <= rDisc) l[i] = true
            }
        }
        shape = largestBlob(fillHoles(BooleanArray(n) { if (local[it]) shape[it] else m[it] }, w, h), w, h)
        if (losesText(own, shape)) return null

        // outline look: thickness = ink band area / edge length; colour = the dark core of the stroke
        val edgeBand = dilate4(mEdge, w, h, 6)
        val band = BooleanArray(n) { ink[it] && m[it] && edgeBand[it] }
        val bandN = band.count { it }
        val t = (bandN.toFloat() / max(mEdge.count { it }, 1)).coerceIn(1f, max(1f, 0.03f * min(w, h))).coerceAtLeast(1.5f)
        val ti = ceil(t).toInt()
        // spikes at the notches (bits of the neighbour's outline) are opened away
        val rr = max(ti + 1, (OPEN_FRAC * ab).roundToInt()).toFloat()
        val inside = shape.copyOf()
        val eroded = BooleanArray(n).also { e -> distTo(BooleanArray(n) { !inside[it] }, w, h, borderIsTarget = true).forEachIndexed { i, d -> e[i] = d > rr } }
        val toEroded = distTo(eroded, w, h)
        shape = largestBlob(BooleanArray(n) { if (local[it]) toEroded[it] <= rr else shape[it] }, w, h)
        if (losesText(own, shape)) return null
        // where the curve rejoins the mask's edge, the V between the two outlines leaves a dent: close it
        val notches = BooleanArray(n) { hypot(it % w - ax, it / w - ay) <= reach || hypot(it % w - bx, it / w - by) <= reach }
        val rc = max(rr, (NOTCH_CLOSE_FRAC * ab).roundToInt().toFloat())
        // on a grid padded with empty space, so the crop border does not count as balloon
        val p = rc.toInt() + 1
        val pw = w + 2 * p
        val pn = pw * (h + 2 * p)
        val padded = BooleanArray(pn).also { s -> for (i in 0 until n) s[(i / w + p) * pw + i % w + p] = shape[i] }
        val toShape = distTo(padded, pw, h + 2 * p)
        val toOutside = distTo(BooleanArray(pn) { toShape[it] > rc }, pw, h + 2 * p)
        shape = largestBlob(BooleanArray(n) { if (notches[it]) toOutside[(it / w + p) * pw + it % w + p] > rc else shape[it] }, w, h)

        val inkRgb = if (bandN > 0) {
            val cutoff = percentile(luma, band, 0.3f)
            medianRgb(argb, BooleanArray(n) { band[it] && luma[it] <= cutoff })
        } else {
            0x141414
        }
        val fillRgb = medianRgb(argb, BooleanArray(n) { inner[it] && luma[it] > fill - 20f })
        val out = argb.copyOf()
        // the bulge beyond the chord is repainted as clean fill: it hides the neighbour's letters and outline
        for (i in 0 until n) {
            if (bulge[i] && shape[i] && !ours[i] && !own[i]) out[i] = (out[i] and 0xFF000000.toInt()) or fillRgb
        }
        // outline along the rebuilt boundary, coverage from the distance to it so it joins the original stroke
        val final = shape
        val dist = distTo(BooleanArray(n) { !final[it] }, w, h, borderIsTarget = true)
        // the corner where the two outlines met stays inside as dark blobs hanging off the outline: repaint
        // them (with their antialiasing). Letters don't touch the outline, so they're left alone.
        val dark = BooleanArray(n) { notches[it] && shape[it] && luma[it] < fill - 40f }
        val junction = BooleanArray(n)
        for (comp in components(dark, w, h, eight = true)) {
            if (comp.any { dist[it] <= t + 0.5f }) for (i in comp) junction[i] = true
        }
        for (i in 0 until n) {
            if (!shape[i] || dist[i] <= t + 0.5f || luma[i] >= fill - 10f) continue
            val x = i % w
            val y = i / w
            var hit = false
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && junction[yy * w + xx]) hit = true
                }
            }
            if (hit) out[i] = (out[i] and 0xFF000000.toInt()) or fillRgb
        }
        val zone = dilate4(bulge, w, h, ti + 2)
        for (i in 0 until n) {
            if (!shape[i]) continue
            if (!zone[i] && hypot(i % w - ax, i / w - ay) > reach && hypot(i % w - bx, i / w - by) > reach) continue
            val cov = (t + 0.5f - dist[i]).coerceIn(0f, 1f)
            if (cov > 0f) out[i] = (out[i] and 0xFF000000.toInt()) or blend(out[i], inkRgb, cov)
        }
        return Result(shape, out, linked)
    }

    private const val MIN_FILL = 150f
    private const val CHORD_SAMPLES = 50

    /** Hermite tangent length as a fraction of the chord: longer overshoots past the notches. */
    private const val TANGENT_SCALE = 0.7f

    /** Opening radius at the neck as a fraction of the chord (removes stubs of the neighbour's outline). */
    private const val OPEN_FRAC = 0.08f

    /** Closing radius at the two notches as a fraction of the chord (fills the dent where the outlines met). */
    private const val NOTCH_CLOSE_FRAC = 0.25f

    /** More than 5 % of our text box outside [shape]. */
    private fun losesText(own: BooleanArray, shape: BooleanArray): Boolean {
        var total = 0
        var lost = 0
        for (i in own.indices) {
            if (!own[i]) continue
            total++
            if (!shape[i]) lost++
        }
        return total > 0 && lost > 0.05f * total
    }

    /** Union of [boxes] `[l,t,r,b]` grown by [pad] px, clipped to the grid. */
    private fun boxMask(boxes: List<FloatArray>, w: Int, h: Int, pad: Int): BooleanArray {
        val z = BooleanArray(w * h)
        for (b in boxes) {
            val x0 = max(0, b[0].toInt() - pad)
            val x1 = min(w - 1, b[2].toInt() + pad)
            val y0 = max(0, b[1].toInt() - pad)
            val y1 = min(h - 1, b[3].toInt() + pad)
            for (y in y0..y1) for (x in x0..x1) z[y * w + x] = true
        }
        return z
    }

    /** The 4-connected component of [m] holding pixel [seed]. */
    private fun componentAt(m: BooleanArray, w: Int, h: Int, seed: Int): BooleanArray {
        val lab = labels(m, w, h)
        val l = lab[seed]
        return BooleanArray(w * h) { lab[it] == l }
    }

    /**
     * Exact Euclidean distance in px from every pixel to the nearest [target] pixel (0 on it), by two
     * passes of the 1-D lower envelope of parabolas (Felzenszwalb-Huttenlocher). With [borderIsTarget]
     * the cells just outside the grid count as target too.
     */
    internal fun distTo(target: BooleanArray, w: Int, h: Int, borderIsTarget: Boolean = false): FloatArray {
        val inf = 1e12f
        val pad = if (borderIsTarget) 1 else 0
        val pw = w + 2 * pad
        val ph = h + 2 * pad
        val g = FloatArray(pw * ph) { i ->
            val x = i % pw - pad
            val y = i / pw - pad
            if (x < 0 || y < 0 || x >= w || y >= h || target[y * w + x]) 0f else inf
        }
        val len = max(pw, ph)
        val f = FloatArray(len)
        val d = FloatArray(len)
        val v = IntArray(len)
        val z = FloatArray(len + 1)
        fun pass(n: Int) {
            fun meet(q: Int, p: Int) = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
            var k = 0
            v[0] = 0
            z[0] = -inf
            z[1] = inf
            for (q in 1 until n) {
                var s = meet(q, v[k])
                while (s <= z[k]) {
                    k--
                    s = meet(q, v[k])
                }
                k++
                v[k] = q
                z[k] = s
                z[k + 1] = inf
            }
            k = 0
            for (q in 0 until n) {
                while (z[k + 1] < q) k++
                val dq = (q - v[k]).toFloat()
                d[q] = dq * dq + f[v[k]]
            }
        }
        for (x in 0 until pw) {
            for (y in 0 until ph) f[y] = g[y * pw + x]
            pass(ph)
            for (y in 0 until ph) g[y * pw + x] = d[y]
        }
        for (y in 0 until ph) {
            for (x in 0 until pw) f[x] = g[y * pw + x]
            pass(pw)
            for (x in 0 until pw) g[y * pw + x] = d[x]
        }
        return FloatArray(w * h) { i -> sqrt(g[(i / w + pad) * pw + i % w + pad]) }
    }
    private const val CURVE_POINTS = 64

    private class Cut(val run: IntArray, val ax: Float, val ay: Float, val bx: Float, val by: Float)

    /** The longest cut-through-fill run whose ends meet ink and whose far side is another balloon. */
    private fun findCut(
        cutPx: BooleanArray,
        ink: BooleanArray,
        brightOut: BooleanArray,
        w: Int,
        h: Int,
        others: List<FloatArray>,
    ): Cut? {
        val runs = components(cutPx, w, h, eight = true)
        val beyond = labels(brightOut, w, h)
        var best: Cut? = null
        for (run in runs) {
            if (run.size < 0.06f * min(w, h)) continue
            // ends = extremes along the run's principal axis
            var mx = 0f
            var my = 0f
            for (p in run) {
                mx += p % w
                my += p / w
            }
            mx /= run.size
            my /= run.size
            var sxx = 0f
            var sxy = 0f
            var syy = 0f
            for (p in run) {
                val dx = p % w - mx
                val dy = p / w - my
                sxx += dx * dx
                sxy += dx * dy
                syy += dy * dy
            }
            val (ux, uy) = principal(sxx, sxy, syy)
            var lo = run[0]
            var hi = run[0]
            var tlo = Float.MAX_VALUE
            var thi = -Float.MAX_VALUE
            for (p in run) {
                val t = (p % w - mx) * ux + (p / w - my) * uy
                if (t < tlo) {
                    tlo = t
                    lo = p
                }
                if (t > thi) {
                    thi = t
                    hi = p
                }
            }
            if (!inkNear(ink, w, h, lo, 4) || !inkNear(ink, w, h, hi, 4)) continue
            // light area just beyond the cut must belong (>= 30 %) to another detected balloon
            val runMask = BooleanArray(w * h).also { r -> run.forEach { r[it] = true } }
            val ring = dilate4(runMask, w, h, 2)
            val touched = HashSet<Int>()
            for (i in ring.indices) if (ring[i] && brightOut[i] && beyond[i] > 0) touched += beyond[i]
            if (touched.isEmpty()) continue
            var total = 0
            val inside = IntArray(others.size)
            for (i in beyond.indices) {
                if (beyond[i] == 0 || beyond[i] !in touched) continue
                total++
                val x = i % w
                val y = i / w
                for ((k, o) in others.withIndex()) if (x >= o[0] && x <= o[2] && y >= o[1] && y <= o[3]) inside[k]++
            }
            if (total == 0 || inside.none { it >= 0.3f * total }) continue
            if (best == null || run.size > best.run.size) {
                best = Cut(run, (lo % w).toFloat(), (lo / w).toFloat(), (hi % w).toFloat(), (hi / w).toFloat())
            }
        }
        return best
    }

    /** Principal axis of a 2x2 covariance. */
    private fun principal(sxx: Float, sxy: Float, syy: Float): Pair<Float, Float> {
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val l = tr / 2 + sqrt(max(0f, tr * tr / 4 - det))
        var vx = sxy
        var vy = l - sxx
        if (abs(vx) + abs(vy) < 1e-6f) {
            vx = if (sxx >= syy) 1f else 0f
            vy = if (sxx >= syy) 0f else 1f
        }
        val n = sqrt(vx * vx + vy * vy)
        return vx / n to vy / n
    }

    /** Unit tangent of [outline] near (px,py), pointing out of the outline into the gap toward the other end. */
    private fun tangent(outline: BooleanArray, w: Int, h: Int, px: Float, py: Float, qx: Float, qy: Float, reach: Float): FloatArray? {
        val pq = sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
        var n = 0
        var mx = 0f
        var my = 0f
        val pts = ArrayList<Int>()
        val r = ceil(reach).toInt()
        for (y in max(0, (py - r).toInt())..min(h - 1, (py + r).toInt())) {
            for (x in max(0, (px - r).toInt())..min(w - 1, (px + r).toInt())) {
                if (!outline[y * w + x]) continue
                val d = sqrt((x - px) * (x - px) + (y - py) * (y - py))
                val dq = sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy))
                if (d > reach || dq <= 0.5f * pq) continue
                pts += y * w + x
                mx += x
                my += y
                n++
            }
        }
        if (n < 3) return null
        mx /= n
        my /= n
        var sxx = 0f
        var sxy = 0f
        var syy = 0f
        for (p in pts) {
            val dx = p % w - mx
            val dy = p / w - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        var (vx, vy) = principal(sxx, sxy, syy)
        if (vx * (px - mx) + vy * (py - my) < 0f) {
            vx = -vx
            vy = -vy
        }
        return floatArrayOf(vx, vy)
    }

    /** [n] points (x,y interleaved) of the cubic Hermite curve from P0 to P1 with tangents T0, T1. */
    internal fun hermite(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        t0x: Float,
        t0y: Float,
        t1x: Float,
        t1y: Float,
        n: Int,
    ): FloatArray {
        val out = FloatArray(2 * n)
        for (k in 0 until n) {
            val t = k.toFloat() / (n - 1)
            val h00 = 2 * t * t * t - 3 * t * t + 1
            val h10 = t * t * t - 2 * t * t + t
            val h01 = -2 * t * t * t + 3 * t * t
            val h11 = t * t * t - t * t
            out[2 * k] = h00 * x0 + h10 * t0x + h01 * x1 + h11 * t1x
            out[2 * k + 1] = h00 * y0 + h10 * t0y + h01 * y1 + h11 * t1y
        }
        return out
    }

    /** Even-odd scanline fill of the closed polygon [poly] (x,y interleaved, pixel (x,y) centred on the integer point). */
    internal fun fillPolygon(poly: FloatArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        val n = poly.size / 2
        val xs = FloatArray(n)
        for (y in 0 until h) {
            val cy = y.toFloat()
            var k = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val yi = poly[2 * i + 1]
                val yj = poly[2 * j + 1]
                if ((yi <= cy && yj > cy) || (yj <= cy && yi > cy)) {
                    xs[k++] = poly[2 * i] + (cy - yi) / (yj - yi) * (poly[2 * j] - poly[2 * i])
                }
            }
            java.util.Arrays.sort(xs, 0, k)
            var p = 0
            while (p + 1 < k) {
                val from = ceil(xs[p]).toInt().coerceAtLeast(0)
                val to = floor(xs[p + 1]).toInt().coerceAtMost(w - 1)
                for (x in from..to) out[y * w + x] = true
                p += 2
            }
        }
        return out
    }

    /** Chamfer (3-4) distance of every inside pixel to the nearest outside one (the grid border counts as outside). */
    private fun chamferInside(m: BooleanArray, w: Int, h: Int): FloatArray {
        val big = 1e6f
        val d = FloatArray(w * h) { if (m[it]) big else 0f }
        fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) 0f else d[y * w + x]
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (d[i] == 0f) continue
                d[i] = minOf(d[i], at(x - 1, y) + 3, at(x, y - 1) + 3, at(x - 1, y - 1) + 4, at(x + 1, y - 1) + 4)
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (d[i] == 0f) continue
                d[i] = minOf(d[i], at(x + 1, y) + 3, at(x, y + 1) + 3, at(x + 1, y + 1) + 4, at(x - 1, y + 1) + 4)
            }
        }
        // chamfer units -> px, measured to the pixel centre of the boundary like an EDT
        for (i in d.indices) if (d[i] > 0f) d[i] = d[i] / 3f
        return d
    }

    private fun inkNear(ink: BooleanArray, w: Int, h: Int, p: Int, r: Int): Boolean {
        val x0 = p % w
        val y0 = p / w
        for (y in max(0, y0 - r)..min(h - 1, y0 + r)) for (x in max(0, x0 - r)..min(w - 1, x0 + r)) if (ink[y * w + x]) return true
        return false
    }

    private fun edgeOf(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val e = erode4(m, w, h, 1)
        return BooleanArray(w * h) { m[it] && !e[it] }
    }

    /** Connected components of [m] as index arrays (4- or 8-connectivity). */
    private fun components(m: BooleanArray, w: Int, h: Int, eight: Boolean): List<IntArray> {
        val lab = IntArray(w * h)
        val out = ArrayList<IntArray>()
        val queue = IntArray(w * h)
        var next = 0
        for (s in m.indices) {
            if (!m[s] || lab[s] != 0) continue
            next++
            var head = 0
            var tail = 0
            lab[s] = next
            queue[tail++] = s
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!eight && dx != 0 && dy != 0) continue
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        val j = yy * w + xx
                        if (m[j] && lab[j] == 0) {
                            lab[j] = next
                            queue[tail++] = j
                        }
                    }
                }
            }
            out += queue.copyOfRange(0, tail)
        }
        return out
    }

    /** 4-connected component label per pixel (0 = background). */
    private fun labels(m: BooleanArray, w: Int, h: Int): IntArray {
        val lab = IntArray(w * h)
        for ((k, comp) in components(m, w, h, eight = false).withIndex()) for (i in comp) lab[i] = k + 1
        return lab
    }

    private fun medianOf(v: FloatArray, where: BooleanArray): Float? {
        val hist = IntArray(256)
        var n = 0
        for (i in v.indices) {
            if (!where[i]) continue
            hist[v[i].toInt().coerceIn(0, 255)]++
            n++
        }
        if (n < 50) return null
        var acc = 0
        for (b in 0 until 256) {
            acc += hist[b]
            if (acc * 2 >= n) return b.toFloat()
        }
        return 255f
    }

    private fun percentile(v: FloatArray, where: BooleanArray, q: Float): Float {
        val hist = IntArray(256)
        var n = 0
        for (i in v.indices) {
            if (!where[i]) continue
            hist[v[i].toInt().coerceIn(0, 255)]++
            n++
        }
        var acc = 0
        for (b in 0 until 256) {
            acc += hist[b]
            if (acc >= q * n) return b.toFloat()
        }
        return 255f
    }

    /** Per-channel median RGB (0xRRGGBB) of the [where] pixels. */
    private fun medianRgb(argb: IntArray, where: BooleanArray): Int {
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        var n = 0
        for (i in argb.indices) {
            if (!where[i]) continue
            hr[(argb[i] shr 16) and 0xFF]++
            hg[(argb[i] shr 8) and 0xFF]++
            hb[argb[i] and 0xFF]++
            n++
        }
        if (n == 0) return 0xFFFFFF
        fun med(hh: IntArray): Int {
            var acc = 0
            for (b in 0 until 256) {
                acc += hh[b]
                if (acc * 2 >= n) return b
            }
            return 255
        }
        return (med(hr) shl 16) or (med(hg) shl 8) or med(hb)
    }

    private fun blend(p: Int, rgb: Int, a: Float): Int {
        fun ch(s: Int) = ((p shr s) and 0xFF) * (1 - a) + ((rgb shr s) and 0xFF) * a
        return (ch(16).toInt() shl 16) or (ch(8).toInt() shl 8) or ch(0).toInt()
    }
}
