package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.RectF

/**
 * Hit-testing for a Bubble Zoom gesture. All coordinates are normalised 0..1 of the page image,
 * matching [Bubble.rect]. The geometry is factored into pure `[l,t,r,b]`-float helpers so it can be
 * unit-tested without Android's `RectF`.
 */
object BubbleHit {

    /** Fallback synthetic-bubble size (fraction of the page) when the page has no detections to learn from. */
    private const val DEFAULT_W = 0.22f
    private const val DEFAULT_H = 0.14f

    /**
     * Index of the bubble the point ([nx], [ny]) lands in, or -1. Among the rects that contain the
     * point, a bubble whose *inscribed ellipse* also contains it wins over one where the point only
     * sits in a rect corner (usually gutter / a neighbouring panel); ties break to the smallest area
     * (the most specific bubble). Never stricter than a plain `rect.contains`.
     */
    fun hitTest(bubbles: List<Bubble>, nx: Float, ny: Float): Int =
        pickIndex(bubbles.map { floatArrayOf(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) }, nx, ny)

    /**
     * A user-placed bubble centred on ([nx], [ny]) for "tap anywhere to zoom". Its size is the median
     * of [pageBubbles] (so it matches the art), falling back to a fixed fraction of the page.
     */
    fun syntheticBubbleAt(nx: Float, ny: Float, pageBubbles: List<Bubble>): Bubble {
        val medW = median(pageBubbles.map { it.rect.right - it.rect.left }) ?: DEFAULT_W
        val medH = median(pageBubbles.map { it.rect.bottom - it.rect.top }) ?: DEFAULT_H
        val r = synthRect(nx, ny, medW, medH)
        return Bubble(RectF(r[0], r[1], r[2], r[3]), confidence = 1f)
    }

    // --- pure geometry (unit-tested) ---

    /** @param boxes each `[left, top, right, bottom]`, normalised. */
    internal fun pickIndex(boxes: List<FloatArray>, nx: Float, ny: Float): Int {
        var best = -1
        var bestScore = Float.MAX_VALUE
        boxes.forEachIndexed { i, r ->
            val l = r[0]
            val t = r[1]
            val rt = r[2]
            val b = r[3]
            if (nx < l || nx > rt || ny < t || ny > b) return@forEachIndexed
            val rx = ((rt - l) / 2f).coerceAtLeast(1e-4f)
            val ry = ((b - t) / 2f).coerceAtLeast(1e-4f)
            val ex = (nx - (l + rt) / 2f) / rx
            val ey = (ny - (t + b) / 2f) / ry
            val area = (rt - l) * (b - t)
            // Ellipse matches (score <= 1) always beat corner-only matches (score >= 1 + 10).
            val score = if (ex * ex + ey * ey <= 1f) area else area + 10f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** `[left, top, right, bottom]` of a [medW] x [medH] box centred on ([nx], [ny]), clamped to 0..1. */
    internal fun synthRect(nx: Float, ny: Float, medW: Float, medH: Float): FloatArray {
        val halfW = (medW / 2f).coerceIn(0.04f, 0.45f)
        val halfH = (medH / 2f).coerceIn(0.03f, 0.40f)
        val left = (nx - halfW).coerceIn(0f, 1f)
        val top = (ny - halfH).coerceIn(0f, 1f)
        return floatArrayOf(left, top, (nx + halfW).coerceIn(left, 1f), (ny + halfH).coerceIn(top, 1f))
    }

    internal fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
