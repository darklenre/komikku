package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * A balloon cutout ready to be drawn floating on the reader overlay.
 *
 * @param bitmap the crop around the balloon, transparent outside the balloon shape (the page's own
 *   ink border is part of the shape; nothing synthetic is drawn on top).
 * @param context the same crop fully opaque, or null. The overlay draws it dimmed behind [bitmap] so
 *   the balloon stays anchored in its panel and a few px of mask error never read as a hard cut.
 * @param source the crop's region in page px (the bitmaps may be downsampled from it).
 * @param bodyOffsetX horizontal offset of the balloon *body* (its text) from the bitmap centre, in
 *   0..1-of-bitmap units (a tail shifts the geometric centre); the overlay frames on the body.
 * @param bodyOffsetY vertical counterpart of [bodyOffsetX].
 */
class BubbleCutout(
    val bitmap: Bitmap,
    val context: Bitmap? = null,
    val source: Rect? = null,
    val bodyOffsetX: Float = 0f,
    val bodyOffsetY: Float = 0f,
) {
    fun recycle() {
        bitmap.recycle()
        context?.recycle()
    }
}
