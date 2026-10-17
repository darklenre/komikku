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
