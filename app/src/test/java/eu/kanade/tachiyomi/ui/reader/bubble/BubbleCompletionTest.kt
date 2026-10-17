package eu.kanade.tachiyomi.ui.reader.bubble

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
