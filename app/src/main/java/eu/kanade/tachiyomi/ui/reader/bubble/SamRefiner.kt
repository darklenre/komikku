package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.LruCache
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
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * EdgeSAM box-prompted mask refiner (EdgeSAM is Apache-2.0), backed by two TFLite models:
 *  - `models/sam_encoder_edgesam.tflite`: `[1,3,1024,1024]` NCHW f32 -> image embedding `[1,256,64,64]`.
 *    RepViT backbone (pure convolution), so unlike MobileSAM's TinyViT the TFLite GPU delegate runs
 *    the whole encode in hardware (~50 ms) instead of falling back to XNNPACK CPU (~350 ms / ~2 s).
 *  - `models/sam_decoder_edgesam.tflite`: (embedding, box `[1,4]` px in the 1024 frame) -> mask logits
 *    `[1,1,256,256]` + IoU `[1,1]`
 *
 * Tensor shapes, dtypes and the ImageNet pixel normalisation are identical to MobileSAM, so the swap
 * is drop-in. The encoder runs once per page (its embedding is cached by page key); the decoder runs
 * per bubble box and is cheap. Interpreters are created lazily on first use and only when a SAM
 * cropping method is selected, so the ~42 MB of models cost nothing otherwise.
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

    private var encoder: Interpreter? = null
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

    /** Cached page embeddings: 256*64*64 f32 = 4 MB each. Sized to cover the reader's page preload. */
    private val embeddings = LruCache<String, FloatArray>(4)

    private val _activeEncodes = MutableStateFlow(0)

    /**
     * How many *interactive* image encodes are running (i.e. blocking an open floating cutout, not
     * the background page-preload warmup). Drives the reader's processing indicator.
     */
    val activeEncodes: StateFlow<Int> = _activeEncodes.asStateFlow()

    val isAvailable: Boolean get() = encoder != null && decoder != null

    private fun ensureInit(context: Context) {
        synchronized(initLock) {
            if (triedInit || disabledForSlowDevice) return
            triedInit = true
            runCatching {
                encoder = makeInterpreter(mmapVerifiedModel(context, ENCODER_ASSET, ENCODER_SHA)) { encBackend = it }
                decoder = makeInterpreter(mmapVerifiedModel(context, DECODER_ASSET, DECODER_SHA)) { decBackend = it }
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

    /**
     * Ensures this page's embedding is cached (runs the ~expensive encoder if needed). Safe to call
     * off the hot path. [stillWanted] is re-checked right before the encode actually starts, so a
     * page the reader has already scrolled past (its warmup superseded) costs nothing once dequeued.
     */
    fun prewarm(context: Context, pageKey: String, streamFn: () -> InputStream, stillWanted: () -> Boolean = { true }) {
        if (disabledForSlowDevice) return
        ensureInit(context)
        if (encoder == null || embeddings.get(pageKey) != null) return
        val bytes = snapshot(streamFn) ?: return
        val (pw, ph) = pageDims(bytes) ?: return
        val gain = INPUT.toFloat() / max(pw, ph)
        embedFor(
            pageKey,
            bytes,
            (pw * gain).roundToInt().coerceIn(1, INPUT),
            (ph * gain).roundToInt().coerceIn(1, INPUT),
            foreground = false,
            stillWanted = stillWanted,
        )
    }

    /**
     * Reads the page bytes once into memory. All decoding then runs off this immutable array via
     * [BitmapFactory.decodeByteArray] — never off the live stream. Decoding the same [page.stream]
     * on the background warmup thread while the page holder is also reading it corrupts the native
     * decoder's buffer (SIGSEGV in `BitmapFactory.decodeStream`), which a `runCatching` can't catch.
     */
    private fun snapshot(streamFn: () -> InputStream): ByteArray? =
        runCatching { streamFn().use { it.readBytes() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * @param foreground when true and an encode actually has to run, it is counted toward
     *   [activeEncodes] (the caller is waiting on it with an open cutout).
     * @param stillWanted gate evaluated once the encode lock is held; a false return skips the encode
     *   (used to drop superseded background warmups). Foreground callers always pass `{ true }`.
     */
    private fun embedFor(
        pageKey: String,
        pageBytes: ByteArray,
        nw: Int,
        nh: Int,
        foreground: Boolean,
        stillWanted: () -> Boolean = { true },
    ): FloatArray? {
        embeddings.get(pageKey)?.let { return it }
        synchronized(encLock) {
            embeddings.get(pageKey)?.let { return it }
            val enc = encoder ?: return null
            if (!foreground && !stillWanted()) return null
            if (foreground) _activeEncodes.update { it + 1 }
            try {
                val t0 = System.currentTimeMillis()
                val e = runEncoder(enc, pageBytes, nw, nh) ?: return null
                val dt = System.currentTimeMillis() - t0
                logcat(LogPriority.WARN) { "SamRefiner: encode ${dt}ms (encoder=$encBackend, foreground=$foreground)" }
                if (foreground) noteEncodeLatency(dt)
                embeddings.put(pageKey, e)
                return e
            } finally {
                if (foreground) _activeEncodes.update { it - 1 }
            }
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

    /**
     * Returns an [outW]x[outH] alpha mask (opaque where the bubble is) covering the [cropNorm] region
     * of the page, using SAM prompted with [boxNorm]. Both rects are normalised 0..1 of the full
     * page. Returns null if SAM is unavailable or produced nothing usable.
     */
    fun shapeMask(
        context: Context,
        pageKey: String,
        streamFn: () -> InputStream,
        boxNorm: RectF,
        cropNorm: RectF,
        outW: Int,
        outH: Int,
    ): Bitmap? {
        if (disabledForSlowDevice) return null
        ensureInit(context)
        val dec = decoder ?: return null

        val bytes = snapshot(streamFn) ?: return null
        val (pw, ph) = pageDims(bytes) ?: return null
        val gain = INPUT.toFloat() / max(pw, ph)
        val nw = (pw * gain).roundToInt().coerceIn(1, INPUT)
        val nh = (ph * gain).roundToInt().coerceIn(1, INPUT)

        val embedding = embedFor(pageKey, bytes, nw, nh, foreground = true) ?: return null

        // Padding is bottom-right anchored, so resized coords == padded (1024) coords.
        val box = floatArrayOf(
            (boxNorm.left * nw).coerceIn(0f, INPUT.toFloat()),
            (boxNorm.top * nh).coerceIn(0f, INPUT.toFloat()),
            (boxNorm.right * nw).coerceIn(0f, INPUT.toFloat()),
            (boxNorm.bottom * nh).coerceIn(0f, INPUT.toFloat()),
        )

        val embIn = ByteBuffer.allocateDirect(EMBED_LEN * 4).order(ByteOrder.nativeOrder())
        embIn.asFloatBuffer().put(embedding)
        val boxIn = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder())
        boxIn.asFloatBuffer().put(box)
        val maskOut = ByteBuffer.allocateDirect(MASK * MASK * 4).order(ByteOrder.nativeOrder())
        val iouOut = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

        val inputs = arrayOfNulls<Any>(2)
        inputs[decEmbedInIdx] = embIn
        inputs[decBoxInIdx] = boxIn
        val outputs = hashMapOf<Int, Any>(decMaskOutIdx to maskOut, decIouOutIdx to iouOut)

        @Suppress("UNCHECKED_CAST")
        val ok = synchronized(decLock) {
            runCatching { dec.runForMultipleInputsOutputs(inputs as Array<Any>, outputs) }
                .onFailure { logcat(LogPriority.ERROR) { "SamRefiner: decoder run failed: ${it.message}" } }
                .isSuccess
        }
        if (!ok) return null

        maskOut.rewind()
        val logits = FloatArray(MASK * MASK)
        maskOut.asFloatBuffer().get(logits)
        // (iouOut carries SAM's self-estimated mask quality; unused for now.)

        // Map the crop region and the prompt box onto the outW x outH grid.
        val q = 4f
        val mL = cropNorm.left * nw / q
        val mT = cropNorm.top * nh / q
        val spanX = (cropNorm.right * nw / q - mL).coerceAtLeast(1f)
        val spanY = (cropNorm.bottom * nh / q - mT).coerceAtLeast(1f)

        fun gx(nx: Float) = ((nx - cropNorm.left) / cropNorm.width().coerceAtLeast(1e-4f) * outW)
        fun gy(ny: Float) = ((ny - cropNorm.top) / cropNorm.height().coerceAtLeast(1e-4f) * outH)
        // Prompt box in grid space, expanded a little — SAM must not reach past this into neighbours.
        val bx = boxNorm
        val padX = (gx(bx.right) - gx(bx.left)) * 0.16f
        val padY = (gy(bx.bottom) - gy(bx.top)) * 0.16f
        val clampL = (gx(bx.left) - padX).toInt().coerceIn(0, outW - 1)
        val clampT = (gy(bx.top) - padY).toInt().coerceIn(0, outH - 1)
        val clampR = (gx(bx.right) + padX).toInt().coerceIn(clampL + 1, outW)
        val clampB = (gy(bx.bottom) + padY).toInt().coerceIn(clampT + 1, outH)

        // Bilinearly resample the 256-grid logits into the output grid and turn them into a soft
        // (anti-aliased) alpha ramp. Nearest + hard threshold gives a visible staircase when a small
        // bubble's ~50px mask window is blown up across the screen. The ramp is kept tight (~1.4
        // logit units): EdgeSAM's boundary is softer than MobileSAM's, and a wide ramp there smears
        // the edge and lets the bubble bleed toward its neighbour.
        val alpha = FloatArray(outW * outH)
        val on = BooleanArray(outW * outH)
        for (oy in clampT until clampB) {
            val fy = (mT + (oy + 0.5f) / outH * spanY - 0.5f).coerceIn(0f, MASK - 1.001f)
            val y0 = fy.toInt()
            val wy = fy - y0
            for (ox in clampL until clampR) {
                val fx = (mL + (ox + 0.5f) / outW * spanX - 0.5f).coerceIn(0f, MASK - 1.001f)
                val x0 = fx.toInt()
                val wx = fx - x0
                val lg = (logits[y0 * MASK + x0] * (1 - wx) + logits[y0 * MASK + x0 + 1] * wx) * (1 - wy) +
                    (logits[(y0 + 1) * MASK + x0] * (1 - wx) + logits[(y0 + 1) * MASK + x0 + 1] * wx) * wy
                val a = ((lg + 0.7f) / 1.4f).coerceIn(0f, 1f)
                alpha[oy * outW + ox] = a
                if (a >= 0.5f) on[oy * outW + ox] = true
            }
        }

        // Morphologically open then close the hard mask: opening severs thin drips / tail leak that
        // EdgeSAM feathers off the bottom of a balloon, closing fills the matching bites out of the
        // boundary. Radius scales with the mask window so it's a few px either way.
        val morph = max(1, min(clampR - clampL, clampB - clampT) / 44)
        val cleaned = closeBool(
            openBool(on, outW, morph, clampL, clampT, clampR, clampB),
            outW,
            morph,
            clampL,
            clampT,
            clampR,
            clampB,
        )

        // Keep only the blob under the box centre — kills leakage into an adjacent bubble/caption.
        val seedX = gx((bx.left + bx.right) / 2f).toInt().coerceIn(clampL, clampR - 1)
        val seedY = gy((bx.top + bx.bottom) / 2f).toInt().coerceIn(clampT, clampB - 1)
        val kept = largestComponentNear(cleaned, outW, seedX, seedY, clampL, clampT, clampR, clampB)
        // Dilate the gate so the blur below has room to feather outward past the hard component edge.
        val gate = dilateBool(kept, outW, 3, clampL, clampT, clampR, clampB)

        // Zero the alpha outside the kept blob, then box-blur it: a genuine feather that doesn't
        // depend on how steep the logits are at the boundary.
        for (i in alpha.indices) if (!gate[i]) alpha[i] = 0f
        val soft = boxBlurAlpha(alpha, outW, outH, 3, clampL, clampT, clampR, clampB)

        val out = IntArray(outW * outH)
        var opaque = 0
        for (i in out.indices) {
            val ai = (soft[i] * 255f).toInt().coerceIn(0, 255)
            if (ai >= 128) opaque++
            out[i] = (ai shl 24) or 0x00FFFFFF
        }
        val total = outW * outH
        if (opaque < total * 0.02f || opaque > total * 0.98f) return null
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, outW, 0, 0, outW, outH)
        }
    }

    /** Separable box blur of an alpha plane, bounded to (l,t)-(r,b) expanded by [radius]. */
    private fun boxBlurAlpha(src: FloatArray, w: Int, h: Int, radius: Int, l: Int, t: Int, r: Int, b: Int): FloatArray {
        val x0 = (l - radius).coerceAtLeast(0)
        val y0 = (t - radius).coerceAtLeast(0)
        val x1 = (r + radius).coerceAtMost(w)
        val y1 = (b + radius).coerceAtMost(h)
        val tmp = src.copyOf()
        val dst = src.copyOf()
        val norm = 1f / (2 * radius + 1)
        // Horizontal
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                var sum = 0f
                for (k in -radius..radius) sum += src[row + (x + k).coerceIn(x0, x1 - 1)]
                tmp[row + x] = sum * norm
            }
        }
        // Vertical
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                var sum = 0f
                for (k in -radius..radius) sum += tmp[(y + k).coerceIn(y0, y1 - 1) * w + x]
                dst[y * w + x] = sum * norm
            }
        }
        return dst
    }

    /** [iterations] rounds of 4-neighbour dilation, bounded to the (l,t)-(r,b) box. [w] is the row stride. */
    private fun dilateBool(
        src: BooleanArray,
        w: Int,
        iterations: Int,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): BooleanArray {
        var cur = src
        repeat(iterations) {
            val nxt = cur.copyOf()
            for (y in t until b) {
                for (x in l until r) {
                    val i = y * w + x
                    if (cur[i]) continue
                    if ((x > l && cur[i - 1]) || (x < r - 1 && cur[i + 1]) ||
                        (y > t && cur[i - w]) || (y < b - 1 && cur[i + w])
                    ) {
                        nxt[i] = true
                    }
                }
            }
            cur = nxt
        }
        return cur
    }

    /**
     * [iterations] rounds of 4-neighbour erosion, bounded to the (l,t)-(r,b) box. Neighbours outside
     * the box count as set, so only true mask boundaries erode, not the clamp edge. [w] is the row stride.
     */
    private fun erodeBool(
        src: BooleanArray,
        w: Int,
        iterations: Int,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): BooleanArray {
        var cur = src
        repeat(iterations) {
            val nxt = cur.copyOf()
            for (y in t until b) {
                for (x in l until r) {
                    val i = y * w + x
                    if (!cur[i]) continue
                    val left = x <= l || cur[i - 1]
                    val right = x >= r - 1 || cur[i + 1]
                    val up = y <= t || cur[i - w]
                    val down = y >= b - 1 || cur[i + w]
                    if (!(left && right && up && down)) nxt[i] = false
                }
            }
            cur = nxt
        }
        return cur
    }

    /** Erode then dilate: removes protrusions / bridges thinner than 2*[iterations]. */
    private fun openBool(src: BooleanArray, w: Int, iterations: Int, l: Int, t: Int, r: Int, b: Int): BooleanArray =
        dilateBool(erodeBool(src, w, iterations, l, t, r, b), w, iterations, l, t, r, b)

    /** Dilate then erode: fills notches / gaps thinner than 2*[iterations]. */
    private fun closeBool(src: BooleanArray, w: Int, iterations: Int, l: Int, t: Int, r: Int, b: Int): BooleanArray =
        erodeBool(dilateBool(src, w, iterations, l, t, r, b), w, iterations, l, t, r, b)

    /**
     * BFS the opaque component that contains (or is nearest to) the seed, within the (l,t)-(r,b) box.
     * [w] is the row stride; the returned mask is `w * b` long (indices past row `b` stay false).
     */
    private fun largestComponentNear(
        on: BooleanArray,
        w: Int,
        seedX: Int,
        seedY: Int,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): BooleanArray {
        val out = BooleanArray(on.size)
        var sx = seedX
        var sy = seedY
        if (!on[sy * w + sx]) {
            // Spiral out a little to find an opaque pixel to start from.
            var found = false
            var rad = 1
            while (!found && rad <= (r - l) + (b - t)) {
                var yy = (sy - rad).coerceAtLeast(t)
                while (yy <= (sy + rad).coerceAtMost(b - 1) && !found) {
                    var xx = (sx - rad).coerceAtLeast(l)
                    while (xx <= (sx + rad).coerceAtMost(r - 1)) {
                        if (on[yy * w + xx]) {
                            sx = xx
                            sy = yy
                            found = true
                            break
                        }
                        xx++
                    }
                    yy++
                }
                rad++
            }
            if (!found) return out
        }
        val q = ArrayDeque<Int>()
        val start = sy * w + sx
        out[start] = true
        q.addLast(start)
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val x = i % w
            val y = i / w
            val neigh = intArrayOf(
                if (x > l) i - 1 else -1,
                if (x < r - 1) i + 1 else -1,
                if (y > t) i - w else -1,
                if (y < b - 1) i + w else -1,
            )
            for (n in neigh) {
                if (n < 0 || out[n] || !on[n]) continue
                out[n] = true
                q.addLast(n)
            }
        }
        return out
    }

    fun evictPage(pageKey: String) = synchronized(encLock) { embeddings.remove(pageKey) }

    fun close() {
        synchronized(initLock) {
            runCatching { encoder?.close() }
            runCatching { decoder?.close() }
            delegates.forEach { runCatching { it.close() } }
            delegates.clear()
            encoder = null
            decoder = null
            embeddings.evictAll()
            triedInit = false
        }
    }

    private fun runEncoder(enc: Interpreter, pageBytes: ByteArray, nw: Int, nh: Int): FloatArray? {
        val page = decodePage(pageBytes) ?: return null
        val resized = Bitmap.createScaledBitmap(page, nw, nh, true)
        if (resized != page) page.recycle()
        val px = IntArray(nw * nh)
        resized.getPixels(px, 0, nw, 0, 0, nw, nh)
        resized.recycle()

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

    private fun pageDims(pageBytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    private fun decodePage(pageBytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / (sample * 2) >= INPUT) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size, opts) }.getOrNull()
    }

    /**
     * Tries the GPU delegate, then XNNPACK CPU. EdgeSAM's RepViT encoder is pure convolution, so the
     * GPU delegate accepts the whole graph (MobileSAM's TinyViT did not — it fell back to XNNPACK in
     * 200+ tiny partitions). NNAPI is still not attempted: it was rejected the same way on the target
     * Qualcomm device and is slower than plain XNNPACK when it partitions.
     * [onBackend] is called with the chosen backend's name.
     */
    private fun makeInterpreter(buffer: MappedByteBuffer, onBackend: (String) -> Unit): Interpreter {
        val compat = CompatibilityList()
        // CompatibilityList's bundled denylist reports "unsupported" on some current Adreno parts it
        // simply doesn't know yet; EdgeSAM's RepViT graph is all conv, so we attempt the GPU delegate
        // regardless and fall back to XNNPACK if interpreter creation actually throws.
        val gpuSupported = compat.isDelegateSupportedOnThisDevice
        run {
            try {
                val options = if (gpuSupported) compat.bestOptionsForThisDevice else GpuDelegate.Options()
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
