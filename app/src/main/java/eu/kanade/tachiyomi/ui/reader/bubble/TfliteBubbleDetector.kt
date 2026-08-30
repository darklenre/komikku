package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * [BubbleDetector] backed by TensorFlow Lite / LiteRT and the bundled model
 * `models/bubble_detector_ogkalu.tflite` (`ogkalu/comic-speech-bubble-detector-yolov8m`, Apache-2.0):
 * YOLOv8-detect, 640 input, int8. Output `[1, 4+nc, anchors]` (or transposed).
 *
 * The input layout (`[1,3,S,S]` vs `[1,S,S,3]`), input size, feature count and transpose are all
 * auto-detected at init. Inference runs on a single background thread with GPU acceleration if
 * supported, falling back to XNNPACK CPU.
 */
class TfliteBubbleDetector(context: Context) : BubbleDetector {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect-tfl").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var modelBuffer: java.nio.MappedByteBuffer? = null

    private var inputSize = INPUT
    private lateinit var pixelBuffer: IntArray
    private lateinit var chwBuffer: FloatArray
    private lateinit var inputBuffer: FloatBuffer
    private lateinit var inputByteBuffer: ByteBuffer

    private var isInputNhwc = false
    private var numFeatures = 5
    private var numAnchors = 8400
    private var isOutputTransposed = false
    private var outputArray3D: Array<Array<FloatArray>>? = null

    init {
        try {
            // The .tflite asset is stored uncompressed (noCompress "tflite" in build.gradle.kts),
            // so it can be mmap'd straight from the APK — no copy into filesDir, no full read into heap.
            val buffer = mmapVerifiedModel(context, ASSET, ASSET_SHA)
            modelBuffer = buffer
            val compatList = CompatibilityList()
            var createdWithGpu = false

            if (compatList.isDelegateSupportedOnThisDevice) {
                try {
                    val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    interpreter = Interpreter(buffer, Interpreter.Options().apply { addDelegate(delegate) })
                    gpuDelegate = delegate
                    createdWithGpu = true
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN) { "TfliteBubbleDetector: GPU delegate init failed, falling back to CPU: ${t.message}" }
                    gpuDelegate?.close()
                    gpuDelegate = null
                }
            }

            if (!createdWithGpu) {
                buffer.rewind()
                interpreter = Interpreter(
                    buffer,
                    Interpreter.Options().apply {
                        setUseXNNPACK(true)
                        setNumThreads(2)
                    },
                )
            }

            interpreter?.let { interp ->
                val inShape = interp.getInputTensor(0).shape()
                isInputNhwc = inShape.size == 4 && inShape[3] == 3
                inputSize = (if (isInputNhwc) inShape.getOrNull(1) else inShape.getOrNull(2))
                    ?.takeIf { it > 0 } ?: INPUT
                allocInput()

                val s = interp.getOutputTensor(0).shape()
                if (s.size == 3 && s[1] > s[2]) {
                    // [1, anchors, features]
                    isOutputTransposed = true
                    numAnchors = s[1]
                    numFeatures = s[2]
                    outputArray3D = Array(1) { Array(numAnchors) { FloatArray(numFeatures) } }
                } else {
                    // [1, features, anchors]
                    isOutputTransposed = false
                    numFeatures = s[1]
                    numAnchors = s[2]
                    outputArray3D = Array(1) { Array(numFeatures) { FloatArray(numAnchors) } }
                }
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR) { "TfliteBubbleDetector: session init failed: ${t.message}" }
        }
    }

    private fun allocInput() {
        pixelBuffer = IntArray(inputSize * inputSize)
        chwBuffer = FloatArray(3 * inputSize * inputSize)
        inputByteBuffer = ByteBuffer.allocateDirect(3 * inputSize * inputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        inputBuffer = inputByteBuffer.asFloatBuffer()
    }

    override val isAvailable: Boolean get() = interpreter != null && ::inputBuffer.isInitialized

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
            gpuDelegate?.close()
            modelBuffer = null
            dispatcher.close()
        } catch (t: Throwable) {
            logcat { "TfliteBubbleDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(interp: Interpreter, bitmap: Bitmap, confThreshold: Float): List<Bubble> {
        val lb = letterboxOf(bitmap.width, bitmap.height, inputSize)

        val scaled = Bitmap.createScaledBitmap(bitmap, lb.nw, lb.nh, true)
        val pixels = pixelBuffer
        scaled.getPixels(pixels, 0, lb.nw, 0, 0, lb.nw, lb.nh)
        if (scaled != bitmap) scaled.recycle()

        inputBuffer.rewind()
        val temp = chwBuffer
        val grey = 114f / 255f
        java.util.Arrays.fill(temp, grey)
        if (isInputNhwc) {
            for (y in 0 until lb.nh) {
                val row = (y + lb.padY) * inputSize
                val src = y * lb.nw
                for (x in 0 until lb.nw) {
                    val p = pixels[src + x]
                    val idx = (row + lb.padX + x) * 3
                    temp[idx] = ((p shr 16) and 0xFF) / 255f
                    temp[idx + 1] = ((p shr 8) and 0xFF) / 255f
                    temp[idx + 2] = (p and 0xFF) / 255f
                }
            }
        } else {
            val plane = inputSize * inputSize
            for (y in 0 until lb.nh) {
                val row = (y + lb.padY) * inputSize
                val src = y * lb.nw
                for (x in 0 until lb.nw) {
                    val p = pixels[src + x]
                    val idx = row + lb.padX + x
                    temp[idx] = ((p shr 16) and 0xFF) / 255f
                    temp[idx + plane] = ((p shr 8) and 0xFF) / 255f
                    temp[idx + 2 * plane] = (p and 0xFF) / 255f
                }
            }
        }
        inputBuffer.put(temp)
        inputBuffer.rewind()

        val out3D = outputArray3D ?: return emptyList()
        interp.run(inputByteBuffer, out3D)

        val featureMajor: Array<FloatArray> = if (isOutputTransposed) {
            val out = out3D[0] // [anchors][features]
            Array(numFeatures) { f -> FloatArray(numAnchors) { a -> out[a][f] } }
        } else {
            out3D[0] // [features][anchors]
        }

        var maxCoord = 0f
        for (a in 0 until numAnchors) {
            maxCoord = max(
                maxCoord,
                max(featureMajor[0][a], max(featureMajor[1][a], max(featureMajor[2][a], featureMajor[3][a]))),
            )
        }
        val coordsIn640Space = maxCoord > 1.5f

        return decodeDetections(
            featureMajor = featureMajor,
            lb = lb,
            coordsIn640Space = coordsIn640Space,
            confThreshold = confThreshold,
            iouThreshold = IOU_THRESHOLD,
        )
    }

    private companion object {
        const val ASSET = "models/bubble_detector_ogkalu.tflite"

        /** SHA-256 of [ASSET]; a truncated/corrupt model otherwise SIGABRTs deep in TFLite. */
        const val ASSET_SHA = "98e3938c48ff0986429fad638aefa3a1f3ac6b506863ded78d10e6d10d2be282"

        const val INPUT = 640
        const val IOU_THRESHOLD = 0.45f
    }
}
