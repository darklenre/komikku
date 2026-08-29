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

    private val cache = object : LruCache<String, List<Bubble>>(CACHE_SIZE) {
        // Bubbles from the segmentation engine carry an ARGB mask Bitmap; recycle it when the page
        // drops out of the cache so masks don't pile up until GC (evictAll / eviction / remove).
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: List<Bubble>, newValue: List<Bubble>?) {
            if (newValue == null) {
                oldValue.forEach { it.maskBitmap?.recycle() }
            }
        }
    }

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

    fun onEngineChanged() {
        synchronized(this) {
            (detector as? OnnxBubbleDetector)?.close()
            (detector as? TfliteBubbleDetector)?.close()
            detector = null
            cache.evictAll()
        }
    }

    private fun detectorFor(appContext: Context): BubbleDetector {
        detector?.let { return it }
        return synchronized(this) {
            detector ?: run {
                if (!isSupported(appContext)) return@synchronized NoopBubbleDetector
                val engine = try {
                    Injekt.get<ReaderPreferences>().bubbleZoomEngine().get()
                } catch (t: Throwable) {
                    "seg"
                }
                if (engine == "ogkalu") {
                    OnnxBubbleDetector(appContext)
                } else {
                    TfliteBubbleDetector(appContext)
                }
            }.also { detector = it }
        }
    }
}
