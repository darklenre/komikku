package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubblePostprocessTest {

    // region letterboxOf

    @Test
    fun `letterbox pads top and bottom for a wide image`() {
        val lb = letterboxOf(1000, 500, 640)
        assertEquals(0.64f, lb.gain, 1e-4f)
        assertEquals(640, lb.nw)
        assertEquals(320, lb.nh)
        assertEquals(0, lb.padX)
        assertEquals(160, lb.padY)
    }

    @Test
    fun `letterbox pads left and right for a tall image`() {
        val lb = letterboxOf(500, 1000, 640)
        assertEquals(320, lb.nw)
        assertEquals(640, lb.nh)
        assertEquals(160, lb.padX)
        assertEquals(0, lb.padY)
    }

    @Test
    fun `letterbox is identity for a square image at native size`() {
        val lb = letterboxOf(640, 640, 640)
        assertEquals(1f, lb.gain, 1e-4f)
        assertEquals(0, lb.padX)
        assertEquals(0, lb.padY)
    }

    // endregion

    // region iou

    @Test
    fun `iou of a box with itself is 1`() {
        val a = Det(0f, 0f, 10f, 10f, 1f)
        assertEquals(1f, iou(a, a), 1e-6f)
    }

    @Test
    fun `iou of disjoint boxes is 0`() {
        val a = Det(0f, 0f, 10f, 10f, 1f)
        val b = Det(20f, 20f, 30f, 30f, 1f)
        assertEquals(0f, iou(a, b), 1e-6f)
    }

    @Test
    fun `iou of half-overlapping boxes`() {
        val a = Det(0f, 0f, 2f, 2f, 1f)
        val b = Det(1f, 0f, 3f, 2f, 1f)
        // intersection 1x2 = 2, union 4 + 4 - 2 = 6
        assertEquals(1f / 3f, iou(a, b), 1e-6f)
    }

    // endregion

    // region decodeDetsNormalized

    /** feature-major layout: rows [cx, cy, w, h, score...], one column per anchor. */
    private fun featureMajor(vararg anchors: FloatArray): Array<FloatArray> {
        val features = anchors[0].size
        return Array(features) { f -> FloatArray(anchors.size) { a -> anchors[a][f] } }
    }

    @Test
    fun `decode maps a 640-space box to normalized coords`() {
        val lb = letterboxOf(640, 640, 640)
        val fm = featureMajor(
            floatArrayOf(320f, 320f, 64f, 64f, 0.9f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
        )
        val dets = decodeDetsNormalized(fm, lb, coordsIn640Space = true)
        assertEquals(1, dets.size)
        assertEquals(0.45f, dets[0].l, 1e-3f)
        assertEquals(0.45f, dets[0].t, 1e-3f)
        assertEquals(0.55f, dets[0].r, 1e-3f)
        assertEquals(0.55f, dets[0].b, 1e-3f)
        assertEquals(0.9f, dets[0].score, 1e-6f)
    }

    @Test
    fun `decode drops boxes below the confidence threshold`() {
        val lb = letterboxOf(640, 640, 640)
        val fm = featureMajor(floatArrayOf(320f, 320f, 64f, 64f, 0.1f))
        assertTrue(decodeDetsNormalized(fm, lb, coordsIn640Space = true, confThreshold = 0.30f).isEmpty())
    }

    @Test
    fun `NMS collapses two heavily overlapping boxes into one`() {
        val lb = letterboxOf(640, 640, 640)
        val fm = featureMajor(
            floatArrayOf(320f, 320f, 64f, 64f, 0.9f),
            floatArrayOf(322f, 322f, 64f, 64f, 0.8f),
        )
        val dets = decodeDetsNormalized(fm, lb, coordsIn640Space = true)
        assertEquals(1, dets.size)
        assertEquals(0.9f, dets[0].score, 1e-6f) // the higher-scoring one survives
    }

    @Test
    fun `NMS keeps two disjoint boxes`() {
        val lb = letterboxOf(640, 640, 640)
        val fm = featureMajor(
            floatArrayOf(100f, 100f, 64f, 64f, 0.9f),
            floatArrayOf(500f, 500f, 64f, 64f, 0.8f),
        )
        assertEquals(2, decodeDetsNormalized(fm, lb, coordsIn640Space = true).size)
    }

    @Test
    fun `decode scales normalized-space coords by the input size`() {
        val lb = letterboxOf(640, 640, 640)
        // coords already 0..1 of the letterbox square: cx,cy,w,h = 0.5, 0.5, 0.1, 0.1
        val fm = featureMajor(floatArrayOf(0.5f, 0.5f, 0.1f, 0.1f, 0.9f))
        val dets = decodeDetsNormalized(fm, lb, coordsIn640Space = false)
        assertEquals(1, dets.size)
        assertEquals(0.45f, dets[0].l, 1e-3f)
        assertEquals(0.55f, dets[0].r, 1e-3f)
    }

    // endregion
}
