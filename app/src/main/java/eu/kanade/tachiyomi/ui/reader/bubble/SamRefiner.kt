package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * EdgeSAM box-prompted balloon segmentation (EdgeSAM is Apache-2.0), backed by two TFLite models:
 *  - `models/sam_encoder_edgesam.tflite`: `[1,3,1024,1024]` NCHW f32 -> image embedding `[1,256,64,64]`.
 *    RepViT backbone (pure convolution), so the TFLite GPU delegate runs the whole encode in hardware
 *    (~50 ms) instead of XNNPACK CPU (~350 ms / ~2 s).
 *  - `models/sam_decoder_edgesam.tflite`: (embedding, box `[1,4]` px in the 1024 frame) -> mask logits
 *    `[1,1,256,256]` + IoU `[1,1]`
 *
 * It runs on a crop around *one* balloon, not on the page: the 256² logit grid then spans a few hundred
 * page px (~1-2 px per cell) instead of the whole page (~12 px per cell), which is what made v1 edges
 * grainy. One encode per balloon; callers cache the resulting mask. Interpreters are created lazily
 * on first use and only when the SAM cutout method is selected.
 */
object SamRefiner {

    private const val ENCODER_ASSET = "models/sam_encoder_edgesam.tflite"
    private const val DECODER_ASSET = "models/sam_decoder_edgesam.tflite"

    /** SHA-256 of the bundled assets — a truncated/corrupt model otherwise SIGABRTs deep in TFLite. */
    private const val ENCODER_SHA = "564f55425f04e5f5c8c7853fc3df8ca108a002b6de0deae328519fe02e03c23f"
    private const val DECODER_SHA = "8bbb8aafbbe447ca67b7235e115d6bdf5360afae27b5a43425c3d204d885807c"

    private const val INPUT = 1024
    private const val EMBED_LEN = 256 * 64 * 64
    private const val MASK = 256

    /** A mask covering less / more than this share of the crop is not a balloon (-> rounded-rect fallback). */
    private const val MIN_COVER = 0.02f
    private const val MAX_COVER = 0.98f

    /** Ink-snap reach as a fraction of the crop short side (see [inkSnap]). */
    private const val SNAP_FRAC = 0.012f

    /** Mask smoothing radius = crop short side / this, 1..3 px (see [smoothMask]). */
    private const val SMOOTH_DIV = 150

    /**
     * If the first few interactive encodes are all slower than this, the device can't run EdgeSAM
     * at a usable latency: [disabledForSlowDevice] latches and the cutout silently falls back to the
     * rounded rectangle for the rest of the process (a restart re-evaluates).
     */
    private const val SLOW_ENCODE_MS = 9_000L
    private const val SLOW_ENCODE_STREAK_TO_DISABLE = 2

    // SAM pixel normalisation (ImageNet-style, applied in the 0..255 domain).
    private val PIX_MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val PIX_STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    private val initLock = Any()
    private val encLock = Any()
    private val decLock = Any()

    @Volatile
    private var encoder: Interpreter? = null

    @Volatile
    private var decoder: Interpreter? = null
    private val delegates = mutableListOf<Closeable>()
    private var encNhwc = false
    private var decEmbedInIdx = 0
    private var decBoxInIdx = 1
    private var decMaskOutIdx = 0
    private var decIouOutIdx = 1
    private var triedInit = false
    private var encBackend = "cpu"
    private var decBackend = "cpu"

    private var slowEncodeStreak = 0

    /**
     * Latched once the device has proven too slow for SAM (see [SLOW_ENCODE_MS]). Survives [close]
     * so a low-memory trim doesn't hand a slow device another round of 9 s encodes; cleared only by
     * a process restart.
     */
    @Volatile
    var disabledForSlowDevice = false
        private set

    private val _activeEncodes = MutableStateFlow(0)

    /**
     * How many *interactive* encodes are running (i.e. blocking an open floating cutout, not the
     * background prewarm). Drives the reader's processing indicator.
     */
    val activeEncodes: StateFlow<Int> = _activeEncodes.asStateFlow()

    val isAvailable: Boolean get() = encoder != null && decoder != null

    private fun ensureInit(context: Context) {
        synchronized(initLock) {
            if (triedInit || disabledForSlowDevice) return
            triedInit = true
            runCatching {
                encoder = makeInterpreter(mmapVerifiedModel(context, ENCODER_ASSET, ENCODER_SHA)) { encBackend = it }
                // Decoder stays on CPU: it is cheap, and on Adreno (LiteRT 1.4.2) the GPU delegate accepts it
                // but returns garbage logits, so every mask failed the plausibility check.
                decoder = makeInterpreter(mmapVerifiedModel(context, DECODER_ASSET, DECODER_SHA), allowGpu = false) { decBackend = it }
                encoder?.let { e ->
                    val s = e.getInputTensor(0).shape()
                    encNhwc = s.size == 4 && s[3] == 3
                }
                decoder?.let { d ->
                    for (i in 0 until d.inputTensorCount) {
                        if (d.getInputTensor(i).shape().size >= 4) decEmbedInIdx = i else decBoxInIdx = i
                    }
                    for (i in 0 until d.outputTensorCount) {
                        if (d.getOutputTensor(i).shape().size >= 4) decMaskOutIdx = i else decIouOutIdx = i
                    }
                }
                if (isAvailable) {
                    logcat(LogPriority.WARN) { "SamRefiner: EdgeSAM ready (encoder=$encBackend, decoder=$decBackend)" }
                }
            }.onFailure {
                logcat(LogPriority.ERROR) { "SamRefiner: init failed: ${it.message}" }
                // Release what loaded, but keep triedInit latched so a hard failure (missing/corrupt
                // model) isn't retried on every call.
                runCatching { encoder?.close() }
                runCatching { decoder?.close() }
                delegates.forEach { d -> runCatching { d.close() } }
                delegates.clear()
                encoder = null
                decoder = null
            }
        }
    }

    /** True when the encoder runs on the GPU delegate (cheap enough to precompute a whole page). */
    val isFast: Boolean get() = encoder != null && encBackend.startsWith("gpu")

    /** Loads the models if needed; false when SAM can't run (missing models, slow device). */
    fun prepare(context: Context): Boolean {
        if (disabledForSlowDevice) return false
        ensureInit(context)
        return isAvailable
    }

    /**
     * Segments the balloon prompted by [box] (px of [crop]) and returns its alpha (0..1, row-major,
     * [crop]'s size): glyph holes filled, lightly smoothed, only the largest blob kept, grown over the
     * balloon's ink outline ([inkSnap]), a 1 px anti-aliased edge. Null
     * if SAM is unavailable or the mask is implausible (empty / the whole crop).
     *
     * @param foreground the caller is waiting on it with an open cutout (counted in [activeEncodes]
     *   and in the slow-device latch); background prewarm passes false.
     */
    fun cropMask(context: Context, crop: Bitmap, box: RectF, foreground: Boolean): FloatArray? {
        if (!prepare(context)) return null
        val w = crop.width
        val h = crop.height
        if (w < 8 || h < 8) return null
        val gain = INPUT.toFloat() / max(w, h)
        val nw = (w * gain).roundToInt().coerceIn(1, INPUT)
        val nh = (h * gain).roundToInt().coerceIn(1, INPUT)

        // The interpreters are read under their lock: [close] takes the same locks, so it can't free one mid-run.
        val embedding = synchronized(encLock) {
            val enc = encoder ?: return null
            if (foreground) _activeEncodes.update { it + 1 }
            try {
                val t0 = System.currentTimeMillis()
                val e = runEncoder(enc, crop, nw, nh)
                val dt = System.currentTimeMillis() - t0
                if (foreground) noteEncodeLatency(dt)
                e
            } finally {
                if (foreground) _activeEncodes.update { it - 1 }
            }
        } ?: return null

        val embIn = ByteBuffer.allocateDirect(EMBED_LEN * 4).order(ByteOrder.nativeOrder())
        embIn.asFloatBuffer().put(embedding)
        val boxIn = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder())
        boxIn.asFloatBuffer().put(
            floatArrayOf(
                (box.left * gain).coerceIn(0f, INPUT.toFloat()),
                (box.top * gain).coerceIn(0f, INPUT.toFloat()),
                (box.right * gain).coerceIn(0f, INPUT.toFloat()),
                (box.bottom * gain).coerceIn(0f, INPUT.toFloat()),
            ),
        )
        val maskOut = ByteBuffer.allocateDirect(MASK * MASK * 4).order(ByteOrder.nativeOrder())
        val iouOut = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        val inputs = arrayOfNulls<Any>(2)
        inputs[decEmbedInIdx] = embIn
        inputs[decBoxInIdx] = boxIn
        val outputs = hashMapOf<Int, Any>(decMaskOutIdx to maskOut, decIouOutIdx to iouOut)

        @Suppress("UNCHECKED_CAST")
        val ok = synchronized(decLock) {
            val dec = decoder ?: return null
            runCatching { dec.runForMultipleInputsOutputs(inputs as Array<Any>, outputs) }
                .onFailure { logcat(LogPriority.ERROR) { "SamRefiner: decoder run failed: ${it.message}" } }
                .isSuccess
        }
        if (!ok) return null
        maskOut.rewind()
        val logits = FloatArray(MASK * MASK)
        maskOut.asFloatBuffer().get(logits)

        // Logits cover the padded 1024 frame at 1/4 resolution; the crop occupies its top-left nw x nh.
        val inside = BooleanArray(w * h)
        val cell = gain / (INPUT / MASK) // crop px -> logit cells
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * cell - 0.5f).coerceIn(0f, MASK - 1.001f)
            val y0 = fy.toInt()
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * cell - 0.5f).coerceIn(0f, MASK - 1.001f)
                val x0 = fx.toInt()
                val wx = fx - x0
                val i = y0 * MASK + x0
                val lg = (logits[i] * (1 - wx) + logits[i + 1] * wx) * (1 - wy) +
                    (logits[i + MASK] * (1 - wx) + logits[i + MASK + 1] * wx) * wy
                inside[y * w + x] = lg > 0f
            }
        }
        val smoothR = (min(w, h) / SMOOTH_DIV).coerceIn(1, 3)
        val blob = largestBlob(smoothMask(fillHoles(inside, w, h), w, h, smoothR), w, h)
        val shape = inkSnap(blob, lumaOf(crop), w, h, (min(w, h) * SNAP_FRAC).roundToInt().coerceAtLeast(2))
        var n = 0
        for (v in shape) if (v) n++
        if (n < w * h * MIN_COVER || n > w * h * MAX_COVER) return null
        return antialias(shape, w, h)
    }

    /** Rec. 601 luma (0..255) of every pixel of [bmp]. */
    private fun lumaOf(bmp: Bitmap): FloatArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return FloatArray(px.size) {
            val p = px[it]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
    }

    /** Latches [disabledForSlowDevice] after a run of interactive encodes all slower than [SLOW_ENCODE_MS]. */
    private fun noteEncodeLatency(ms: Long) {
        if (ms >= SLOW_ENCODE_MS) {
            if (++slowEncodeStreak >= SLOW_ENCODE_STREAK_TO_DISABLE) {
                disabledForSlowDevice = true
                logcat(LogPriority.WARN) {
                    "SamRefiner: encode ~${ms}ms (backend=$encBackend) x$slowEncodeStreak — disabling SAM refinement for this session"
                }
            }
        } else {
            slowEncodeStreak = 0
        }
    }

    /** Frees the models, waiting for an encode or decode in flight (it would otherwise run on freed memory). */
    fun close() {
        synchronized(initLock) {
            synchronized(encLock) {
                synchronized(decLock) {
                    runCatching { encoder?.close() }
                    runCatching { decoder?.close() }
                    delegates.forEach { runCatching { it.close() } }
                    delegates.clear()
                    encoder = null
                    decoder = null
                    triedInit = false
                }
            }
        }
    }

    private fun runEncoder(enc: Interpreter, crop: Bitmap, nw: Int, nh: Int): FloatArray? {
        val resized = Bitmap.createScaledBitmap(crop, nw, nh, true)
        val px = IntArray(nw * nh)
        resized.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (resized !== crop) resized.recycle()

        val plane = INPUT * INPUT
        val tmp = FloatArray(3 * plane) // zeros outside the resized area (SAM pads with 0 post-norm)
        for (y in 0 until nh) {
            val src = y * nw
            for (x in 0 until nw) {
                val p = px[src + x]
                val r = (((p shr 16) and 0xFF) - PIX_MEAN[0]) / PIX_STD[0]
                val g = (((p shr 8) and 0xFF) - PIX_MEAN[1]) / PIX_STD[1]
                val b = ((p and 0xFF) - PIX_MEAN[2]) / PIX_STD[2]
                if (encNhwc) {
                    val i = (y * INPUT + x) * 3
                    tmp[i] = r
                    tmp[i + 1] = g
                    tmp[i + 2] = b
                } else {
                    val i = y * INPUT + x
                    tmp[i] = r
                    tmp[i + plane] = g
                    tmp[i + 2 * plane] = b
                }
            }
        }

        val inBuf = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder())
        inBuf.asFloatBuffer().put(tmp)
        val outBuf = ByteBuffer.allocateDirect(EMBED_LEN * 4).order(ByteOrder.nativeOrder())
        return runCatching {
            enc.run(inBuf, outBuf)
            outBuf.rewind()
            FloatArray(EMBED_LEN).also { outBuf.asFloatBuffer().get(it) }
        }.onFailure {
            logcat(LogPriority.ERROR) { "SamRefiner: encoder run failed: ${it.message}" }
        }.getOrNull()
    }

    /**
     * Tries the GPU delegate, then XNNPACK CPU. EdgeSAM's RepViT encoder is pure convolution, so the
     * GPU delegate accepts the whole graph (MobileSAM's TinyViT did not — it fell back to XNNPACK in
     * 200+ tiny partitions). NNAPI is still not attempted: it was rejected the same way on the target
     * Qualcomm device and is slower than plain XNNPACK when it partitions.
     * [onBackend] is called with the chosen backend's name.
     */
    private fun makeInterpreter(buffer: MappedByteBuffer, allowGpu: Boolean = true, onBackend: (String) -> Unit): Interpreter {
        if (!allowGpu) {
            onBackend("cpu")
            return Interpreter(
                buffer,
                Interpreter.Options().apply {
                    setUseXNNPACK(true)
                    setNumThreads(4)
                },
            )
        }
        val compat = CompatibilityList()
        // CompatibilityList's bundled denylist reports "unsupported" on some current Adreno parts it
        // simply doesn't know yet; EdgeSAM's RepViT graph is all conv, so we attempt the GPU delegate
        // regardless and fall back to XNNPACK if interpreter creation actually throws.
        val gpuSupported = compat.isDelegateSupportedOnThisDevice
        run {
            try {
                // Full fp32 on the GPU: with the default fp16 the balloon edges drift a few px (eval set,
                // Fold8: boundary F 0.78 vs 0.86 on CPU).
                val options = (if (gpuSupported) compat.bestOptionsForThisDevice else GpuDelegate.Options())
                    .setPrecisionLossAllowed(false)
                val delegate = GpuDelegate(options)
                val itp = Interpreter(buffer, Interpreter.Options().apply { addDelegate(delegate) })
                delegates += delegate
                onBackend(if (gpuSupported) "gpu" else "gpu(forced)")
                return itp
            } catch (t: Throwable) {
                logcat(LogPriority.WARN) { "SamRefiner: GPU delegate init failed (compatList=$gpuSupported): ${t.message}" }
            }
        }
        buffer.rewind()
        onBackend("cpu")
        return Interpreter(
            buffer,
            Interpreter.Options().apply {
                setUseXNNPACK(true)
                setNumThreads(4)
            },
        )
    }
}
