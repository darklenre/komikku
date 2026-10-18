package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * A bundled `.tflite` asset compiled with LiteRT 2.x ([CompiledModel]) on the first [Backend] that
 * accepts it, with its input and output buffers allocated once. Not thread-safe: callers keep running
 * it on one thread (or under one lock), as they did with the old `Interpreter`.
 *
 * The asset's SHA-256 is checked first: a truncated or corrupt model otherwise fails as a native
 * SIGABRT deep inside LiteRT instead of a catchable error.
 */
internal class LiteRtModel private constructor(
    private val model: CompiledModel,
    /** Name of the backend that accepted the model, for logs ("gpu", "gpu-fp32", "cpu"). */
    val backend: String,
    private val io: Io?,
) : AutoCloseable {

    /**
     * Signature tensor names, in the index order callers use. [CompiledModel.createInputBuffers] /
     * [CompiledModel.createOutputBuffers] don't follow the signature's `output_0, output_1, ...` order, so
     * models with several inputs or outputs are addressed by name; a model without a signature passes null.
     */
    class Io(val inputs: List<String>, val outputs: List<String>)

    private val inputs: List<TensorBuffer> =
        io?.inputs?.map { model.createInputBuffer(it, SIGNATURE) } ?: model.createInputBuffers()
    private val outputs: List<TensorBuffer> =
        io?.outputs?.map { model.createOutputBuffer(it, SIGNATURE) } ?: model.createOutputBuffers()

    fun writeInput(index: Int, data: FloatArray) = inputs[index].writeFloat(data)

    fun run() {
        if (io == null) {
            model.run(inputs, outputs)
        } else {
            model.run(io.inputs.zip(inputs).toMap(), io.outputs.zip(outputs).toMap(), SIGNATURE)
        }
    }

    fun readFloat(index: Int): FloatArray = outputs[index].readFloat()

    fun readLong(index: Int): LongArray = outputs[index].readLong()

    override fun close() {
        (inputs + outputs).forEach { runCatching { it.close() } }
        runCatching { model.close() }
    }

    /** Where to run a model. GPU backends keep the CPU as fallback for the ops the GPU can't run. */
    enum class Backend(val label: String) {
        /** Qualcomm NPU, for a model compiled ahead of time for this SoC ([npuSources]). */
        NPU("npu"),

        /**
         * Qualcomm NPU, compiling a plain model on the device ([npuSources]): slow the first time (a few seconds
         * per model), then loaded from LiteRT's compilation cache in the app's cache dir.
         */
        NPU_JIT("npu-jit"),
        GPU("gpu"),

        /** GPU at full fp32 precision, for models whose output drifts in fp16 (EdgeSAM's encoder). */
        GPU_FP32("gpu-fp32"),
        CPU("cpu"),
    }

    /** One way to load a model: [asset] (checked against [sha] when given) on [backend]. */
    class Source(val asset: String, val sha: String?, val backend: Backend)

    companion object {
        private const val CPU_THREADS = 4
        private const val SIGNATURE = "serving_default"

        @Volatile
        private var environment: Environment? = null

        /** One environment per process; with the NPU dispatch library when the device has a supported NPU. */
        private fun environment(context: Context): Environment = environment ?: synchronized(this) {
            environment ?: run {
                val npu = BuiltinNpuAcceleratorProvider(context.applicationContext)
                val env = if (npu.isDeviceSupported() && npu.isLibraryReady()) {
                    runCatching { Environment.create(context.applicationContext, npu) }
                        .onFailure { logcat(LogPriority.WARN) { "LiteRtModel: NPU environment failed: ${it.message}" } }
                        .getOrNull()
                } else {
                    null
                }
                (env ?: Environment.create()).also { environment = it }
            }
        }

        /**
         * SHA-256 of the ahead-of-time compiled models (`src/npu/assets/models/npu/<soc>/`, every build type but
         * FOSS); one is used only if this build bundles it.
         */
        private val NPU_SHA = mapOf(
            "models/npu/sm8850/panel_detector_v2.tflite" to
                "6189c5140c61ab6f2f0099454063afb65e42ef261513f9f06bb47103759633e5",
            "models/npu/sm8850/bubble_detector_v4s_head.tflite" to
                "4883eebdd2dfd415d86c72c89893a4aabf4d4bbe041f75757f04d45cf8ac4d35",
            "models/npu/sm8850/sam_encoder_edgesam_v2.tflite" to
                "b2d6e8da7b21e156bdedf30318d58a52c10498847c8f884f8869e142b6a8aae8",
        )

        /** True when this build bundles the NPU runtime and the device's NPU is supported by it. */
        private fun npuAvailable(context: Context): Boolean =
            runCatching { Accelerator.NPU in environment(context).getAvailableAccelerators() }.getOrDefault(false)

        /**
         * The NPU ways to run the model `<name>` (bundled as [asset]): its ahead-of-time compiled variant for
         * this device's SoC (`models/npu/<soc>/<name>.tflite`) when the build has one, then, if [jit], [asset]
         * compiled on the device. Empty without an NPU.
         */
        fun npuSources(context: Context, name: String, asset: String, sha: String, jit: Boolean = true): List<Source> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !npuAvailable(context)) return emptyList()
            val dir = "models/npu/${Build.SOC_MODEL.lowercase()}"
            val aot = "$dir/$name.tflite".takeIf { aot ->
                NPU_SHA.containsKey(aot) && runCatching { context.assets.list(dir) }.getOrNull()?.contains("$name.tflite") == true
            }
            return listOfNotNull(
                aot?.let { Source(it, NPU_SHA.getValue(it), Backend.NPU) },
                Source(asset, sha, Backend.NPU_JIT).takeIf { jit },
            )
        }

        /** Compiles [asset] on the first of [backends] that accepts it. */
        fun open(context: Context, asset: String, sha: String, io: Io?, vararg backends: Backend): LiteRtModel =
            open(context, io, *backends.map { Source(asset, sha, it) }.toTypedArray())

        /**
         * Compiles the first of [sources] that loads. Throws when none does (a source whose asset fails its
         * checksum is skipped like one its backend can't compile).
         */
        fun open(context: Context, io: Io?, vararg sources: Source): LiteRtModel {
            var last: Throwable? = null
            val verified = HashSet<String>()
            for (source in debugOverride(context, sources.toList())) {
                try {
                    if (source.sha != null && source.asset !in verified) {
                        mmapVerifiedModel(context, source.asset, source.sha)
                        verified += source.asset
                    }
                    val model = CompiledModel.create(
                        context.assets,
                        source.asset,
                        options(source.backend, debugFlags(context, source.asset)?.get("htp")),
                        environment(context),
                    )
                    return try {
                        LiteRtModel(model, source.backend.label, io)
                    } catch (t: Throwable) {
                        runCatching { model.close() }
                        throw t
                    }
                } catch (t: Throwable) {
                    last = t
                    logcat(LogPriority.WARN) { "LiteRtModel: ${source.asset} on ${source.backend.label} failed: ${t.message}" }
                }
            }
            throw IllegalStateException("${sources.firstOrNull()?.asset}: no source could be compiled", last)
        }

        /**
         * Debug builds only: `files/bzeval/backends` (lines `npu=off`, `aot=off`, `jit=off`, `gpu=off|fp32`, each
         * optionally scoped to the assets whose path contains a word, e.g. `sam_encoder:npu=off`) narrows the
         * backends, so the eval harness can compare them on one install. `htp=<mode>` sets the NPU's
         * [CompiledModel.QualcommOptions.HtpPerformanceMode] (e.g. `htp=burst`).
         */
        private fun debugOverride(context: Context, sources: List<Source>): List<Source> {
            val asset = sources.firstOrNull()?.asset ?: return sources
            val all = debugFlags(context, asset) ?: return sources
            logcat(LogPriority.WARN) { "LiteRtModel: debug backends for $asset: $all" }
            return sources.mapNotNull { s ->
                val flags = debugFlags(context, s.asset).orEmpty()
                when {
                    s.backend in setOf(Backend.NPU, Backend.NPU_JIT) && flags["npu"] == "off" -> null
                    s.backend == Backend.NPU && flags["aot"] == "off" -> null
                    s.backend == Backend.NPU_JIT && flags["jit"] == "off" -> null
                    s.backend in setOf(Backend.GPU, Backend.GPU_FP32) && flags["gpu"] == "off" -> null
                    s.backend == Backend.GPU && flags["gpu"] == "fp32" -> Source(s.asset, s.sha, Backend.GPU_FP32)
                    else -> s
                }
            }
        }

        /** The debug flags of `files/bzeval/backends` that apply to [asset]; null when there are none. */
        private fun debugFlags(context: Context, asset: String): Map<String, String>? {
            if (!BuildConfig.DEBUG) return null
            val lines = runCatching { File(context.filesDir, "bzeval/backends").readLines() }.getOrNull()
                ?.mapNotNull { line -> line.split('=').takeIf { it.size == 2 } }
                ?: return null
            return lines.mapNotNull { (key, value) ->
                val scope = key.substringBefore(':', "").trim()
                if (scope.isEmpty() || scope in asset) key.substringAfter(':').trim() to value.trim() else null
            }.toMap()
        }

        private fun options(backend: Backend, htpMode: String? = null): CompiledModel.Options {
            val options = when (backend) {
                Backend.NPU, Backend.NPU_JIT -> CompiledModel.Options(Accelerator.NPU)
                Backend.GPU, Backend.GPU_FP32 -> CompiledModel.Options(Accelerator.GPU, Accelerator.CPU)
                Backend.CPU -> CompiledModel.Options(Accelerator.CPU)
            }
            options.cpuOptions = CompiledModel.CpuOptions(CPU_THREADS, null, null)
            if (htpMode != null && backend in setOf(Backend.NPU, Backend.NPU_JIT)) {
                options.qualcommOptions = CompiledModel.QualcommOptions(
                    htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.valueOf(htpMode.uppercase()),
                )
            }
            if (backend == Backend.GPU_FP32) {
                options.gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
            }
            return options
        }
    }
}
