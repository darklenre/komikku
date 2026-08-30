package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class Letterbox(
    val gain: Float,
    val padX: Int,
    val padY: Int,
    val nw: Int,
    val nh: Int,
    val inputSize: Int = 640,
)

internal fun letterboxOf(srcW: Int, srcH: Int, inputSize: Int = 640): Letterbox {
    val gain = min(inputSize / srcW.toFloat(), inputSize / srcH.toFloat())
    val nw = (srcW * gain).toInt().coerceAtLeast(1)
    val nh = (srcH * gain).toInt().coerceAtLeast(1)
    val padX = (inputSize - nw) / 2
    val padY = (inputSize - nh) / 2
    return Letterbox(gain, padX, padY, nw, nh, inputSize)
}

internal class Det(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val score: Float,
)

internal fun iou(a: Det, b: Det): Float {
    val ix = max(0f, min(a.r, b.r) - max(a.l, b.l))
    val iy = max(0f, min(a.b, b.b) - max(a.t, b.t))
    val inter = ix * iy
    val union = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
    return if (union <= 0f) 0f else inter / union
}

/**
 * Decodes a YOLOv8-detect head from [featureMajor] ([features][anchors]) and NMS-filters it.
 *
 * @param coordsIn640Space true if cx,cy,w,h are in pixel coordinates (0..inputSize),
 *                         false if normalized to 0..1 relative to the letterbox square.
 */
internal fun decodeDetections(
    featureMajor: Array<FloatArray>,
    lb: Letterbox,
    coordsIn640Space: Boolean,
    confThreshold: Float = 0.30f,
    iouThreshold: Float = 0.45f,
): List<Bubble> {
    val features = featureMajor.size
    val anchors = featureMajor[0].size
    val classes = (features - 4).coerceAtLeast(1)

    val dets = ArrayList<Det>()
    for (a in 0 until anchors) {
        var best = featureMajor[4][a]
        for (c in 1 until classes) best = max(best, featureMajor[4 + c][a])
        if (best < confThreshold) continue
        var cx = featureMajor[0][a]
        var cy = featureMajor[1][a]
        var w = featureMajor[2][a]
        var h = featureMajor[3][a]

        if (!coordsIn640Space) {
            cx *= lb.inputSize
            cy *= lb.inputSize
            w *= lb.inputSize
            h *= lb.inputSize
        }

        dets.add(Det(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, best))
    }
    if (dets.isEmpty()) return emptyList()

    dets.sortByDescending { it.score }
    val suppressed = BooleanArray(dets.size)
    val kept = ArrayList<Det>()
    for (i in dets.indices) {
        if (suppressed[i]) continue
        kept.add(dets[i])
        for (j in i + 1 until dets.size) {
            if (!suppressed[j] && iou(dets[i], dets[j]) > iouThreshold) suppressed[j] = true
        }
    }

    // Map from letterbox space back to normalized (0..1) page coordinates.
    val contentW = (lb.inputSize - 2 * lb.padX).coerceAtLeast(1).toFloat()
    val contentH = (lb.inputSize - 2 * lb.padY).coerceAtLeast(1).toFloat()
    return kept.map { d ->
        Bubble(
            rect = RectF(
                ((d.l - lb.padX) / contentW).coerceIn(0f, 1f),
                ((d.t - lb.padY) / contentH).coerceIn(0f, 1f),
                ((d.r - lb.padX) / contentW).coerceIn(0f, 1f),
                ((d.b - lb.padY) / contentH).coerceIn(0f, 1f),
            ),
            confidence = d.score,
        )
    }
}
