package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.Bitmap

/**
 * Detects speech bubbles on a page bitmap. Implementations run inference off the main thread.
 * Rects in the returned [Bubble]s are normalised to 0..1 of [bitmap].
 */
interface BubbleDetector {
    val isAvailable: Boolean

    suspend fun detect(bitmap: Bitmap): List<Bubble>
}

/** Used when Bubble Zoom is off or the device can't run the model — long-tap falls back to the menu. */
object NoopBubbleDetector : BubbleDetector {
    override val isAvailable = false
    override suspend fun detect(bitmap: Bitmap): List<Bubble> = emptyList()
}
