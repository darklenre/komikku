package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleMaskTest {

    private fun grid(vararg rows: String): Triple<BooleanArray, Int, Int> {
        val w = rows[0].length
        return Triple(BooleanArray(w * rows.size) { rows[it / w][it % w] == '#' }, w, rows.size)
    }

    private fun BooleanArray.render(w: Int) = indices.joinToString("") { if (this[it]) "#" else "." }.chunked(w)

    @Test
    fun `fillHoles fills enclosed glyph holes but not open bays`() {
        val (m, w, h) = grid(
            ".......",
            ".#####.",
            ".#..#..",
            ".#####.",
            ".......",
        )
        assertEquals(
            listOf(
                ".......",
                ".#####.",
                ".####..",
                ".#####.",
                ".......",
            ),
            fillHoles(m, w, h).render(w),
        )
    }

    @Test
    fun `largestBlob keeps only the biggest component`() {
        val (m, w, h) = grid(
            "##...#",
            "##....",
            "##..##",
        )
        assertEquals(
            listOf(
                "##....",
                "##....",
                "##....",
            ),
            largestBlob(m, w, h).render(w),
        )
    }

    @Test
    fun `antialias softens only the edge`() {
        val (m, w, h) = grid(
            "#####",
            "#####",
            "#####",
            ".....",
        )
        val a = antialias(m, w, h)
        assertEquals(1f, a[1 * w + 2])
        assertTrue(a[2 * w + 2] in 0.6f..0.7f)
        assertTrue(a[3 * w + 2] in 0.4f..0.6f)
    }

    @Test
    fun `text block goes to the balloon that contains it, smallest on a tie`() {
        val big = floatArrayOf(0f, 0f, 1f, 1f, 0.9f)
        val small = floatArrayOf(0.1f, 0.1f, 0.4f, 0.4f, 0.9f)
        val right = floatArrayOf(0.6f, 0.1f, 0.9f, 0.4f, 0.9f)
        val texts = listOf(
            floatArrayOf(0.15f, 0.15f, 0.35f, 0.35f, 0.8f), // inside small (and big)
            floatArrayOf(0.65f, 0.15f, 0.85f, 0.35f, 0.8f), // inside right (and big)
            floatArrayOf(0.45f, 0.6f, 0.55f, 0.9f, 0.8f), // only big
            floatArrayOf(1.5f, 1.5f, 1.6f, 1.6f, 0.8f), // nobody
        )
        assertEquals(listOf(1, 2, 0, -1), textOwners(listOf(big, small, right), texts).toList())
    }

    @Test
    fun `dedupe drops a same-object duplicate but keeps neighbours`() {
        val a = floatArrayOf(0f, 0f, 0.5f, 0.5f, 0.9f)
        val dup = floatArrayOf(0.01f, 0f, 0.5f, 0.51f, 0.6f)
        val next = floatArrayOf(0.5f, 0f, 1f, 0.5f, 0.8f)
        assertEquals(listOf(0.9f, 0.8f), dedupe(listOf(dup, a, next)).map { it[4] })
    }

    @Test
    fun `smoothMask keeps a straight edge and removes a one-pixel step`() {
        val (m, w, h) = grid(
            "##########",
            "##########",
            "##########",
            "#####.....",
            "##########",
            "##########",
            "..........",
            "..........",
            "..........",
            "..........",
        )
        val s = smoothMask(m, w, h, 1)
        // the notch inside the fill closes, the straight bottom edge stays where it is
        assertTrue((0 until w).all { s[3 * w + it] })
        assertTrue((0 until w).all { s[5 * w + it] && !s[6 * w + it] })
    }

    @Test
    fun `inkSnap takes in the balloon outline but not a dark area next to it`() {
        val w = 60
        val h = 40
        val luma = FloatArray(w * h) { 235f } // light background
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val inBalloon = x in 10..39 && y in 10..29
                val inStroke = x in 7..42 && y in 7..32 && !inBalloon
                when {
                    inBalloon -> luma[i] = 255f
                    inStroke -> luma[i] = 20f
                    x >= 43 -> luma[i] = 15f // black panel / hair touching the right side of the stroke
                }
                mask[i] = inBalloon
            }
        }
        val s = inkSnap(mask, luma, w, h, 4)
        // left, top and bottom strokes (thin, light background beyond) are now in the cutout
        assertTrue((7..9).all { s[20 * w + it] })
        assertTrue((7..9).all { s[it * w + 25] })
        assertTrue((30..32).all { s[it * w + 25] })
        // nothing of the dark area right of the stroke
        assertTrue((44 until w).none { s[20 * w + it] })
        // background stays out
        assertTrue(!s[20 * w + 3] && !s[2 * w + 25])
    }
}
