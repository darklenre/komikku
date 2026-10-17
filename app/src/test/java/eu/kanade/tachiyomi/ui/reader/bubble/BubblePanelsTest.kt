package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BubblePanelsTest {

    /** White page, black panels: one full-width top row, then a tall left panel next to two stacked right ones. */
    private fun page(): Triple<FloatArray, Int, Int> {
        val w = 200
        val h = 300
        val luma = FloatArray(w * h) { 250f }
        fun panel(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) for (x in x0 until x1) luma[y * w + x] = 40f
        }
        panel(10, 10, 190, 90) // A: top row
        panel(10, 100, 95, 290) // B: tall left
        panel(105, 100, 190, 190) // C: right top
        panel(105, 200, 190, 290) // D: right bottom
        return Triple(luma, w, h)
    }

    @Test
    fun `xy-cut finds the panels in reading order for both directions`() {
        val (luma, w, h) = page()
        val tree = BubblePanels.detect(luma, w, h, emptyList())
        fun names(dir: ReadingDirection) = tree.leaves(dir).map { l ->
            val cx = (l.l + l.r) / 2
            val cy = (l.t + l.b) / 2
            when {
                cy < 0.33f -> "A"
                cx < 0.5f -> "B"
                cy < 0.66f -> "C"
                else -> "D"
            }
        }
        assertEquals(listOf("A", "B", "C", "D"), names(ReadingDirection.LTR))
        assertEquals(listOf("A", "C", "D", "B"), names(ReadingDirection.RTL))
    }

    @Test
    fun `a balloon straddling a gutter does not hide it`() {
        val (luma, w, h) = page()
        // balloon art bridging A and the row below
        for (y in 80 until 110) for (x in 60 until 140) luma[y * w + x] = 255f.also { if (x == 60 || x == 139) luma[y * w + x] = 0f }
        val balloon = floatArrayOf(60f / w, 80f / h, 140f / w, 110f / h)
        assertEquals(4, BubblePanels.detect(luma, w, h, listOf(balloon)).leaves(ReadingDirection.LTR).size)
    }

    @Test
    fun `a panel overlapping the gutter between rows still reads row by row`() {
        // two rows of two panels; the bottom-right one is drawn over the gutter (white border, no straight
        // gutter across the page), so only the column cut exists: an XY-cut would read the left column first
        val w = 200
        val h = 300
        val luma = FloatArray(w * h) { 250f }
        fun panel(x0: Int, y0: Int, x1: Int, y1: Int, v: Float) {
            for (y in y0 until y1) for (x in x0 until x1) luma[y * w + x] = v
        }
        panel(10, 10, 95, 100, 40f) // L1
        panel(105, 10, 190, 100, 60f) // R1
        panel(10, 110, 95, 290, 80f) // L2
        panel(105, 85, 190, 290, 250f) // R2's white border over R1's bottom...
        panel(108, 88, 187, 287, 100f) // ...and R2 itself
        val names = BubblePanels.detect(luma, w, h, emptyList()).leaves(ReadingDirection.LTR).map { l ->
            val left = (l.l + l.r) / 2 < 0.5f
            val top = (l.t + l.b) / 2 < 0.25f
            (if (left) "L" else "R") + (if (top) "1" else "2")
        }
        assertEquals(listOf("L1", "R1", "L2", "R2"), names)
    }

    @Test
    fun `bubbles are read panel by panel`() {
        val (luma, w, h) = page()
        val tree = BubblePanels.detect(luma, w, h, emptyList())
        // a bubble high in tall panel B and one in C at the same height: LTR reads B first only by panel order,
        // and the low bubble in B still comes before C because it belongs to B
        val boxes = listOf(
            BubbleReadingOrder.Box(0.75f, 0.45f, 0.1f, 0.05f), // C
            BubbleReadingOrder.Box(0.25f, 0.85f, 0.1f, 0.05f), // B low
            BubbleReadingOrder.Box(0.25f, 0.45f, 0.1f, 0.05f), // B high
        )
        assertEquals(listOf(2, 1, 0), BubbleReadingOrder.orderInPanels(boxes, ReadingDirection.LTR, tree.leaves(ReadingDirection.LTR)))
    }
}
