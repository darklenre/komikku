package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleContourTest {

    // region traceContour

    @Test
    fun `traces the boundary of a solid block`() {
        val w = 12
        val h = 12
        val inside = BooleanArray(w * h)
        for (y in 3..8) for (x in 3..8) inside[y * w + x] = true

        val contour = BubbleExtractor.traceContour(inside, w, h)
        assertTrue(contour != null && contour.size >= 24)
        contour!!
        var i = 0
        while (i < contour.size) {
            // pixel-centre coords of boundary pixels of a [3..8] block
            assertTrue(contour[i] in 3.0f..9.0f, "x=${contour[i]} out of range")
            assertTrue(contour[i + 1] in 3.0f..9.0f, "y=${contour[i + 1]} out of range")
            i += 2
        }
    }

    @Test
    fun `returns null for an isolated pixel`() {
        val w = 10
        val h = 10
        val inside = BooleanArray(w * h)
        inside[5 * w + 5] = true
        assertNull(BubbleExtractor.traceContour(inside, w, h))
    }

    @Test
    fun `returns null for an empty mask`() {
        assertNull(BubbleExtractor.traceContour(BooleanArray(64), 8, 8))
    }

    // endregion

    // region chaikinClosed

    @Test
    fun `chaikin doubles the vertex count of a polygon`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f) // 4 points
        assertEquals(8 * 2, BubbleExtractor.chaikinClosed(square).size) // 4 -> 8 points, x/y pairs
    }

    @Test
    fun `chaikin keeps every point inside the original bounding box`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val cut = BubbleExtractor.chaikinClosed(square)
        var i = 0
        while (i < cut.size) {
            assertTrue(cut[i] in 0f..10f)
            assertTrue(cut[i + 1] in 0f..10f)
            i += 2
        }
    }

    @Test
    fun `chaikin is a no-op below 4 points`() {
        val tri = floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f) // 3 points
        assertTrue(tri.contentEquals(BubbleExtractor.chaikinClosed(tri)))
    }

    // endregion

    // region decimateClosed

    @Test
    fun `decimate thins a dense polygon to the target count`() {
        val dense = FloatArray(400) { it.toFloat() } // 200 points
        val out = BubbleExtractor.decimateClosed(dense, 50)
        assertEquals(50 * 2, out.size)
        // first kept vertex is the first input vertex
        assertEquals(0f, out[0])
        assertEquals(1f, out[1])
    }

    @Test
    fun `decimate leaves a polygon that is already sparse untouched`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(square.contentEquals(BubbleExtractor.decimateClosed(square, 100)))
    }

    // endregion
}
