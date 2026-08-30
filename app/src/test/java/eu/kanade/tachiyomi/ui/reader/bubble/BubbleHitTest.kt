package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleHitTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    @Test
    fun `no match returns -1`() {
        assertEquals(-1, BubbleHit.pickIndex(listOf(box(0.1f, 0.1f, 0.3f, 0.3f)), 0.8f, 0.8f))
    }

    @Test
    fun `centre of a single bubble matches`() {
        assertEquals(0, BubbleHit.pickIndex(listOf(box(0.2f, 0.2f, 0.6f, 0.6f)), 0.4f, 0.4f))
    }

    @Test
    fun `a point only in a rect corner loses to a bubble whose ellipse contains it`() {
        val a = box(0.0f, 0.0f, 0.8f, 0.8f)
        val b = box(0.02f, 0.02f, 0.18f, 0.18f)
        // (0.1,0.1) is inside B's ellipse and only in A's corner → B wins.
        assertEquals(1, BubbleHit.pickIndex(listOf(a, b), 0.1f, 0.1f))
    }

    @Test
    fun `overlapping bubbles both containing the point tie-break to the smaller one`() {
        val big = box(0.0f, 0.0f, 0.9f, 0.9f)
        val small = box(0.3f, 0.3f, 0.6f, 0.6f)
        assertEquals(1, BubbleHit.pickIndex(listOf(big, small), 0.45f, 0.45f))
    }

    @Test
    fun `corner-only match is still returned when it is the only candidate`() {
        val thinWide = box(0.0f, 0.4f, 1.0f, 0.5f)
        assertEquals(0, BubbleHit.pickIndex(listOf(thinWide), 0.02f, 0.41f))
    }

    @Test
    fun `synthetic rect is centred on the tap and clamped to the page`() {
        val r = BubbleHit.synthRect(0.5f, 0.5f, 0.22f, 0.14f)
        assertEquals(0.5f, (r[0] + r[2]) / 2f, 1e-4f)
        assertEquals(0.5f, (r[1] + r[3]) / 2f, 1e-4f)

        val edge = BubbleHit.synthRect(0.01f, 0.99f, 0.22f, 0.14f)
        assertTrue(edge[0] >= 0f && edge[1] >= 0f && edge[2] <= 1f && edge[3] <= 1f)
    }

    @Test
    fun `median picks the middle element, null for empty`() {
        assertEquals(0.2f, BubbleHit.median(listOf(0.1f, 0.2f, 0.9f)))
        assertEquals(null, BubbleHit.median(emptyList()))
    }
}
