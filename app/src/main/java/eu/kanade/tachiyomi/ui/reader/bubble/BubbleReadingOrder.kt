package eu.kanade.tachiyomi.ui.reader.bubble

import kotlin.math.abs

enum class ReadingDirection { LTR, RTL, VERTICAL }

/**
 * Orders bubbles into reading sequence. With a [PanelNode] layout, bubbles are first grouped by the
 * panel they sit in and panels are read in layout order; inside a panel (or on a page without one):
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

    fun sort(
        bubbles: List<Bubble>,
        direction: ReadingDirection,
        panels: PanelNode? = null,
        bandFactor: Float = 0.5f,
    ): List<Bubble> {
        if (bubbles.size <= 1) return bubbles
        val boxes = bubbles.map { Box(it.rect.centerX(), it.rect.centerY(), it.rect.width(), it.rect.height()) }
        return orderInPanels(boxes, direction, panels?.leaves(direction), bandFactor).map { bubbles[it] }
    }

    /** [orderIndices] per panel, panels in [leaves] order; each box goes to the nearest panel (L1 to its centre). */
    internal fun orderInPanels(
        boxes: List<Box>,
        direction: ReadingDirection,
        leaves: List<PanelNode.Leaf>?,
        bandFactor: Float = 0.5f,
    ): List<Int> {
        if (leaves.isNullOrEmpty() || leaves.size == 1) return orderIndices(boxes, direction, bandFactor)
        val groups = List(leaves.size) { mutableListOf<Int>() }
        for ((i, b) in boxes.withIndex()) {
            val best = leaves.indices.minBy { k ->
                val p = leaves[k]
                maxOf(0f, p.l - b.cx, b.cx - p.r) + maxOf(0f, p.t - b.cy, b.cy - p.b)
            }
            groups[best] += i
        }
        return groups.flatMap { g -> orderIndices(g.map { boxes[it] }, direction, bandFactor).map { g[it] } }
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
