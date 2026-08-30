package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BubbleGroupingTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    // args mirror BubbleDetection.LINK_OVERLAP_FRAC / LINK_STACK_GAP_FRAC / LINK_SIDE_GAP_FRAC
    private fun group(boxes: List<FloatArray>) = groupLinked(boxes, 0.5f, 0.35f, 0.16f)

    @Test
    fun `three vertically stacked aligned lobes collapse into one group`() {
        val boxes = listOf(
            box(0.10f, 0.60f, 0.30f, 0.70f),
            box(0.11f, 0.71f, 0.31f, 0.81f),
            box(0.10f, 0.82f, 0.29f, 0.92f),
        )
        val groups = group(boxes)
        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1, 2), groups[0])
    }

    @Test
    fun `two horizontally linked lobes collapse into one group`() {
        val boxes = listOf(
            box(0.10f, 0.30f, 0.28f, 0.45f),
            box(0.29f, 0.31f, 0.50f, 0.46f),
        )
        assertEquals(1, group(boxes).size)
    }

    @Test
    fun `side-by-side bubbles with a clear gap stay separate`() {
        val boxes = listOf(
            box(0.05f, 0.20f, 0.25f, 0.35f),
            box(0.60f, 0.20f, 0.80f, 0.35f),
        )
        assertEquals(2, group(boxes).size)
    }

    @Test
    fun `vertically aligned but far apart stay separate`() {
        val boxes = listOf(
            box(0.10f, 0.10f, 0.30f, 0.20f),
            box(0.10f, 0.70f, 0.30f, 0.80f),
        )
        assertEquals(2, group(boxes).size)
    }

    @Test
    fun `stacked but poorly x-aligned stay separate`() {
        val boxes = listOf(
            box(0.10f, 0.40f, 0.30f, 0.50f),
            box(0.28f, 0.51f, 0.48f, 0.61f), // only ~10% width overlap
        )
        assertEquals(2, group(boxes).size)
    }

    @Test
    fun `only the touching pair of three merges`() {
        val boxes = listOf(
            box(0.10f, 0.10f, 0.30f, 0.20f), // isolated at top
            box(0.10f, 0.60f, 0.30f, 0.70f), // pair
            box(0.10f, 0.71f, 0.30f, 0.81f), // pair
        )
        val groups = group(boxes).sortedBy { it.first() }
        assertEquals(2, groups.size)
        assertEquals(listOf(0), groups[0])
        assertEquals(listOf(1, 2), groups[1])
    }
}
