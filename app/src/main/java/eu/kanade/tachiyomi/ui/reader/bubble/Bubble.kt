package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.RectF

/**
 * A detected speech bubble. [rect] is normalised to 0..1 of the page image, so it is independent
 * of the resolution the detector ran at and of any later crop/merge transforms of the display view.
 */
data class Bubble(
    val rect: RectF,
    val confidence: Float,
)
