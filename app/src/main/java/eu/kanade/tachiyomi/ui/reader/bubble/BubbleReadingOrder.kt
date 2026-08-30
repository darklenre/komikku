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

    /** Bubble box reduced to what the ordering needs — centre and size. */
    internal class Box(val cx: Float, val cy: Float, val w: Float, val h: Float)

    fun sort(bubbles: List<Bubble>, direction: ReadingDirection, bandFactor: Float = 0.5f): List<Bubble> {
        if (bubbles.size <= 1) return bubbles
        val boxes = bubbles.map { Box(it.rect.centerX(), it.rect.centerY(), it.rect.width(), it.rect.height()) }
        return orderIndices(boxes, direction, bandFactor).map { bubbles[it] }
    }

    /** The pure core of [sort]: returns a permutation of `boxes.indices` in reading order. */
    internal fun orderIndices(boxes: List<Box>, direction: ReadingDirection, bandFactor: Float = 0.5f): List<Int> {
        if (boxes.size <= 1) return boxes.indices.toList()

        val medianH = boxes.map { it.h }.sorted()[boxes.size / 2].coerceAtLeast(1e-4f)
        val medianW = boxes.map { it.w }.sorted()[boxes.size / 2].coerceAtLeast(1e-4f)

        val bands = cluster(boxes.indices.toList(), medianH * bandFactor) { boxes[it].cy }
        return bands.flatMap { band ->
            val columns = cluster(band, medianW * bandFactor) { boxes[it].cx }
                .sortedBy { column -> column.sumOf { boxes[it].cx.toDouble() } / column.size }
            val ordered = if (direction == ReadingDirection.RTL) columns.asReversed() else columns
            ordered.flatMap { column -> column.sortedBy { boxes[it].cy } }
        }
    }

    /** 1-D agglomerative clustering: walk points in [key] order, start a new cluster whenever the
     *  gap to the running cluster mean exceeds [threshold]. */
    private fun <T> cluster(items: List<T>, threshold: Float, key: (T) -> Float): List<List<T>> {
        val sorted = items.sortedBy(key)
        val clusters = mutableListOf<MutableList<T>>()
        var mean = Float.NaN
        for (item in sorted) {
            val v = key(item)
            if (clusters.isEmpty() || abs(v - mean) > threshold) {
                clusters.add(mutableListOf(item))
                mean = v
            } else {
                val c = clusters.last()
                c.add(item)
                mean = c.sumOf { key(it).toDouble() }.toFloat() / c.size
            }
        }
        return clusters
    }
}
