package eu.kanade.tachiyomi.ui.reader.bubble

import kotlin.math.abs

enum class ReadingDirection { LTR, RTL, VERTICAL }

/**
 * Orders bubbles into reading sequence:
 *  1. cluster into horizontal bands (rows) using a threshold proportional to the median bubble
 *     height, bands top-to-bottom;
 *  2. inside a band, cluster into columns by X (threshold proportional to the median bubble
 *     width), columns left-to-right (LTR/VERTICAL) or right-to-left (RTL);
 *  3. inside a column, top-to-bottom.
 *
 * The column step is what keeps a pair of bubbles stacked on the same side of the page in reading
 * order instead of flipping them on a near-tied X. Works on normalised rects; scale-invariant.
 */
object BubbleReadingOrder {

    fun sort(bubbles: List<Bubble>, direction: ReadingDirection, bandFactor: Float = 0.5f): List<Bubble> {
        if (bubbles.size <= 1) return bubbles

        val heights = bubbles.map { it.rect.height() }.sorted()
        val medianH = heights[heights.size / 2].coerceAtLeast(1e-4f)
        val widths = bubbles.map { it.rect.width() }.sorted()
        val medianW = widths[widths.size / 2].coerceAtLeast(1e-4f)

        val bands = cluster(bubbles, medianH * bandFactor) { it.rect.centerY() }
        return bands.flatMap { band ->
            val columns = cluster(band, medianW * bandFactor) { it.rect.centerX() }
                .sortedBy { column -> column.sumOf { it.rect.centerX().toDouble() } / column.size }
            val ordered = if (direction == ReadingDirection.RTL) columns.asReversed() else columns
            ordered.flatMap { column -> column.sortedBy { it.rect.centerY() } }
        }
    }

    /** 1-D agglomerative clustering: walk points in [key] order, start a new cluster whenever the
     *  gap to the running cluster mean exceeds [threshold]. */
    private fun cluster(bubbles: List<Bubble>, threshold: Float, key: (Bubble) -> Float): List<List<Bubble>> {
        val sorted = bubbles.sortedBy(key)
        val clusters = mutableListOf<MutableList<Bubble>>()
        var mean = Float.NaN
        for (b in sorted) {
            val v = key(b)
            if (clusters.isEmpty() || abs(v - mean) > threshold) {
                clusters.add(mutableListOf(b))
                mean = v
            } else {
                val c = clusters.last()
                c.add(b)
                mean = c.sumOf { key(it).toDouble() }.toFloat() / c.size
            }
        }
        return clusters
    }
}
