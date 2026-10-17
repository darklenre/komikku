package eu.kanade.tachiyomi.ui.reader.bubble

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Panel layout of a page as a recursive XY-cut tree: a node is either a leaf panel or a split of its
 * area along straight gutters into ordered children. Coordinates are normalised 0..1 of the page.
 * Direction-independent; [BubbleReadingOrder] walks it left-to-right or right-to-left.
 */
sealed class PanelNode {
    class Leaf(val l: Float, val t: Float, val r: Float, val b: Float) : PanelNode()

    /** [horizontal] = split by horizontal gutters (children top to bottom), else by vertical ones (left to right). */
    class Split(val horizontal: Boolean, val children: List<PanelNode>) : PanelNode()

    /** Leaves in reading order for [direction] (RTL reverses side-by-side panels). */
    fun leaves(direction: ReadingDirection): List<Leaf> = when (this) {
        is Leaf -> listOf(this)
        is Split -> (if (!horizontal && direction == ReadingDirection.RTL) children.asReversed() else children)
            .flatMap { it.leaves(direction) }
    }
}

/**
 * Finds panels by recursive XY-cut on gutters: straight rows (or columns) made of the gutter colour
 * across the whole current region. Balloon boxes are ignored by that test, since balloons often
 * straddle a gutter. The gutter colour is unknown (white, black, the page margin's tint), so each
 * candidate is tried and the one that yields the most panels wins.
 *
 * @param luma page luminance 0..255, row-major, [w]x[h] (any resolution; ~1000 px long side is plenty)
 * @param balloons balloon boxes `[l, t, r, b]` normalised 0..1
 */
object BubblePanels {

    /** Max |luma - gutter colour| for a pixel to count as gutter. */
    private const val TOL = 40f

    /** Share of a row's non-balloon pixels that must be gutter for the row to be a gutter line. */
    private const val LINE_FRAC = 0.985f

    /** A row also needs at least this share of non-balloon pixels to be judged at all. */
    private const val MIN_VALID = 0.3f

    /** Min gutter width (fraction of the page's long side) and min panel extent (fraction of the page side). */
    private const val MIN_GAP = 0.002f
    private const val MIN_PANEL = 0.06f

    fun detect(luma: FloatArray, w: Int, h: Int, balloons: List<FloatArray>): PanelNode {
        val wild = BooleanArray(w * h)
        for (r in balloons) {
            val x0 = (r[0] * w).toInt().coerceIn(0, w)
            val x1 = ceil(r[2] * w).toInt().coerceIn(0, w)
            val y0 = (r[1] * h).toInt().coerceIn(0, h)
            val y1 = ceil(r[3] * h).toInt().coerceIn(0, h)
            for (y in y0 until y1) for (x in x0 until x1) wild[y * w + x] = true
        }
        val ctx = Ctx(w, h, wild, max(2, (MIN_GAP * max(w, h)).toInt()))
        return gutterCandidates(luma, w, h)
            .map { bg -> cut(BooleanArray(w * h) { abs(luma[it] - bg) <= TOL }, ctx, 0, 0, w, h) }
            .maxBy { countLeaves(it) }
    }

    private class Ctx(val w: Int, val h: Int, val wild: BooleanArray, val minGap: Int)

    /** Mode of a 2 % border ring (page margin colour), plus white and black when they differ from it. */
    internal fun gutterCandidates(luma: FloatArray, w: Int, h: Int): List<Float> {
        val b = max(2, (0.02f * minOf(w, h)).toInt())
        val hist = IntArray(256)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y < b || y >= h - b || x < b || x >= w - b) hist[luma[y * w + x].toInt().coerceIn(0, 255)]++
            }
        }
        var bg = 0
        var best = -1
        for (v in 0 until 256) {
            var s = 0
            for (k in -4..4) s += hist.getOrElse(v + k) { 0 }
            if (s > best) {
                best = s
                bg = v
            }
        }
        return buildList {
            add(bg.toFloat())
            for (v in floatArrayOf(245f, 10f)) if (abs(v - bg) > TOL) add(v)
        }
    }

    private fun cut(gut: BooleanArray, c: Ctx, x0: Int, y0: Int, x1: Int, y1: Int): PanelNode {
        for (horizontal in booleanArrayOf(true, false)) {
            val n = if (horizontal) y1 - y0 else x1 - x0
            val across = if (horizontal) x1 - x0 else y1 - y0
            val flags = BooleanArray(n) { i ->
                var valid = 0
                var good = 0
                for (j in 0 until across) {
                    val idx = if (horizontal) (y0 + i) * c.w + x0 + j else (y0 + j) * c.w + x0 + i
                    if (c.wild[idx]) continue
                    valid++
                    if (gut[idx]) good++
                }
                valid > MIN_VALID * across && good >= LINE_FRAC * max(valid, 1)
            }
            val cuts = mutableListOf(0)
            var start = -1
            for (i in 0..n) {
                val f = i < n && flags[i]
                if (f && start < 0) start = i
                if (!f && start >= 0) {
                    // gutter runs touching the region edge are margins, not separators
                    if (i - start >= c.minGap && start > 0 && i < n) cuts += (start + i) / 2
                    start = -1
                }
            }
            cuts += n
            val minPanel = MIN_PANEL * (if (horizontal) c.h else c.w)
            val segs = cuts.zipWithNext().filter { (a, b) -> b - a >= minPanel }
            if (segs.size < 2) continue
            return PanelNode.Split(
                horizontal,
                segs.map { (a, b) ->
                    if (horizontal) cut(gut, c, x0, y0 + a, x1, y0 + b) else cut(gut, c, x0 + a, y0, x0 + b, y1)
                },
            )
        }
        return PanelNode.Leaf(x0.toFloat() / c.w, y0.toFloat() / c.h, x1.toFloat() / c.w, y1.toFloat() / c.h)
    }

    private fun countLeaves(n: PanelNode): Int = when (n) {
        is PanelNode.Leaf -> 1
        is PanelNode.Split -> n.children.sumOf { countLeaves(it) }
    }
}
