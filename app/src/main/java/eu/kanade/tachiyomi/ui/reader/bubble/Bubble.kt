package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.RectF

/**
 * A detected speech bubble. [rect] is normalised to 0..1 of the page image, so it is independent
 * of the resolution the detector ran at and of any later crop/merge transforms of the display view.
 *
 * [lobes] is set only on a bubble that is the union of several linked detections (a balloon the
 * artist pinched into stacked / side-by-side lobes): it holds the original per-lobe rects so the
 * extractor can mask each lobe separately and OR the results, instead of prompting one model call
 * with a wide box it segments badly. Null on an ordinary single-detection bubble.
 */
data class Bubble(
    val rect: RectF,
    val confidence: Float,
    val lobes: List<RectF>? = null,
)
