package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * [BubbleDetector] backed by TensorFlow Lite / LiteRT and the bundled model
 * `models/bubble_detector_v4s.tflite` (`ogkalu/comic-text-and-bubble-detector` "detector-v4-s",
 * RT-DETR-v2, Apache-2.0; conversion steps in `assets/models/NOTICE`).
 *
 * Input `[1, 640, 640, 3]` f32 RGB 0..1, the page *stretched* to 640x640 (the model was trained on
 * resized, not letterboxed, pages). Outputs, sorted by tensor name: labels `[1,300]` int64
 * (0 bubble, 1 text inside a bubble, 2 free text), boxes `[1,300,4]` x0,y0,x1,y1 normalised 0..1,
 * scores `[1,300]`. DETR emits one box per object, so no NMS is needed beyond dropping the odd
 * same-class duplicate.
 *
 * Runs on a single background thread. The GPU delegate is tried first (forced past CompatibilityList,
 * whose denylist misreports current Adreno parts — same as [SamRefiner]): it takes the convolutional
 * backbone, the bulk of the compute, while the DETR head's gathers / TopK / int64 casts stay on CPU.
 * If the delegate can't be created the whole graph runs on XNNPACK. The backend and per-page latency
 * are logged so real devices can be checked.
 */
class TfliteBubbleDetector(context: Context) : BubbleDetector {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect-tfl").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var modelBuffer: java.nio.MappedByteBuffer? = null
    private var backend = "cpu"

    private val pixels = IntArray(INPUT * INPUT)
    private val input: ByteBuffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val labels = Array(1) { LongArray(QUERIES) }
    private val boxes = Array(1) { Array(QUERIES) { FloatArray(4) } }
    private val scores = Array(1) { FloatArray(QUERIES) }

    /** Output index of labels / boxes / scores (in that order when sorted by tensor name). */
    private var outLabels = 0
    private var outBoxes = 1
    private var outScores = 2

    init {
        try {
            // The .tflite asset is stored uncompressed (noCompress "tflite" in build.gradle.kts),
            // so it can be mmap'd straight from the APK — no copy into filesDir, no full read into heap.
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
            val byName = (0 until interp.outputTensorCount).sortedBy { interp.getOutputTensor(it).name() }
            outLabels = byName[0]
            outBoxes = byName[1]
            outScores = byName[2]
            check(interp.getOutputTensor(outBoxes).shape().contentEquals(intArrayOf(1, QUERIES, 4))) {
                "unexpected boxes shape ${interp.getOutputTensor(outBoxes).shape().contentToString()}"
            }
            interpreter = interp
            logcat(LogPriority.WARN) { "TfliteBubbleDetector: ready (backend=$backend)" }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR) { "TfliteBubbleDetector: session init failed: ${t.message}" }
        }
    }

    /** GPU delegate interpreter (threads still used by the CPU-side partitions), or null if it can't be created. */
    private fun gpuInterpreter(buffer: java.nio.MappedByteBuffer): Interpreter? {
        val compat = CompatibilityList()
        val supported = compat.isDelegateSupportedOnThisDevice
        var delegate: GpuDelegate? = null
        return try {
            delegate = GpuDelegate(if (supported) compat.bestOptionsForThisDevice else GpuDelegate.Options())
            val interp = Interpreter(
                buffer,
                Interpreter.Options().apply {
                    addDelegate(delegate)
                    setNumThreads(THREADS)
                },
            )
            gpuDelegate = delegate
            backend = if (supported) "gpu" else "gpu(forced)"
            interp
        } catch (t: Throwable) {
            logcat(LogPriority.WARN) { "TfliteBubbleDetector: GPU delegate unavailable (compatList=$supported): ${t.message}" }
            runCatching { delegate?.close() }
            null
        }
    }

    override val isAvailable: Boolean get() = interpreter != null

    override suspend fun detect(bitmap: Bitmap, confThreshold: Float): List<Bubble> {
        val interp = interpreter ?: return emptyList()
        return withContext(dispatcher) {
            runCatching { infer(interp, bitmap, confThreshold) }
                .onFailure { logcat(LogPriority.ERROR) { "TfliteBubbleDetector: inference failed: ${it.message}" } }
                .getOrDefault(emptyList())
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
            modelBuffer = null
            dispatcher.close()
        } catch (t: Throwable) {
            logcat { "TfliteBubbleDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(interp: Interpreter, bitmap: Bitmap, confThreshold: Float): List<Bubble> {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled != bitmap) scaled.recycle()
        input.rewind()
        val f = input.asFloatBuffer()
        for (p in pixels) {
            f.put(((p shr 16) and 0xFF) / 255f)
            f.put(((p shr 8) and 0xFF) / 255f)
            f.put((p and 0xFF) / 255f)
        }
        input.rewind()

        val outputs = hashMapOf<Int, Any>(outLabels to labels, outBoxes to boxes, outScores to scores)
        val t0 = System.nanoTime()
        interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        logcat(LogPriority.DEBUG) { "TfliteBubbleDetector: inference ${(System.nanoTime() - t0) / 1_000_000}ms ($backend)" }

        val balloons = ArrayList<FloatArray>()
        val texts = ArrayList<FloatArray>()
        for (q in 0 until QUERIES) {
            val s = scores[0][q]
            if (s < confThreshold) continue
            val b = boxes[0][q]
            val box = floatArrayOf(
                b[0].coerceIn(0f, 1f),
                b[1].coerceIn(0f, 1f),
                b[2].coerceIn(0f, 1f),
                b[3].coerceIn(0f, 1f),
                s,
            )
            if (box[2] <= box[0] || box[3] <= box[1]) continue
            when (labels[0][q].toInt()) {
                LABEL_BUBBLE -> balloons += box
                LABEL_TEXT_IN_BUBBLE -> texts += box
            }
        }
        return assembleBubbles(dedupe(balloons), dedupe(texts))
    }

    private companion object {
        const val ASSET = "models/bubble_detector_v4s.tflite"

        /** SHA-256 of [ASSET]; a truncated/corrupt model otherwise SIGABRTs deep in TFLite. */
        const val ASSET_SHA = "43264efa950091fc0d9d1451c8ccfad0a245ee98a9fd7023007e8a8d90c30cdf"

        const val INPUT = 640
        const val QUERIES = 300
        const val THREADS = 4
        const val LABEL_BUBBLE = 0
        const val LABEL_TEXT_IN_BUBBLE = 1
    }
}

/** Same-class boxes overlapping more than this IoU are one object reported twice. */
private const val DUPLICATE_IOU = 0.7f

/** A text block belongs to a balloon when at least this share of it lies inside the balloon box. */
private const val TEXT_INSIDE_FRAC = 0.5f

/** Greedy by score: drops a box that overlaps an already kept one by more than [DUPLICATE_IOU]. Each box is `[l,t,r,b,score]`. */
internal fun dedupe(boxes: List<FloatArray>): List<FloatArray> {
    val kept = ArrayList<FloatArray>(boxes.size)
    for (b in boxes.sortedByDescending { it[4] }) {
        if (kept.none { iou(it, b) > DUPLICATE_IOU }) kept += b
    }
    return kept
}

/** Builds the [Bubble]s from balloon and text-block boxes (`[l,t,r,b,score]`, 0..1); see [textOwners]. */
internal fun assembleBubbles(balloons: List<FloatArray>, texts: List<FloatArray>): List<Bubble> {
    val owner = textOwners(balloons, texts)
    return balloons.mapIndexed { i, b ->
        val own = texts.filterIndexed { t, _ -> owner[t] == i }.map { RectF(it[0], it[1], it[2], it[3]) }
        Bubble(RectF(b[0], b[1], b[2], b[3]), b[4], own)
    }
}

/**
 * For each text block, the index of the balloon it belongs to, or -1: the single balloon containing
 * the largest share of it (at least [TEXT_INSIDE_FRAC]), the smallest balloon on a tie. Overlapping
 * neighbours therefore never share a text block.
 */
internal fun textOwners(balloons: List<FloatArray>, texts: List<FloatArray>): IntArray = IntArray(texts.size) { ti ->
    val t = texts[ti]
    val tArea = area(t).coerceAtLeast(1e-9f)
    var best = -1
    var bestFrac = 0f
    var bestArea = 0f
    for ((i, b) in balloons.withIndex()) {
        val frac = intersection(t, b) / tArea
        if (frac < TEXT_INSIDE_FRAC) continue
        val a = area(b)
        if (best < 0 || frac > bestFrac + 1e-6f || (frac >= bestFrac - 1e-6f && a < bestArea)) {
            best = i
            bestFrac = frac
            bestArea = a
        }
    }
    best
}

private fun area(b: FloatArray) = (b[2] - b[0]).coerceAtLeast(0f) * (b[3] - b[1]).coerceAtLeast(0f)

private fun intersection(a: FloatArray, b: FloatArray): Float =
    (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f) *
        (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)

private fun iou(a: FloatArray, b: FloatArray): Float {
    val i = intersection(a, b)
    val u = area(a) + area(b) - i
    return if (u <= 0f) 0f else i / u
}
