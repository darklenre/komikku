package eu.kanade.tachiyomi.ui.reader.bubble

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * [BubbleDetector] backed by ONNX Runtime and the bundled model
 * `assets/models/bubble_detector.onnx` (`ogkalu/comic-speech-bubble-detector-yolov8m`, Apache-2.0):
 * YOLOv8-detect, 1 class, 640 input, output `[1, 5, 8400]` = [cx, cy, w, h, score] in 640-space.
 *
 * Inference runs on a single background thread. Postprocess: threshold -> NMS -> map boxes from the
 * letterboxed 640 space back to normalised page coordinates.
 */
class OnnxBubbleDetector(context: Context) : BubbleDetector {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val env: OrtEnvironment?
    private val session: OrtSession?

    // Inference runs one page at a time on [dispatcher], so these scratch buffers can be reused
    // across calls instead of allocating ~6.5 MB per page.
    private val inputBuffer = FloatArray(3 * INPUT * INPUT)
    private val pixelBuffer = IntArray(INPUT * INPUT)

    init {
        var e: OrtEnvironment? = null
        var s: OrtSession? = null
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            e = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            s = e.createSession(bytes, options)
            logcat { "OnnxBubbleDetector: session ready" }
        } catch (t: Throwable) {
            logcat { "OnnxBubbleDetector: session init failed: ${t.message}" }
        }
        env = e
        session = s
    }

    override val isAvailable: Boolean get() = session != null

    override suspend fun detect(bitmap: Bitmap): List<Bubble> {
        val ortSession = session ?: return emptyList()
        return withContext(dispatcher) {
            runCatching { infer(ortSession, bitmap) }
                .onFailure { logcat { "OnnxBubbleDetector: inference failed: ${it.message}" } }
                .getOrDefault(emptyList())
        }
    }

    private fun infer(ortSession: OrtSession, bitmap: Bitmap): List<Bubble> {
        val ortEnv = env ?: return emptyList()
        val bw = bitmap.width
        val bh = bitmap.height
        val gain = min(INPUT / bw.toFloat(), INPUT / bh.toFloat())
        val nw = (bw * gain).toInt().coerceAtLeast(1)
        val nh = (bh * gain).toInt().coerceAtLeast(1)
        val padX = (INPUT - nw) / 2
        val padY = (INPUT - nh) / 2

        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val pixels = pixelBuffer
        scaled.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        if (scaled != bitmap) scaled.recycle()

        val plane = INPUT * INPUT
        val chw = inputBuffer
        java.util.Arrays.fill(chw, 114f / 255f) // letterbox grey
        for (y in 0 until nh) {
            val row = (y + padY) * INPUT
            val src = y * nw
            for (x in 0 until nw) {
                val p = pixels[src + x]
                val idx = row + padX + x
                chw[idx] = ((p shr 16) and 0xFF) / 255f
                chw[idx + plane] = ((p shr 8) and 0xFF) / 255f
                chw[idx + 2 * plane] = (p and 0xFF) / 255f
            }
        }

        OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()),
        ).use { tensor ->
            ortSession.run(mapOf(ortSession.inputNames.first() to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val out = (results[0].value as Array<Array<FloatArray>>)[0] // [features][anchors]
                return decode(out, gain, padX.toFloat(), padY.toFloat())
            }
        }
    }

    private class Det(val l: Float, val t: Float, val r: Float, val b: Float, val score: Float)

    private fun decode(out: Array<FloatArray>, gain: Float, padX: Float, padY: Float): List<Bubble> {
        val features = out.size
        val anchors = out[0].size
        val classes = features - 4

        val dets = ArrayList<Det>()
        for (a in 0 until anchors) {
            var best = out[4][a]
            for (c in 1 until classes) best = max(best, out[4 + c][a])
            if (best < CONF_THRESHOLD) continue
            val cx = out[0][a]
            val cy = out[1][a]
            val w = out[2][a]
            val h = out[3][a]
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
                if (!suppressed[j] && iou(dets[i], dets[j]) > IOU_THRESHOLD) suppressed[j] = true
            }
        }

        // 640 letterbox space -> normalised page coordinates
        val contentW = (INPUT - 2 * padX).coerceAtLeast(1f)
        val contentH = (INPUT - 2 * padY).coerceAtLeast(1f)
        return kept.map { d ->
            Bubble(
                RectF(
                    ((d.l - padX) / contentW).coerceIn(0f, 1f),
                    ((d.t - padY) / contentH).coerceIn(0f, 1f),
                    ((d.r - padX) / contentW).coerceIn(0f, 1f),
                    ((d.b - padY) / contentH).coerceIn(0f, 1f),
                ),
                d.score,
            )
        }
    }

    private fun iou(a: Det, b: Det): Float {
        val ix = max(0f, min(a.r, b.r) - max(a.l, b.l))
        val iy = max(0f, min(a.b, b.b) - max(a.t, b.t))
        val inter = ix * iy
        val union = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private companion object {
        const val ASSET = "models/bubble_detector.onnx"
        const val INPUT = 640
        const val CONF_THRESHOLD = 0.30f
        const val IOU_THRESHOLD = 0.45f
    }
}
