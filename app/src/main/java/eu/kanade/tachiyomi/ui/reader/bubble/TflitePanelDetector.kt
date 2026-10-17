package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Panel detector backed by the bundled `models/panel_detector_v1.tflite`: a YOLOX-nano (Apache-2.0)
 * trained for Komikku on a single "panel" class (provenance in `assets/models/NOTICE`).
 *
 * Input `[1, 640, 640, 3]` f32 **BGR 0..255**, the page scaled to fit and placed at the top-left of a
 * grey (114) square, as in YOLOX training. Output `[1, 8400, 6]`: per anchor cx, cy, w, h in input
 * pixels, objectness, class score. Boxes with `objectness * class` >= [CONF] go through class-agnostic
 * NMS ([NMS_IOU]).
 *
 * Same threading and delegate policy as [TfliteBubbleDetector]: one background thread, GPU delegate
 * forced past CompatibilityList, XNNPACK if the delegate can't be created.
 */
class TflitePanelDetector(context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "panel-detect-tfl").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var modelBuffer: java.nio.MappedByteBuffer? = null
    private var backend = "cpu"

    private val pixels = IntArray(INPUT * INPUT)
    private val input: ByteBuffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(ANCHORS) { FloatArray(6) } }

    init {
        try {
            val buffer = mmapVerifiedModel(context, ASSET, ASSET_SHA)
            modelBuffer = buffer
            val interp = gpuInterpreter(buffer) ?: run {
                buffer.rewind()
                backend = "cpu"
                Interpreter(
                    buffer,
                    Interpreter.Options().apply {
                        setUseXNNPACK(true)
                        setNumThreads(THREADS)
                    },
                )
            }
            check(interp.getOutputTensor(0).shape().contentEquals(intArrayOf(1, ANCHORS, 6))) {
                "unexpected output shape ${interp.getOutputTensor(0).shape().contentToString()}"
            }
            interpreter = interp
            logcat(LogPriority.WARN) { "TflitePanelDetector: ready (backend=$backend)" }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR) { "TflitePanelDetector: session init failed: ${t.message}" }
        }
    }

    private fun gpuInterpreter(buffer: java.nio.MappedByteBuffer): Interpreter? {
        var delegate: GpuDelegate? = null
        return try {
            delegate = GpuDelegate(GpuDelegate.Options())
            val interp = Interpreter(
                buffer,
                Interpreter.Options().apply {
                    addDelegate(delegate)
                    setNumThreads(THREADS)
                },
            )
            gpuDelegate = delegate
            backend = "gpu"
            interp
        } catch (t: Throwable) {
            logcat(LogPriority.WARN) { "TflitePanelDetector: GPU delegate unavailable: ${t.message}" }
            runCatching { delegate?.close() }
            null
        }
    }

    val isAvailable: Boolean get() = interpreter != null

    /** Panel boxes `[l, t, r, b]` normalised 0..1 of [bitmap], or null when the model can't run. */
    suspend fun detect(bitmap: Bitmap): List<FloatArray>? {
        if (interpreter == null) return null
        return withContext(dispatcher) {
            val interp = interpreter ?: throw CancellationException("panel detector closed")
            runCatching { infer(interp, bitmap) }
                .onFailure { logcat(LogPriority.ERROR) { "TflitePanelDetector: inference failed: ${it.message}" } }
                .getOrNull()
        }
    }

    /** Frees the interpreter once the inference in flight (if any) is done; returns without waiting. */
    fun close() {
        try {
            executor.execute {
                runCatching { interpreter?.close() }
                interpreter = null
                runCatching { gpuDelegate?.close() }
                gpuDelegate = null
                modelBuffer = null
            }
            executor.shutdown()
        } catch (t: Throwable) {
            logcat { "TflitePanelDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(interp: Interpreter, bitmap: Bitmap): List<FloatArray> {
        val w = bitmap.width
        val h = bitmap.height
        val r = min(INPUT.toFloat() / w, INPUT.toFloat() / h)
        val nw = max(1, (w * r).roundToInt()).coerceAtMost(INPUT)
        val nh = max(1, (h * r).roundToInt()).coerceAtMost(INPUT)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        scaled.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        if (scaled != bitmap) scaled.recycle()
        input.rewind()
        val f = input.asFloatBuffer()
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                if (x < nw && y < nh) {
                    val p = pixels[y * nw + x]
                    f.put((p and 0xFF).toFloat())
                    f.put(((p shr 8) and 0xFF).toFloat())
                    f.put(((p shr 16) and 0xFF).toFloat())
                } else {
                    f.put(PAD)
                    f.put(PAD)
                    f.put(PAD)
                }
            }
        }
        input.rewind()

        val t0 = System.nanoTime()
        interp.run(input, output)
        logcat(LogPriority.DEBUG) { "TflitePanelDetector: inference ${(System.nanoTime() - t0) / 1_000_000}ms ($backend)" }

        val boxes = ArrayList<FloatArray>()
        for (a in output[0]) {
            val score = a[4] * a[5]
            if (score < CONF) continue
            val l = ((a[0] - a[2] / 2) / r / w).coerceIn(0f, 1f)
            val t = ((a[1] - a[3] / 2) / r / h).coerceIn(0f, 1f)
            val rr = ((a[0] + a[2] / 2) / r / w).coerceIn(0f, 1f)
            val b = ((a[1] + a[3] / 2) / r / h).coerceIn(0f, 1f)
            if (rr > l && b > t) boxes += floatArrayOf(l, t, rr, b, score)
        }
        return nms(boxes, NMS_IOU)
    }

    private companion object {
        const val ASSET = "models/panel_detector_v1.tflite"

        /** SHA-256 of [ASSET]; a truncated/corrupt model otherwise SIGABRTs deep in TFLite. */
        const val ASSET_SHA = "3a58b3fcbf29eaaee3476d7edda0c691d891a9921ffe972b5479906a380f753b"

        const val INPUT = 640
        const val ANCHORS = 8400
        const val THREADS = 4
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
