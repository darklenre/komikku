package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.Executors

/**
 * [BubbleDetector] backed by LiteRT 2.x and the bundled model
 * `models/bubble_detector_v4s.tflite` (`ogkalu/comic-text-and-bubble-detector` "detector-v4-s",
 * RT-DETR-v2, Apache-2.0; conversion steps in `assets/models/NOTICE`).
 *
 * Input `[1, 640, 640, 3]` f32 RGB 0..1, the page *stretched* to 640x640 (the model was trained on
 * resized, not letterboxed, pages). Outputs, in signature order: labels `[1,300]` int64
 * (0 bubble, 1 text inside a bubble, 2 free text), boxes `[1,300,4]` x0,y0,x1,y1 normalised 0..1,
 * scores `[1,300]`. DETR emits one box per object, so no NMS is needed beyond dropping the odd
 * same-class duplicate.
 *
 * Runs on a single background thread: split between the NPU and the CPU when an ahead-of-time compiled
 * encoder exists for this SoC (see [head]), else entirely on the CPU. The backend and per-page latency
 * are logged so real devices can be checked.
 */
class TfliteBubbleDetector(context: Context) : BubbleDetector {

    /** Inference and [close] both run on this one thread, so the model is never freed mid-run. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect-tfl").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    /** The whole detector on GPU or CPU, when the NPU split below isn't available. */
    @Volatile
    private var model: LiteRtModel? = null

    /**
     * NPU split: backbone + hybrid encoder ahead-of-time compiled for this SoC ([head], fully on the NPU) and
     * the DETR decoder ([tail], CPU), joined at the flattened encoder memory `[1,8400,256]`. The decoder's
     * gathers / TopK / int64 ops are what the NPU rejects.
     */
    @Volatile
    private var head: LiteRtModel? = null
    private var tail: LiteRtModel? = null

    private val pixels = IntArray(INPUT * INPUT)
    private val input = FloatArray(INPUT * INPUT * 3)

    init {
        try {
            openNpuSplit(context)
            if (head == null) {
                // No GPU: LiteRT 2.3's GPU accelerator changes this detector's boxes (fp16 and fp32 alike; Fold8
                // eval: 15/57 pages differ from CPU, reading order 95.1 % vs 96.3 % next), while the CPU matches the
                // reference and is no slower than the old GPU delegate was.
                model = LiteRtModel.open(context, ASSET, ASSET_SHA, IO, LiteRtModel.Backend.CPU)
            }
            logcat(LogPriority.WARN) { "TfliteBubbleDetector: ready (backend=$backend)" }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR) { "TfliteBubbleDetector: session init failed: ${t.message}" }
        }
    }

    private fun openNpuSplit(context: Context) {
        val headAsset = LiteRtModel.npuAsset(context, NPU_HEAD) ?: return
        runCatching {
            val h = LiteRtModel.open(context, null, LiteRtModel.Source(headAsset, null, LiteRtModel.Backend.NPU))
            tail = try {
                LiteRtModel.open(context, null, LiteRtModel.Source(NPU_TAIL_ASSET, null, LiteRtModel.Backend.CPU))
            } catch (t: Throwable) {
                h.close()
                throw t
            }
            head = h
        }.onFailure { logcat(LogPriority.WARN) { "TfliteBubbleDetector: NPU split unavailable: ${it.message}" } }
    }

    private val backend: String get() = if (head != null) "npu+cpu" else model?.backend ?: "none"

    override val isAvailable: Boolean get() = model != null || head != null

    override suspend fun detect(bitmap: Bitmap, confThreshold: Float): List<Bubble> {
        if (!isAvailable) return emptyList()
        return withContext(dispatcher) {
            // closed while this call was queued: give up without an (empty) result that would be cached
            if (!isAvailable) throw CancellationException("detector closed")
            runCatching { infer(bitmap, confThreshold) }
                .onFailure { logcat(LogPriority.ERROR) { "TfliteBubbleDetector: inference failed: ${it.message}" } }
                .getOrDefault(emptyList())
        }
    }

    /** Frees the model once the inference in flight (if any) is done; returns without waiting. */
    fun close() {
        try {
            executor.execute {
                runCatching { model?.close() }
                runCatching { head?.close() }
                runCatching { tail?.close() }
                model = null
                head = null
                tail = null
            }
            executor.shutdown()
        } catch (t: Throwable) {
            logcat { "TfliteBubbleDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(bitmap: Bitmap, confThreshold: Float): List<Bubble> {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled != bitmap) scaled.recycle()
        var i = 0
        for (p in pixels) {
            input[i++] = ((p shr 16) and 0xFF) / 255f
            input[i++] = ((p shr 8) and 0xFF) / 255f
            input[i++] = (p and 0xFF) / 255f
        }

        val t0 = System.nanoTime()
        val h = head
        val t = tail
        val labels: LongArray
        val boxes: FloatArray
        val scores: FloatArray
        if (h != null && t != null) {
            h.writeInput(0, input)
            h.run()
            t.writeInput(0, h.readFloat(0))
            t.run()
            boxes = t.readFloat(TAIL_BOXES)
            labels = t.readLong(TAIL_LABELS)
            scores = t.readFloat(TAIL_SCORES)
        } else {
            val m = model ?: throw CancellationException("detector closed")
            m.writeInput(0, input)
            m.run()
            labels = m.readLong(OUT_LABELS)
            boxes = m.readFloat(OUT_BOXES)
            scores = m.readFloat(OUT_SCORES)
        }
        logcat(LogPriority.DEBUG) { "TfliteBubbleDetector: inference ${(System.nanoTime() - t0) / 1_000_000}ms ($backend)" }
        check(labels.size == QUERIES && boxes.size == QUERIES * 4 && scores.size == QUERIES) {
            "unexpected output sizes ${labels.size} / ${boxes.size} / ${scores.size}"
        }

        val balloons = ArrayList<FloatArray>()
        val texts = ArrayList<FloatArray>()
        for (q in 0 until QUERIES) {
            val s = scores[q]
            if (s < confThreshold) continue
            val o = q * 4
            val box = floatArrayOf(
                boxes[o].coerceIn(0f, 1f),
                boxes[o + 1].coerceIn(0f, 1f),
                boxes[o + 2].coerceIn(0f, 1f),
                boxes[o + 3].coerceIn(0f, 1f),
                s,
            )
            if (box[2] <= box[0] || box[3] <= box[1]) continue
            when (labels[q].toInt()) {
                LABEL_BUBBLE -> balloons += box
                LABEL_TEXT_IN_BUBBLE -> texts += box
            }
        }
        return assembleBubbles(dedupe(balloons), dedupe(texts))
    }

    private companion object {
        const val ASSET = "models/bubble_detector_v4s.tflite"

        /** SHA-256 of [ASSET]; a truncated/corrupt model otherwise SIGABRTs deep in LiteRT. */
        const val ASSET_SHA = "43264efa950091fc0d9d1451c8ccfad0a245ee98a9fd7023007e8a8d90c30cdf"

        const val INPUT = 640
        const val QUERIES = 300

        val IO = LiteRtModel.Io(listOf("images"), listOf("output_0", "output_1", "output_2"))

        /** Output indices in [IO]: `output_0..2` are labels, boxes, scores. */
        const val OUT_LABELS = 0
        const val OUT_BOXES = 1
        const val OUT_SCORES = 2

        /** NPU variant of the encoder half, see [LiteRtModel.npuAsset]. */
        const val NPU_HEAD = "bubble_detector_v4s_head"

        /** Decoder half (CPU, any SoC); its outputs, without a signature, are boxes, labels, scores. */
        const val NPU_TAIL_ASSET = "models/npu/bubble_detector_v4s_tail.tflite"
        const val TAIL_BOXES = 0
        const val TAIL_LABELS = 1
        const val TAIL_SCORES = 2
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
