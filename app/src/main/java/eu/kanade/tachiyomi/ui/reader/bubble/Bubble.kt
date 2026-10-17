package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.RectF

/**
 * A detected speech balloon (or caption box). [rect] is normalised to 0..1 of the page image, so it
 * is independent of the resolution the detector ran at and of any later crop/merge transforms of the
 * display view.
 *
 * [text] holds the text blocks the detector found inside this balloon (also 0..1 of the page). Each
 * block belongs to exactly one balloon, so two balloons sharing a background stay two targets.
 * Empty when the detector found no text block for it (e.g. a box holding only "…").
 */
data class Bubble(
    val rect: RectF,
    val confidence: Float,
    val text: List<RectF> = emptyList(),
)
