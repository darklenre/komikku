package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.Bitmap
import android.graphics.Path

/**
 * A shaped speech-bubble cutout ready to be drawn floating on the reader overlay.
 *
 * @param bitmap the crop cut to the bubble silhouette (transparent around it), **without** the
 *   outline baked in.
 * @param outline the smoothed silhouette as a closed [Path] in the unit square (0..1 of [bitmap]),
 *   or null when there's nothing to trace. The overlay strokes it live so the edge stays crisp at
 *   any display scale.
 * @param outlineFraction outline half-width as a fraction of the cutout's short side; the overlay
 *   turns it into a pixel `strokeWidth` for the size it actually draws at. 0 disables the outline.
 * @param bodyOffsetX horizontal offset of the bubble *body* centre from the bitmap centre, in
 *   0..1-of-bitmap units (the tail shifts the geometric centre); the overlay frames on the body.
 * @param bodyOffsetY vertical counterpart of [bodyOffsetX].
 */
class BubbleCutout(
    val bitmap: Bitmap,
    val outline: Path? = null,
    val outlineFraction: Float = 0f,
    val bodyOffsetX: Float = 0f,
    val bodyOffsetY: Float = 0f,
)
