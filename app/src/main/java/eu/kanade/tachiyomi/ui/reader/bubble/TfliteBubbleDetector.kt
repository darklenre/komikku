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
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * [BubbleDetector] backed by TensorFlow Lite / LiteRT and the bundled segmentation model
 * `assets/models/bubble_detector_seg.tflite` (YOLOv8-seg int8).
 *
 * NOTE: the current seg model artifact is licensed GPL-3.0, which is incompatible with this app's
 * Apache-2.0 distribution — its inclusion is still under review (see claude-com.md §9). This class
 * itself is engine-agnostic; only [ASSET] needs to change if the model is swapped.
 *
 * Input: `[1, 3, 640, 640]` or `[1, 640, 640, 3]`, float32 (auto-detected).
 * Output: detect head `[1, features, 8400]` (+ optional proto-mask tensor `[1, 32, 160, 160]`).
 * Inference runs on a single background thread with GPU acceleration if supported, falling back to XNNPACK CPU.
 */
class TfliteBubbleDetector(context: Context) : BubbleDetector {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-detect-tfl").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var modelStream: FileInputStream? = null
    private var modelChannel: FileChannel? = null
    private var modelBuffer: java.nio.MappedByteBuffer? = null

    private val pixelBuffer = IntArray(INPUT * INPUT)
    private val chwBuffer = FloatArray(3 * INPUT * INPUT)
    private val inputBuffer: FloatBuffer
    private val inputByteBuffer: ByteBuffer

    private var isInputNhwc = false
    private var numFeatures = 6
    private var numAnchors = 8400
    private var isOutputTransposed = false
    private var detectOutIdx = 0
    private var outputArray3D: Array<Array<FloatArray>>? = null

    // Proto masks (YOLOv8-seg). The raw interpreter buffer keeps the model's native layout;
    // [protoChw] is the reused [32][H][W] view BubblePostprocess expects.
    private var protoOutIdx = -1
    private var protoNhwc = false
    private var protoC = 32
    private var protoH = 0
    private var protoW = 0
    private var protoRaw: Array<*>? = null
    private var protoChw: Array<Array<FloatArray>>? = null

    private val outputMap = HashMap<Int, Any>()

    init {
        inputByteBuffer = ByteBuffer.allocateDirect(1 * 3 * INPUT * INPUT * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        inputBuffer = inputByteBuffer.asFloatBuffer()

        try {
            // The .tflite asset is stored uncompressed (noCompress "tflite" in build.gradle.kts),
            // so it can be mmap'd straight from the APK — no copy into filesDir, no full read into heap.
            val buffer = loadModelBuffer(context, ASSET)
            modelBuffer = buffer
            val compatList = CompatibilityList()
            var createdWithGpu = false

            if (compatList.isDelegateSupportedOnThisDevice) {
                try {
                    val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    val options = Interpreter.Options().apply {
                        addDelegate(delegate)
                    }
                    interpreter = Interpreter(buffer, options)
                    gpuDelegate = delegate
                    createdWithGpu = true
                    logcat(LogPriority.INFO) { "TfliteBubbleDetector: session ready (GPU delegate enabled)" }
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN) { "TfliteBubbleDetector: GPU delegate init failed, falling back to CPU: ${t.message}" }
                    gpuDelegate?.close()
                    gpuDelegate = null
                }
            }

            if (!createdWithGpu) {
                buffer.rewind()
                val options = Interpreter.Options().apply {
                    setUseXNNPACK(true)
                    setNumThreads(2)
                }
                interpreter = Interpreter(buffer, options)
                logcat(LogPriority.INFO) { "TfliteBubbleDetector: session ready (XNNPACK CPU)" }
            }

            interpreter?.let { interp ->
                val inShape = interp.getInputTensor(0).shape()
                isInputNhwc = inShape.size == 4 && inShape[3] == 3

                // Classify each output: the 3-D tensor is the detect head, a 4-D tensor is the
                // seg proto masks. The export can emit them in either order.
                for (i in 0 until interp.outputTensorCount) {
                    val s = interp.getOutputTensor(i).shape()
                    when {
                        s.size == 3 -> {
                            detectOutIdx = i
                            if (s[1] == 8400 && s[2] <= 40) {
                                isOutputTransposed = true
                                numAnchors = s[1]
                                numFeatures = s[2]
                                outputArray3D = Array(1) { Array(numAnchors) { FloatArray(numFeatures) } }
                            } else {
                                isOutputTransposed = false
                                numFeatures = s[1]
                                numAnchors = s[2]
                                outputArray3D = Array(1) { Array(numFeatures) { FloatArray(numAnchors) } }
                            }
                        }
                        s.size == 4 -> {
                            protoOutIdx = i
                            // NCHW [1,32,H,W] vs NHWC [1,H,W,32]: the mask-coeff axis (32) is the
                            // small one and sits at index 1 (NCHW) or 3 (NHWC).
                            protoNhwc = s[3] < s[1]
                            if (protoNhwc) {
                                protoH = s[1]
                                protoW = s[2]
                                protoC = s[3]
                                protoRaw = Array(1) { Array(protoH) { Array(protoW) { FloatArray(protoC) } } }
                            } else {
                                protoC = s[1]
                                protoH = s[2]
                                protoW = s[3]
                                protoRaw = Array(1) { Array(protoC) { Array(protoH) { FloatArray(protoW) } } }
                            }
                            protoChw = Array(protoC) { Array(protoH) { FloatArray(protoW) } }
                        }
                    }
                }
                outputArray3D?.let { outputMap[detectOutIdx] = it }
                protoRaw?.let { outputMap[protoOutIdx] = it }

                logcat(LogPriority.INFO) {
                    "TfliteBubbleDetector: in=${inShape.toList()} nhwc=$isInputNhwc; " +
                        "outputs=" + (0 until interp.outputTensorCount).joinToString {
                            "#$it${interp.getOutputTensor(it).shape().toList()}"
                        } +
                        "; detect=#$detectOutIdx transposed=$isOutputTransposed feat=$numFeatures anchors=$numAnchors" +
                        "; proto=" + if (protoOutIdx >= 0) "#$protoOutIdx nhwc=$protoNhwc ${protoC}x${protoH}x$protoW" else "none"
                }
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR) { "TfliteBubbleDetector: session init failed: ${t.message}" }
        }
    }

    override val isAvailable: Boolean get() = interpreter != null

    override suspend fun detect(bitmap: Bitmap): List<Bubble> {
        val interp = interpreter ?: return emptyList()
        return withContext(dispatcher) {
            val t0 = System.currentTimeMillis()
            val result = runCatching { infer(interp, bitmap) }
                .onFailure { logcat(LogPriority.ERROR) { "TfliteBubbleDetector: inference failed: ${it.message}" } }
                .getOrDefault(emptyList())
            val dt = System.currentTimeMillis() - t0
            logcat(LogPriority.INFO) { "TfliteBubbleDetector: page detected in ${dt}ms (${result.size} bubbles, ${bitmap.width}x${bitmap.height})" }
            result
        }
    }

    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
            modelChannel?.close()
            modelStream?.close()
            modelBuffer = null
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

        inputBuffer.rewind()
        if (isInputNhwc) {
            // Pack into NHWC FloatBuffer [1, 640, 640, 3]
            val tempHwc = chwBuffer
            val grey = 114f / 255f
            java.util.Arrays.fill(tempHwc, grey)
            for (y in 0 until lb.nh) {
                val row = (y + lb.padY) * INPUT
                val src = y * lb.nw
                for (x in 0 until lb.nw) {
                    val p = pixels[src + x]
                    val idx = (row + lb.padX + x) * 3
                    tempHwc[idx] = ((p shr 16) and 0xFF) / 255f
                    tempHwc[idx + 1] = ((p shr 8) and 0xFF) / 255f
                    tempHwc[idx + 2] = (p and 0xFF) / 255f
                }
            }
            inputBuffer.put(tempHwc)
        } else {
            // Pack into NCHW FloatBuffer [1, 3, 640, 640]
            val tempChw = chwBuffer
            val plane = INPUT * INPUT
            val grey = 114f / 255f
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
        }
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
            protoMasks = protoChwOrNull(),
        )
    }

    /** Fills [protoChw] ([32][H][W]) from the raw interpreter output, transposing if it was NHWC. */
    private fun protoChwOrNull(): Array<Array<FloatArray>>? {
        val raw = protoRaw ?: return null

        @Suppress("UNCHECKED_CAST")
        val batch = (raw as Array<Array<Array<FloatArray>>>)[0]
        if (!protoNhwc) return batch // already [C][H][W]
        val chw = protoChw ?: return null
        for (y in 0 until protoH) {
            val rowY = batch[y]
            for (x in 0 until protoW) {
                val px = rowY[x]
                for (c in 0 until protoC) chw[c][y][x] = px[c]
            }
        }
        return chw
    }

    private fun loadModelBuffer(context: Context, path: String): java.nio.MappedByteBuffer {
        context.assets.openFd(path).use { fd ->
            val stream = FileInputStream(fd.fileDescriptor)
            val channel = stream.channel
            modelStream = stream
            modelChannel = channel
            // The mapping stays valid after the fd is closed.
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private companion object {
        const val ASSET = "models/bubble_detector_seg.tflite"
        const val INPUT = 640
        const val CONF_THRESHOLD = 0.30f
        const val IOU_THRESHOLD = 0.45f
    }
}
