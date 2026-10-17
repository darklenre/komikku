package eu.kanade.tachiyomi.ui.reader.bubble

/*
 * Pure mask helpers for the balloon cutout (row-major `w`x`h` grids), kept free of Android types so
 * they are unit-testable.
 */

/** [m] with every background region that does not reach the border filled in (glyphs, ink holes). */
internal fun fillHoles(m: BooleanArray, w: Int, h: Int): BooleanArray {
    val outside = BooleanArray(w * h)
    val queue = IntArray(w * h)
    var head = 0
    var tail = 0
    fun seed(i: Int) {
        if (!m[i] && !outside[i]) {
            outside[i] = true
            queue[tail++] = i
        }
    }
    for (x in 0 until w) {
        seed(x)
        seed((h - 1) * w + x)
    }
    for (y in 0 until h) {
        seed(y * w)
        seed(y * w + w - 1)
    }
    while (head < tail) {
        val i = queue[head++]
        val x = i % w
        val y = i / w
        if (x > 0) seed(i - 1)
        if (x < w - 1) seed(i + 1)
        if (y > 0) seed(i - w)
        if (y < h - 1) seed(i + w)
    }
    return BooleanArray(w * h) { m[it] || !outside[it] }
}

/** Only the largest 4-connected blob of [m] (stray specks off the balloon dropped). */
internal fun largestBlob(m: BooleanArray, w: Int, h: Int): BooleanArray {
    val label = IntArray(w * h)
    val queue = IntArray(w * h)
    var best = 0
    var bestSize = 0
    var next = 0
    for (start in m.indices) {
        if (!m[start] || label[start] != 0) continue
        next++
        var head = 0
        var tail = 0
        fun visit(j: Int) {
            if (m[j] && label[j] == 0) {
                label[j] = next
                queue[tail++] = j
            }
        }
        visit(start)
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        if (tail > bestSize) {
            bestSize = tail
            best = next
        }
    }
    return BooleanArray(w * h) { best != 0 && label[it] == best }
}

/** 3x3 box average of [m]: a ~1 px anti-aliased edge, 0..1. */
internal fun antialias(m: BooleanArray, w: Int, h: Int): FloatArray {
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    n++
                    if (m[yy * w + xx]) sum++
                }
            }
            out[y * w + x] = sum.toFloat() / n
        }
    }
    return out
}

/**
 * [m] smoothed by two passes of a (2r+1) box blur and re-thresholded at 0.5: removes the staircase
 * SAM leaves where a balloon is split from a linked neighbour inside one uniform fill, without
 * moving edges that follow real ink.
 */
internal fun smoothMask(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
    if (r <= 0) return m
    var a = FloatArray(w * h) { if (m[it]) 1f else 0f }
    repeat(2) { a = boxBlur(a, w, h, r) }
    return BooleanArray(w * h) { a[it] >= 0.5f }
}

/** Separable box blur with edge clamping. */
private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(w * h)
    val out = FloatArray(w * h)
    val n = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sum = 0f
        for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[row + x] = sum / n
            sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
        }
    }
    for (x in 0 until w) {
        var sum = 0f
        for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            out[y * w + x] = sum / n
            sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
        }
    }
    return out
}

/**
 * Grows [m] over the balloon's own ink outline, at crop resolution, so the cutout keeps the full dark
 * border instead of stopping inside or halfway through it. [luma] is 0..255 per pixel. A pixel joins
 * when it is ink (clearly darker than the balloon fill), at most [r] steps from the mask, and at most
 * r+1 steps from a light pixel outside the mask — i.e. part of a thin stroke between fill and
 * background, never a dark area such as hair or a black panel. One more ring of half-dark pixels
 * gives the stroke its soft outer edge. Where the mask edge runs through uniform fill (a cut between
 * linked balloons) it is smoothed harder. Balloons with a dark or saturated fill are returned as is.
 */
internal fun inkSnap(m: BooleanArray, luma: FloatArray, w: Int, h: Int, r: Int): BooleanArray {
    val inner = erode4(m, w, h, 3)
    val fill = median(luma, inner) ?: return m
    if (fill < SNAP_MIN_FILL) return m
    val inkMax = fill - maxOf(60f, 0.35f * fill)
    val brightOut = BooleanArray(w * h) { !m[it] && luma[it] > fill - 40f }
    val nearBright = dilate4(brightOut, w, h, r + 1)
    var cur = m
    for (step in 0 until r) {
        val ring = dilate4(cur, w, h, 1)
        val prev = cur
        cur = BooleanArray(w * h) { prev[it] || (ring[it] && luma[it] < inkMax && nearBright[it]) }
        if (cur.contentEquals(prev)) break
    }
    val ring = dilate4(cur, w, h, 1)
    cur = BooleanArray(w * h) { cur[it] || (ring[it] && luma[it] < fill - 25f && nearBright[it]) }
    cur = fillHoles(cur, w, h)

    // Cut through uniform fill (linked balloon): edge pixels that are light with light pixels outside.
    val eroded = erode4(cur, w, h, 1)
    val brightNear = dilate4(brightOut, w, h, 1)
    var edges = 0
    var cuts = 0
    val cut = BooleanArray(w * h)
    for (i in cur.indices) {
        if (!cur[i] || eroded[i]) continue
        edges++
        if (luma[i] > fill - 40f && brightNear[i]) {
            cut[i] = true
            cuts++
        }
    }
    if (cuts > 0.05f * edges) {
        var a = FloatArray(w * h) { if (cur[it]) 1f else 0f }
        repeat(3) { a = boxBlur(a, w, h, 3) }
        val zone = dilate4(cut, w, h, 6)
        cur = BooleanArray(w * h) { if (zone[it]) a[it] >= 0.5f else cur[it] }
    }
    return cur
}

/** Below this fill luma the balloon isn't light enough for the ink outline to stand out. */
private const val SNAP_MIN_FILL = 150f

/** Median of [v] over the [where] pixels (0..255 histogram), or null if none. */
private fun median(v: FloatArray, where: BooleanArray): Float? {
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

/** [it] rounds of 4-neighbour dilation. */
internal fun dilate4(m: BooleanArray, w: Int, h: Int, it: Int): BooleanArray {
    var cur = m
    repeat(it) {
        val src = cur
        cur = BooleanArray(w * h) { i ->
            val x = i % w
            src[i] || (x > 0 && src[i - 1]) || (x < w - 1 && src[i + 1]) ||
                (i >= w && src[i - w]) || (i < w * (h - 1) && src[i + w])
        }
    }
    return cur
}

/** [it] rounds of 4-neighbour erosion (outside the grid counts as unset). */
internal fun erode4(m: BooleanArray, w: Int, h: Int, it: Int): BooleanArray {
    var cur = m
    repeat(it) {
        val src = cur
        cur = BooleanArray(w * h) { i ->
            val x = i % w
            src[i] && x > 0 && src[i - 1] && x < w - 1 && src[i + 1] &&
                i >= w && src[i - w] && i < w * (h - 1) && src[i + w]
        }
    }
    return cur
}
