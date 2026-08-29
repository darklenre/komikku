package eu.kanade.tachiyomi.ui.reader.bubble

import android.graphics.Bitmap
import android.graphics.RectF
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

// Receiver for the `Any.logcat` extension from top-level functions in this file.
private object BubbleLog

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
    val maskWeights: FloatArray? = null,
)

internal fun iou(a: Det, b: Det): Float {
    val ix = max(0f, min(a.r, b.r) - max(a.l, b.l))
    val iy = max(0f, min(a.b, b.b) - max(a.t, b.t))
    val inter = ix * iy
    val union = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
    return if (union <= 0f) 0f else inter / union
}

/**
 * Decodes YOLOv8 detections and optional segmentation masks from [featureMajor] matrix ([features][anchors]).
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
    protoMasks: Array<Array<FloatArray>>? = null,
): List<Bubble> {
    val features = featureMajor.size
    val anchors = featureMajor[0].size
    val hasMasks = features > 32
    val numMasks = if (hasMasks) 32 else 0
    val classes = (features - 4 - numMasks).coerceAtLeast(1)

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

        val maskWeights = if (hasMasks) {
            FloatArray(32) { m -> featureMajor[4 + classes + m][a] }
        } else {
            null
        }

        dets.add(Det(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, best, maskWeights))
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

    // Map from letterbox space back to normalized (0..1) page coordinates
    val contentW = (lb.inputSize - 2 * lb.padX).coerceAtLeast(1).toFloat()
    val contentH = (lb.inputSize - 2 * lb.padY).coerceAtLeast(1).toFloat()
    return kept.map { d ->
        val maskBitmap = if (protoMasks != null && d.maskWeights != null) {
            val protoW = protoMasks[0][0].size // 160
            val protoH = protoMasks[0].size // 160
            val scaleX = protoW.toFloat() / lb.inputSize
            val scaleY = protoH.toFloat() / lb.inputSize

            val pL = (d.l * scaleX).toInt().coerceIn(0, protoW - 1)
            val pT = (d.t * scaleY).toInt().coerceIn(0, protoH - 1)
            val pR = (d.r * scaleX).toInt().coerceIn(pL + 1, protoW)
            val pB = (d.b * scaleY).toInt().coerceIn(pT + 1, protoH)

            val mW = pR - pL
            val mH = pB - pT
            if (mW > 0 && mH > 0) {
                val maskBmp = Bitmap.createBitmap(mW, mH, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(mW * mH)
                val weights = d.maskWeights
                val numCoeffs = minOf(32, protoMasks.size, weights.size)
                var hi = 0
                var minSum = Float.MAX_VALUE
                var maxSum = -Float.MAX_VALUE
                for (y in 0 until mH) {
                    val py = pT + y
                    for (x in 0 until mW) {
                        val px = pL + x
                        var sum = 0f
                        for (m in 0 until numCoeffs) {
                            sum += weights[m] * protoMasks[m][py][px]
                        }
                        if (sum < minSum) minSum = sum
                        if (sum > maxSum) maxSum = sum
                        val sig = 1f / (1f + kotlin.math.exp(-sum))
                        if (sig > 0.5f) hi++
                        // Keep the raw confidence in the alpha channel; the consumer thresholds it
                        // AFTER upscaling to native crop resolution, so the edge stays crisp.
                        val alpha = (sig * 255f).toInt().coerceIn(0, 255)
                        pixels[y * mW + x] = (alpha shl 24) or 0x00FFFFFF
                    }
                }
                maskBmp.setPixels(pixels, 0, mW, 0, 0, mW, mH)
                BubbleLog.logcat(LogPriority.INFO) {
                    "BubblePostprocess: mask ${mW}x$mH cover=${hi * 100 / (mW * mH)}% " +
                        "sumRange=[${"%.2f".format(minSum)},${"%.2f".format(maxSum)}] " +
                        "box640=[${d.l.toInt()},${d.t.toInt()},${d.r.toInt()},${d.b.toInt()}]"
                }
                maskBmp
            } else {
                null
            }
        } else {
            BubbleLog.logcat(LogPriority.INFO) {
                "BubblePostprocess: no mask (proto=${protoMasks != null} weights=${d.maskWeights != null} feat=$features)"
            }
            null
        }

        Bubble(
            rect = RectF(
                ((d.l - lb.padX) / contentW).coerceIn(0f, 1f),
                ((d.t - lb.padY) / contentH).coerceIn(0f, 1f),
                ((d.r - lb.padX) / contentW).coerceIn(0f, 1f),
                ((d.b - lb.padY) / contentH).coerceIn(0f, 1f),
            ),
            confidence = d.score,
            maskBitmap = maskBitmap,
        )
    }
}
