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
}
