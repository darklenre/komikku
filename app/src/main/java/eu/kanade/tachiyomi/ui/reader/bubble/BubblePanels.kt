package eu.kanade.tachiyomi.ui.reader.bubble

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Panel layout of a page as a tree: a node is a leaf panel, a split of its area into ordered rows or
 * columns, or a loose group with no clean cut. Coordinates are normalised 0..1 of the page (panels may
 * overlap). Direction-independent; [BubbleReadingOrder] walks it left-to-right or right-to-left.
 */
sealed class PanelNode {
    class Leaf(val l: Float, val t: Float, val r: Float, val b: Float) : PanelNode()

    /** [horizontal] = split by horizontal gutters (children top to bottom), else by vertical ones (left to right). */
    class Split(val horizontal: Boolean, val children: List<PanelNode>) : PanelNode()

    /** Panels that no row or column cut separates: read top first, then across in the reading direction. */
    class Loose(val panels: List<Leaf>) : PanelNode()

    /** Leaves in reading order for [direction] (RTL reverses side-by-side panels). */
    fun leaves(direction: ReadingDirection): List<Leaf> = when (this) {
        is Leaf -> listOf(this)
        is Split -> (if (!horizontal && direction == ReadingDirection.RTL) children.asReversed() else children)
            .flatMap { it.leaves(direction) }
        is Loose -> panels.sortedWith(
            compareBy<Leaf>({ (it.t * 10).roundToInt() }, { if (direction == ReadingDirection.RTL) -it.r else it.l }),
        )
    }
}

/**
 * Finds panels as the connected regions the gutters separate ([regions]): this copes with slanted,
 * irregular and overlapping panels. Balloon boxes count as gutter there, since balloons often straddle
 * one. When no gutter colour gives regions covering enough of the page, falls back to a recursive XY-cut
 * on gutters: straight rows (or columns) made of the gutter colour across the whole current region,
 * balloon boxes ignored. The gutter colour is unknown (white, black, the page margin's tint, a grey
 * gradient), so each candidate is tried and the one that yields the most panels wins.
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
        regions(luma, w, h, wild)?.let { return it }
        val ctx = Ctx(w, h, wild, max(2, (MIN_GAP * max(w, h)).toInt()))
        return gutterCandidates(luma, w, h)
            .map { bg -> cut(BooleanArray(w * h) { abs(luma[it] - bg) <= TOL }, ctx, 0, 0, w, h) }
            .maxBy { countLeaves(it) }
    }

    /**
     * Layout from panel boxes `[l, t, r, b]` (0..1) found by a model ([TflitePanelDetector]), ordered like
     * the gutter regions. No boxes (a splash page, a page without panels) → the whole page as one panel.
     */
    fun fromBoxes(boxes: List<FloatArray>): PanelNode {
        if (boxes.isEmpty()) return PanelNode.Leaf(0f, 0f, 1f, 1f)
        return orderRegions(boxes.map { PanelNode.Leaf(it[0], it[1], it[2], it[3]) })
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
        is PanelNode.Loose -> n.panels.size
    }

    // ---- panels as regions between gutters ----

    /** A region must cover this share of the page in total; each one this share, and this extent per side. */
    private const val MIN_COVER = 0.4f
    private const val MIN_REGION_AREA = 0.015f

    /** A region box mostly (this share) inside a larger one belongs to it. */
    private const val CONTAINED = 0.7f

    /** Panels whose extents overlap by up to this share of the smaller one still fall in separate rows/columns. */
    private const val ROW_OVERLAP = 0.15f

    private class Region(val l: Float, val t: Float, val r: Float, val b: Float, val area: Float) {
        val boxArea get() = (r - l) * (b - t)
        fun within(o: Region): Boolean {
            if (o === this || o.boxArea <= boxArea) return false
            val ix = max(0f, min(r, o.r) - max(l, o.l)) * max(0f, min(b, o.b) - max(t, o.t))
            return ix >= CONTAINED * boxArea
        }
    }

    /**
     * Panels as connected non-gutter regions, for the gutter colour giving the most, or null when none
     * covers [MIN_COVER] of the page. Candidates add the colours of uniform rows/columns to
     * [gutterCandidates], which catches grey or gradient gutters.
     */
    private fun regions(luma: FloatArray, w: Int, h: Int, wild: BooleanArray): PanelNode? {
        val cands = gutterCandidates(luma, w, h).toMutableList()
        for (v in uniformColours(luma, w, h)) {
            if (cands.size >= MAX_CANDIDATES) break
            if (cands.all { abs(v - it) > TOL }) cands += v
        }
        var best: List<Region> = emptyList()
        for (c in cands) {
            val found = regionsFor(luma, w, h, wild, c)
            if (found.sumOf { it.area.toDouble() } < MIN_COVER) continue
            if (found.size > best.size) best = found
        }
        if (best.isEmpty()) return null
        return orderRegions(best.map { PanelNode.Leaf(it.l, it.t, it.r, it.b) })
    }

    private const val MAX_CANDIDATES = 5

    /** Median colours of rows/columns (inside a 10 % frame) whose 5th-95th percentile spread is within [TOL], most frequent first. */
    private fun uniformColours(luma: FloatArray, w: Int, h: Int): List<Float> {
        val meds = ArrayList<Float>()
        val x0 = (0.1f * w).toInt()
        val x1 = (0.9f * w).toInt()
        val y0 = (0.1f * h).toInt()
        val y1 = (0.9f * h).toInt()
        val buf = FloatArray(max(w, h))
        for (y in 0 until h) {
            val n = x1 - x0
            for (x in x0 until x1) buf[x - x0] = luma[y * w + x]
            uniformMedian(buf, n)?.let { meds += it }
        }
        for (x in 0 until w) {
            val n = y1 - y0
            for (y in y0 until y1) buf[y - y0] = luma[y * w + x]
            uniformMedian(buf, n)?.let { meds += it }
        }
        val hist = IntArray(256)
        for (m in meds) hist[m.toInt().coerceIn(0, 255)]++
        val smooth = IntArray(256) { v -> (-4..4).sumOf { hist.getOrElse(v + it) { 0 } } }
        return (0 until 256).filter { smooth[it] >= 3 }.sortedByDescending { smooth[it] }.take(40).map { it.toFloat() }
    }

    private fun uniformMedian(buf: FloatArray, n: Int): Float? {
        if (n < 3) return null
        val v = buf.copyOf(n).also { it.sort() }
        fun q(p: Float): Float {
            val pos = p * (n - 1)
            val i = pos.toInt().coerceAtMost(n - 2)
            return v[i] + (v[i + 1] - v[i]) * (pos - i)
        }
        return if (q(0.95f) - q(0.05f) <= TOL) q(0.5f) else null
    }

    /** Connected non-gutter regions for gutter colour [c]: thin links opened away, the page frame and pieces of art dropped. */
    private fun regionsFor(luma: FloatArray, w: Int, h: Int, wild: BooleanArray, c: Float): List<Region> {
        val n = w * h
        val body = BooleanArray(n) { !wild[it] && abs(luma[it] - c) > TOL }
        // 2 px at a 1200 px long side
        val r = max(1, (2f * max(w, h) / 1200f).roundToInt())
        val opened = dilateSquare(erodeSquare(body, w, h, r), w, h, r)
        val lab = IntArray(n)
        val queue = IntArray(n)
        val found = ArrayList<Region>()
        var next = 0
        for (seed in 0 until n) {
            if (!opened[seed] || lab[seed] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = seed
            lab[seed] = next
            var area = 0
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            while (head < tail) {
                val i = queue[head++]
                area++
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && opened[i - 1] && lab[i - 1] == 0) {
                    lab[i - 1] = next
                    queue[tail++] = i - 1
                }
                if (x < w - 1 && opened[i + 1] && lab[i + 1] == 0) {
                    lab[i + 1] = next
                    queue[tail++] = i + 1
                }
                if (y > 0 && opened[i - w] && lab[i - w] == 0) {
                    lab[i - w] = next
                    queue[tail++] = i - w
                }
                if (y < h - 1 && opened[i + w] && lab[i + w] == 0) {
                    lab[i + w] = next
                    queue[tail++] = i + w
                }
            }
            if (area < MIN_REGION_AREA * n) continue
            if (maxY + 1 - minY < MIN_PANEL * h || maxX + 1 - minX < MIN_PANEL * w) continue
            val bw = (maxX + 1 - minX).toFloat() / w
            val bh = (maxY + 1 - minY).toFloat() / h
            val share = area.toFloat() / n
            if (bw * bh > 0.85f && share < 0.5f * bw * bh) continue // the page frame, not a panel
            found += Region(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1f) / w, (maxY + 1f) / h, share)
        }
        // a box holding 2+ others is a background or merged area: drop it, keep the panels inside; a box inside
        // exactly one larger one is a piece of art split off inside that panel: drop the piece
        val panels = found.filter { b -> found.count { it.within(b) } < 2 }
        return panels.filter { a -> panels.none { a.within(it) } }
    }

    /** Recursive cut over panel boxes, rows first then columns, tolerating [ROW_OVERLAP]; no clean cut → [PanelNode.Loose]. */
    private fun orderRegions(boxes: List<PanelNode.Leaf>): PanelNode {
        if (boxes.size == 1) return boxes[0]
        for (horizontal in booleanArrayOf(true, false)) {
            fun lo(b: PanelNode.Leaf) = if (horizontal) b.t else b.l
            fun hi(b: PanelNode.Leaf) = if (horizontal) b.b else b.r
            val sorted = boxes.sortedBy { lo(it) }
            val groups = mutableListOf(mutableListOf(sorted[0]))
            var end = hi(sorted[0])
            for (b in sorted.drop(1)) {
                val ext = min(hi(b) - lo(b), groups.last().maxOf { hi(it) - lo(it) })
                if (lo(b) >= end - ROW_OVERLAP * ext) {
                    groups += mutableListOf(b)
                    end = hi(b)
                } else {
                    groups.last() += b
                    end = max(end, hi(b))
                }
            }
            if (groups.size > 1) return PanelNode.Split(horizontal, groups.map { orderRegions(it) })
        }
        return PanelNode.Loose(boxes)
    }

    /** Erosion by a (2r+1)² square; outside the grid counts as empty. */
    private fun erodeSquare(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray =
        slide(slide(m, w, h, r, horizontal = true, all = true), w, h, r, horizontal = false, all = true)

    /** Dilation by a (2r+1)² square. */
    private fun dilateSquare(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray =
        slide(slide(m, w, h, r, horizontal = true, all = false), w, h, r, horizontal = false, all = false)

    /** 1-D window of radius [r] along rows or columns: true when all ([all]) or any of the window is set. */
    private fun slide(m: BooleanArray, w: Int, h: Int, r: Int, horizontal: Boolean, all: Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val win = 2 * r + 1
        for (line in 0 until lines) {
            fun at(k: Int) = if (horizontal) line * w + k else k * w + line
            var count = 0
            for (k in 0 until min(r, len)) if (m[at(k)]) count++
            for (k in 0 until len) {
                if (k + r < len && m[at(k + r)]) count++
                if (k - r - 1 >= 0 && m[at(k - r - 1)]) count--
                out[at(k)] = if (all) count == win else count > 0
            }
        }
        return out
    }
}
