package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * [BubbleDetector] backed by TensorFlow Lite / LiteRT and the bundled model
 * `assets/models/bubble_detector.tflite` (`ogkalu/comic-speech-bubble-detector-yolov8m`, Apache-2.0).
 *
 * Input: `[1, 3, 640, 640]`, float32.
 * Output: `[1, features, 8400]`.
 * Inference runs on a single background thread with GPU acceleration if supported, falling back to XNNPACK CPU.
 */
class TfliteBubbleDetector(context: Context) : BubbleDetector {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect-tfl").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private val pixelBuffer = IntArray(INPUT * INPUT)
    private val chwBuffer = FloatArray(3 * INPUT * INPUT)
    private val inputBuffer: FloatBuffer
    private val inputByteBuffer: ByteBuffer

    private var numFeatures = 6
    private var numAnchors = 8400
    private var isOutputTransposed = false
    private var outputArray3D: Array<Array<FloatArray>>? = null
    private var protoArray4D: Array<Array<Array<FloatArray>>>? = null
    private val outputMap = HashMap<Int, Any>()

    init {
        inputByteBuffer = ByteBuffer.allocateDirect(1 * 3 * INPUT * INPUT * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        inputBuffer = inputByteBuffer.asFloatBuffer()

        try {
            val modelBuffer = loadModelBuffer(context, ASSET)
            val compatList = CompatibilityList()
            var createdWithGpu = false

            if (compatList.isDelegateSupportedOnThisDevice) {
                try {
                    val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    val options = Interpreter.Options().apply {
                        addDelegate(delegate)
                    }
                    interpreter = Interpreter(modelBuffer, options)
                    gpuDelegate = delegate
                    createdWithGpu = true
                    logcat { "TfliteBubbleDetector: session ready (GPU delegate enabled)" }
                } catch (t: Throwable) {
                    logcat { "TfliteBubbleDetector: GPU delegate init failed, falling back to CPU: ${t.message}" }
                    gpuDelegate?.close()
                    gpuDelegate = null
                }
            }

            if (!createdWithGpu) {
                val options = Interpreter.Options().apply {
                    setUseXNNPACK(true)
                    setNumThreads(2)
                }
                interpreter = Interpreter(modelBuffer, options)
                logcat { "TfliteBubbleDetector: session ready (XNNPACK CPU)" }
            }

            interpreter?.let { interp ->
                val outShape = interp.getOutputTensor(0).shape()
                if (outShape.size == 3) {
                    if (outShape[1] == 8400 && outShape[2] <= 40) {
                        isOutputTransposed = true
                        numAnchors = outShape[1]
                        numFeatures = outShape[2]
                        outputArray3D = Array(1) { Array(numAnchors) { FloatArray(numFeatures) } }
                    } else {
                        isOutputTransposed = false
                        numFeatures = outShape[1]
                        numAnchors = outShape[2]
                        outputArray3D = Array(1) { Array(numFeatures) { FloatArray(numAnchors) } }
                    }
                }
                outputArray3D?.let { outputMap[0] = it }

                if (interp.outputTensorCount > 1) {
                    val protoShape = interp.getOutputTensor(1).shape()
                    if (protoShape.size == 4) {
                        val masks = protoShape[1]
                        val protoH = protoShape[2]
                        val protoW = protoShape[3]
                        val protoArr = Array(1) { Array(masks) { Array(protoH) { FloatArray(protoW) } } }
                        protoArray4D = protoArr
                        outputMap[1] = protoArr
                    }
                }
            }
        } catch (t: Throwable) {
            logcat { "TfliteBubbleDetector: session init failed: ${t.message}" }
        }
    }

    override val isAvailable: Boolean get() = interpreter != null

    override suspend fun detect(bitmap: Bitmap): List<Bubble> {
        val interp = interpreter ?: return emptyList()
        return withContext(dispatcher) {
            val t0 = System.currentTimeMillis()
            val result = runCatching { infer(interp, bitmap) }
                .onFailure { logcat(logcat.LogPriority.ERROR) { "TfliteBubbleDetector: inference failed: ${it.message}" } }
                .getOrDefault(emptyList())
            val dt = System.currentTimeMillis() - t0
            logcat(logcat.LogPriority.INFO) { "TfliteBubbleDetector: page detected in ${dt}ms (${result.size} bubbles, ${bitmap.width}x${bitmap.height})" }
            result
        }
    }

    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
            dispatcher.close()
        } catch (t: Throwable) {
            logcat { "TfliteBubbleDetector: close failed: ${t.message}" }
        }
    }

    private fun infer(interp: Interpreter, bitmap: Bitmap): List<Bubble> {
        val lb = letterboxOf(bitmap.width, bitmap.height, INPUT)

        val scaled = Bitmap.createScaledBitmap(bitmap, lb.nw, lb.nh, true)
        val pixels = pixelBuffer
        scaled.getPixels(pixels, 0, lb.nw, 0, 0, lb.nw, lb.nh)
        if (scaled != bitmap) scaled.recycle()

        // Pack into NCHW FloatBuffer [1, 3, 640, 640]
        inputBuffer.rewind()
        val plane = INPUT * INPUT
        val grey = 114f / 255f

        val tempChw = chwBuffer
        java.util.Arrays.fill(tempChw, grey)

        for (y in 0 until lb.nh) {
            val row = (y + lb.padY) * INPUT
            val src = y * lb.nw
            for (x in 0 until lb.nw) {
                val p = pixels[src + x]
                val idx = row + lb.padX + x
                tempChw[idx] = ((p shr 16) and 0xFF) / 255f
                tempChw[idx + plane] = ((p shr 8) and 0xFF) / 255f
                tempChw[idx + 2 * plane] = (p and 0xFF) / 255f
            }
        }
        inputBuffer.put(tempChw)
        inputBuffer.rewind()

        val out3D = outputArray3D ?: return emptyList()
        if (outputMap.size > 1) {
            interp.runForMultipleInputsOutputs(arrayOf(inputByteBuffer), outputMap)
        } else {
            interp.run(inputByteBuffer, out3D)
        }

        val featureMajor: Array<FloatArray> = if (isOutputTransposed) {
            val out = out3D[0] // [8400][features]
            val transposed = Array(numFeatures) { FloatArray(numAnchors) }
            for (a in 0 until numAnchors) {
                val row = out[a]
                for (f in 0 until numFeatures) {
                    transposed[f][a] = row[f]
                }
            }
            transposed
        } else {
            out3D[0] // [features][8400]
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
            confThreshold = CONF_THRESHOLD,
            iouThreshold = IOU_THRESHOLD,
            protoMasks = protoArray4D?.get(0),
        )
    }

    private fun loadModelBuffer(context: Context, path: String): ByteBuffer {
        return try {
            val fd = context.assets.openFd(path)
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        } catch (e: Throwable) {
            context.assets.open(path).use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            }
        }
    }

    private companion object {
        const val ASSET = "models/bubble_detector.tflite"
        const val INPUT = 640
        const val CONF_THRESHOLD = 0.30f
        const val IOU_THRESHOLD = 0.45f
    }
}
