package eu.kanade.tachiyomi.ui.reader.bubble

import eu.kanade.tachiyomi.ui.reader.bubble.BubbleReadingOrder.Box
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BubbleReadingOrderTest {

    private fun box(cx: Float, cy: Float, size: Float = 0.15f) = Box(cx, cy, size, size)

    @Test
    fun `single box keeps its index`() {
        assertEquals(listOf(0), BubbleReadingOrder.orderIndices(listOf(box(0.5f, 0.5f)), ReadingDirection.RTL))
    }

    @Test
    fun `a single column is ordered top-to-bottom regardless of input order`() {
        val boxes = listOf(
            box(0.5f, 0.7f), // 0
            box(0.5f, 0.1f), // 1
            box(0.5f, 0.4f), // 2
        )
        assertEquals(listOf(1, 2, 0), BubbleReadingOrder.orderIndices(boxes, ReadingDirection.LTR))
    }

    @Test
    fun `2x2 grid reads right-to-left per row for RTL`() {
        val boxes = listOf(
            box(0.7f, 0.2f), // 0 top-right
            box(0.3f, 0.2f), // 1 top-left
            box(0.7f, 0.7f), // 2 bottom-right
            box(0.3f, 0.7f), // 3 bottom-left
        )
        assertEquals(listOf(0, 1, 2, 3), BubbleReadingOrder.orderIndices(boxes, ReadingDirection.RTL))
    }

    @Test
    fun `2x2 grid reads left-to-right per row for LTR`() {
        val boxes = listOf(
            box(0.7f, 0.2f), // 0 top-right
            box(0.3f, 0.2f), // 1 top-left
            box(0.7f, 0.7f), // 2 bottom-right
            box(0.3f, 0.7f), // 3 bottom-left
        )
        assertEquals(listOf(1, 0, 3, 2), BubbleReadingOrder.orderIndices(boxes, ReadingDirection.LTR))
    }

    @Test
    fun `two bubbles stacked on one side stay in order on a near-tied X`() {
        // Right column has two stacked bubbles; a naive per-band X sort would interleave them.
        val boxes = listOf(
            box(0.75f, 0.15f), // 0 top-right
            box(0.25f, 0.20f), // 1 top-left (slightly lower)
            box(0.75f, 0.35f), // 2 right, just below 0 — same reading band as 0? no, next band
        )
        val rtl = BubbleReadingOrder.orderIndices(boxes, ReadingDirection.RTL)
        // 0 (top-right) before 1 (top-left) before 2 (lower right)
        assertEquals(listOf(0, 1, 2), rtl)
    }
}
