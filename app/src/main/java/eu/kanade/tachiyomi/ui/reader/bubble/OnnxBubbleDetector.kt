package eu.kanade.tachiyomi.ui.reader.bubble

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * [BubbleDetector] backed by ONNX Runtime and the bundled model
 * `assets/models/bubble_detector_ogkalu.onnx` (`ogkalu/comic-speech-bubble-detector-yolov8m`, Apache-2.0):
 * YOLOv8-detect, 1 class, 640 input, output `[1, 5, 8400]` = [cx, cy, w, h, score] in 640-space.
 *
 * Inference runs on a single background thread. Postprocess: threshold -> NMS -> map boxes from the
 * letterboxed 640 space back to normalised page coordinates.
 */
class OnnxBubbleDetector(context: Context) : BubbleDetector {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect-onnx").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val env: OrtEnvironment?
    private val session: OrtSession?

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
            val t0 = System.currentTimeMillis()
            val result = runCatching { infer(ortSession, bitmap) }
                .onFailure { logcat(LogPriority.ERROR) { "OnnxBubbleDetector: inference failed: ${it.message}" } }
                .getOrDefault(emptyList())
            val dt = System.currentTimeMillis() - t0
            logcat(LogPriority.INFO) { "OnnxBubbleDetector: page detected in ${dt}ms (${result.size} bubbles, ${bitmap.width}x${bitmap.height})" }
            result
        }
    }

    fun close() {
        try {
            session?.close()
            dispatcher.close()
        } catch (t: Throwable) {
            logcat { "OnnxBubbleDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(ortSession: OrtSession, bitmap: Bitmap): List<Bubble> {
        val ortEnv = env ?: return emptyList()
        val lb = letterboxOf(bitmap.width, bitmap.height, INPUT)

        val scaled = Bitmap.createScaledBitmap(bitmap, lb.nw, lb.nh, true)
        val pixels = pixelBuffer
        scaled.getPixels(pixels, 0, lb.nw, 0, 0, lb.nw, lb.nh)
        if (scaled != bitmap) scaled.recycle()

        val plane = INPUT * INPUT
        val chw = inputBuffer
        java.util.Arrays.fill(chw, 114f / 255f) // letterbox grey
        for (y in 0 until lb.nh) {
            val row = (y + lb.padY) * INPUT
            val src = y * lb.nw
            for (x in 0 until lb.nw) {
                val p = pixels[src + x]
                val idx = row + lb.padX + x
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
                return decodeDetections(
                    featureMajor = out,
                    lb = lb,
                    coordsIn640Space = true,
                    confThreshold = CONF_THRESHOLD,
                    iouThreshold = IOU_THRESHOLD,
                )
            }
        }
    }

    private companion object {
        const val ASSET = "models/bubble_detector_ogkalu.onnx"
        const val INPUT = 640
        const val CONF_THRESHOLD = 0.30f
        const val IOU_THRESHOLD = 0.45f
    }
}
