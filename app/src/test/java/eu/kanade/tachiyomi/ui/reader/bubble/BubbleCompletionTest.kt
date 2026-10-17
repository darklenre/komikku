package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BubbleCompletionTest {

    @Test
    fun `a balloon cut from its linked neighbour is closed with a round outline`() {
        val w = 140
        val h = 90
        val luma = FloatArray(w * h) { 120f } // mid-grey art around
        fun disc(cx: Int, cy: Int, r: Float, x: Int, y: Int) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inUnion = disc(50, 45, 32f, x, y) || disc(92, 45, 32f, x, y)
                val inFill = disc(50, 45, 29f, x, y) || disc(92, 45, 29f, x, y)
                if (inUnion) luma[y * w + x] = if (inFill) 255f else 15f
            }
        }
        val argb = IntArray(w * h) {
            val v = luma[it].toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        // our balloon = the left lobe, cut straight through the shared fill at x = 71
        val mask = BooleanArray(w * h) { (it % w) <= 71 && luma[it] != 120f }
        val res = BubbleCompletion.complete(
            argb,
            luma,
            mask,
            w,
            h,
            others = listOf(floatArrayOf(62f, 13f, 124f, 77f)),
            ownText = listOf(floatArrayOf(38f, 40f, 62f, 50f)),
        )
        assertNotNull(res)
        res!!
        val row = 45 * w
        // the new right edge bulges past the cut and stays well short of the neighbour's centre
        val right = (0 until w).last { res.mask[row + it] }
        assertTrue(right in 74..88, "right edge at $right")
        // and it has a dark outline there, while the inside stays fill-white
        assertTrue(((res.pixels[row + right] shr 8) and 0xFF) < 120, "outline missing")
        assertTrue(((res.pixels[row + right - 8] shr 8) and 0xFF) > 230, "fill not restored")
    }

    @Test
    fun `a balloon cut raggedly along the letters is closed at the neck`() {
        val w = 140
        val h = 90
        val (luma, argb) = linkedPair(w, h)
        // SAM-like cut: stops at x = 64..71 in uneven teeth (it follows the glyphs of the last line)
        val mask = BooleanArray(w * h) {
            val x = it % w
            val y = it / w
            x <= 64 + (y * 7 % 8) && luma[it] != 120f
        }
        val res = BubbleCompletion.completeNeck(
            argb,
            luma,
            mask,
            w,
            h,
            ownText = listOf(floatArrayOf(38f, 40f, 62f, 50f)),
            otherText = listOf(floatArrayOf(80f, 40f, 104f, 50f)),
        )
        assertNotNull(res)
        res!!
        assertTrue(res.linked != null)
        // the right side is one smooth bulge: every row through the neck ends within a few px of its neighbours
        val ends = (30..60).map { y -> (0 until w).last { res.mask[y * w + it] } }
        assertTrue(ends.zipWithNext().all { (a, b) -> abs(a - b) <= 3 }, "ragged edge $ends")
        val right = ends[45 - 30]
        assertTrue(right in 72..88, "right edge at $right")
        assertTrue(((res.pixels[45 * w + right] shr 8) and 0xFF) < 120, "outline missing")
        assertTrue(((res.pixels[45 * w + right - 8] shr 8) and 0xFF) > 230, "fill not restored")
    }

    @Test
    fun `a balloon whose neighbour is not linked is left alone`() {
        val w = 140
        val h = 90
        val (luma, argb) = linkedPair(w, h)
        val mask = BooleanArray(w * h) { (it % w) <= 71 && luma[it] != 120f }
        // the other text is outside the shared fill
        val res = BubbleCompletion.completeNeck(
            argb,
            luma,
            mask,
            w,
            h,
            ownText = listOf(floatArrayOf(38f, 40f, 62f, 50f)),
            otherText = listOf(floatArrayOf(120f, 2f, 138f, 10f)),
        )
        assertNull(res)
    }

    @Test
    fun `distTo is the exact euclidean distance`() {
        val w = 37
        val h = 23
        val rnd = java.util.Random(7)
        val target = BooleanArray(w * h) { rnd.nextInt(40) == 0 }
        for (border in listOf(false, true)) {
            val d = BubbleCompletion.distTo(target, w, h, borderIsTarget = border)
            for (i in 0 until w * h) {
                val x = i % w
                val y = i / w
                var best = Double.MAX_VALUE
                for (j in 0 until w * h) if (target[j]) best = minOf(best, Math.hypot((x - j % w).toDouble(), (y - j / w).toDouble()))
                if (border) best = minOf(best, (x + 1).toDouble(), (y + 1).toDouble(), (w - x).toDouble(), (h - y).toDouble())
                assertTrue(abs(d[i] - best) < 1e-3, "($x,$y) border=$border: ${d[i]} vs $best")
            }
        }
    }

    /** Two overlapping round balloons (black outline, one shared white fill) on mid-grey art. */
    private fun linkedPair(w: Int, h: Int): Pair<FloatArray, IntArray> {
        val luma = FloatArray(w * h) { 120f }
        fun disc(cx: Int, cy: Int, r: Float, x: Int, y: Int) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inUnion = disc(50, 45, 32f, x, y) || disc(92, 45, 32f, x, y)
                val inFill = disc(50, 45, 29f, x, y) || disc(92, 45, 29f, x, y)
                if (inUnion) luma[y * w + x] = if (inFill) 255f else 15f
            }
        }
        val argb = IntArray(w * h) {
            val v = luma[it].toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return luma to argb
    }
}
