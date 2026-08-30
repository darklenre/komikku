package eu.kanade.tachiyomi.ui.reader.bubble

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.LruCache
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

/**
 * Entry point for Bubble Zoom detection: picks a [BubbleDetector] for the device, runs it off the
 * main thread and caches the result per page (normalised rects). Callers read [cached] on long-tap
 * and only get a hit if detection has already finished for that page.
 */
object BubbleDetection {

    private const val CACHE_SIZE = 32

    /**
     * Devices below this total RAM don't run the model (OOM / too slow). `isLowRamDevice` already
     * filters the Android Go tier; this is the extra floor. Tune after real-device benchmarks.
     */
    private const val MIN_TOTAL_RAM_BYTES = 2L * 1024 * 1024 * 1024

    private val cache = LruCache<String, List<Bubble>>(CACHE_SIZE)

    @Volatile
    private var detector: BubbleDetector? = null

    private val _activeDetections = MutableStateFlow(0)

    /** How many page detections are running right now; drives the reader's processing indicator. */
    val activeDetections: StateFlow<Int> = _activeDetections.asStateFlow()

    /** Whether this device can run bubble detection at all (used to gate the setting + the work). */
    fun isSupported(context: Context): Boolean {
        if (!Process.is64Bit()) return false
        val am = context.getSystemService<ActivityManager>() ?: return false
        if (am.isLowRamDevice) return false
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return info.totalMem >= MIN_TOTAL_RAM_BYTES
    }

    fun cached(key: String): List<Bubble>? = cache.get(key)

    /** True while detection for [key] has been requested but no result (even an empty one) is cached yet. */
    fun isPending(key: String): Boolean = cache.get(key) == null

    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Enqueues a background detection for [bitmap] without blocking or binding to ephemeral page holder lifecycles.
     * [bitmap] is automatically recycled upon completion.
     */
    fun enqueue(context: Context, key: String, bitmap: Bitmap) {
        detectionScope.launch {
            try {
                detect(context.applicationContext, key, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Detects bubbles on [bitmap] (the final page image), caching under [key]. */
    suspend fun detect(context: Context, key: String, bitmap: Bitmap): List<Bubble> {
        cache.get(key)?.let { return it }
        _activeDetections.update { it + 1 }
        try {
            val result = detectorFor(context.applicationContext)
                .takeIf { it.isAvailable }
                ?.detect(bitmap)
                ?: emptyList()
            cache.put(key, result)
            return result
        } finally {
            _activeDetections.update { it - 1 }
        }
    }

    /**
     * Kicks a background MobileSAM image-encode for [key] so the first floating cutout on the page
     * doesn't pay the ~2-3 s encoder latency. No-op unless the cutout method actually uses SAM.
     *
     * Called per page holder as it binds; [SamRefiner] serialises the encodes on one lock and skips
     * pages whose embedding is already cached, so the reader's preload turns into at most a handful
     * of queued encodes that then go idle — in steady-state reading it is one encode per page turn.
     */
    fun prewarmSam(context: Context, key: String, streamFn: () -> InputStream) {
        val method = try {
            Injekt.get<ReaderPreferences>().bubbleZoomCroppingMethod().get()
        } catch (t: Throwable) {
            return
        }
        if (method != "sam") return
        val appContext = context.applicationContext
        detectionScope.launch {
            runCatching { SamRefiner.prewarm(appContext, key, streamFn) }
        }
    }

    private fun detectorFor(appContext: Context): BubbleDetector {
        detector?.let { return it }
        return synchronized(this) {
            detector ?: run {
                if (!isSupported(appContext)) {
                    NoopBubbleDetector
                } else {
                    TfliteBubbleDetector(appContext)
                }
            }.also { detector = it }
        }
    }
}
