package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Panel detector backed by the bundled `models/panel_detector_v2.tflite`: a YOLOX-nano (Apache-2.0)
 * trained for Komikku on a single "panel" class (provenance in `assets/models/NOTICE`).
 *
 * Input `[1, 640, 640, 3]` f32 **BGR 0..255**, the page scaled to fit and placed at the top-left of a
 * grey (114) square, as in YOLOX training. Output `[1, 8400, 6]`: per anchor cx, cy, w, h in input
 * pixels, objectness, class score. Boxes with `objectness * class` >= [CONF] go through class-agnostic
 * NMS ([NMS_IOU]).
 *
 * Same threading and backend policy as [TfliteBubbleDetector]: one background thread; the NPU when an
 * ahead-of-time compiled variant exists for this SoC ([LiteRtModel.npuAsset]), else the CPU (~17 ms).
 */
class TflitePanelDetector(context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "panel-detect-tfl").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var model: LiteRtModel? = null

    private val pixels = IntArray(INPUT * INPUT)
    private val input = FloatArray(INPUT * INPUT * 3)

    init {
        try {
            val sources = listOfNotNull(
                LiteRtModel.npuAsset(context, NPU_NAME)?.let { LiteRtModel.Source(it, null, LiteRtModel.Backend.NPU) },
                LiteRtModel.Source(ASSET, ASSET_SHA, LiteRtModel.Backend.CPU),
            )
            model = LiteRtModel.open(context, null, *sources.toTypedArray())
            logcat(LogPriority.WARN) { "TflitePanelDetector: ready (backend=${model?.backend})" }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR) { "TflitePanelDetector: session init failed: ${t.message}" }
        }
    }

    val isAvailable: Boolean get() = model != null

    /** Panel boxes `[l, t, r, b]` normalised 0..1 of [bitmap], or null when the model can't run. */
    suspend fun detect(bitmap: Bitmap): List<FloatArray>? {
        if (model == null) return null
        return withContext(dispatcher) {
            val m = model ?: throw CancellationException("panel detector closed")
            runCatching { infer(m, bitmap) }
                .onFailure { logcat(LogPriority.ERROR) { "TflitePanelDetector: inference failed: ${it.message}" } }
                .getOrNull()
        }
    }

    /** Frees the model once the inference in flight (if any) is done; returns without waiting. */
    fun close() {
        try {
            executor.execute {
                runCatching { model?.close() }
                model = null
            }
            executor.shutdown()
        } catch (t: Throwable) {
            logcat { "TflitePanelDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(m: LiteRtModel, bitmap: Bitmap): List<FloatArray> {
        val w = bitmap.width
        val h = bitmap.height
        val r = min(INPUT.toFloat() / w, INPUT.toFloat() / h)
        val nw = max(1, (w * r).roundToInt()).coerceAtMost(INPUT)
        val nh = max(1, (h * r).roundToInt()).coerceAtMost(INPUT)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        scaled.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        if (scaled != bitmap) scaled.recycle()
        var i = 0
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                if (x < nw && y < nh) {
                    val p = pixels[y * nw + x]
                    input[i] = (p and 0xFF).toFloat()
                    input[i + 1] = ((p shr 8) and 0xFF).toFloat()
                    input[i + 2] = ((p shr 16) and 0xFF).toFloat()
                } else {
                    input[i] = PAD
                    input[i + 1] = PAD
                    input[i + 2] = PAD
                }
                i += 3
            }
        }

        val t0 = System.nanoTime()
        m.writeInput(0, input)
        m.run()
        val out = m.readFloat(0)
        logcat(LogPriority.DEBUG) { "TflitePanelDetector: inference ${(System.nanoTime() - t0) / 1_000_000}ms (${m.backend})" }
        check(out.size == ANCHORS * 6) { "unexpected output size ${out.size}" }

        val boxes = ArrayList<FloatArray>()
        for (k in 0 until ANCHORS) {
            val o = k * 6
            val score = out[o + 4] * out[o + 5]
            if (score < CONF) continue
            val cx = out[o]
            val cy = out[o + 1]
            val bw = out[o + 2]
            val bh = out[o + 3]
            val l = ((cx - bw / 2) / r / w).coerceIn(0f, 1f)
            val t = ((cy - bh / 2) / r / h).coerceIn(0f, 1f)
            val rr = ((cx + bw / 2) / r / w).coerceIn(0f, 1f)
            val b = ((cy + bh / 2) / r / h).coerceIn(0f, 1f)
            if (rr > l && b > t) boxes += floatArrayOf(l, t, rr, b, score)
        }
        return nms(boxes, NMS_IOU)
    }

    private companion object {
        const val ASSET = "models/panel_detector_v2.tflite"

        /** Name of the NPU variant of [ASSET] (same input and output). */
        const val NPU_NAME = "panel_detector_v2"

        /** SHA-256 of [ASSET]; a truncated/corrupt model otherwise SIGABRTs deep in LiteRT. */
        const val ASSET_SHA = "005132193d259a9ca7b32b99d9f1b8ce30c830125fd2fe252de02f05249a679d"

        const val INPUT = 640
        const val ANCHORS = 8400
        const val PAD = 114f
        const val CONF = 0.3f
        const val NMS_IOU = 0.45f
    }
}

/** Greedy class-agnostic NMS over `[l, t, r, b, score]` boxes; returns the kept boxes as `[l, t, r, b]`. */
internal fun nms(boxes: List<FloatArray>, iouThreshold: Float = 0.45f): List<FloatArray> {
    val kept = ArrayList<FloatArray>()
    for (b in boxes.sortedByDescending { it[4] }) {
        if (kept.none { boxIoU(it, b) > iouThreshold }) kept += b
    }
    return kept.map { floatArrayOf(it[0], it[1], it[2], it[3]) }
}

private fun boxIoU(a: FloatArray, b: FloatArray): Float {
    val inter = (min(a[2], b[2]) - max(a[0], b[0])).coerceAtLeast(0f) * (min(a[3], b[3]) - max(a[1], b[1])).coerceAtLeast(0f)
    val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return if (union <= 0f) 0f else inter / union
}
