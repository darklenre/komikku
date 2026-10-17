package eu.kanade.tachiyomi.ui.reader.bubble

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.LruCache
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    /** Panel layout per page key, for panel-aware reading order. Absent on tiled (webtoon) pages. */
    private val panelCache = LruCache<String, PanelNode>(CACHE_SIZE)

    @Volatile
    private var detector: BubbleDetector? = null

    @Volatile
    private var panelDetector: TflitePanelDetector? = null

    /** Guards [panelDetector] only: its GPU set-up (~0.5 s) must not hold up callers waiting on [detector]. */
    private val panelLock = Any()

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

    /** Panel layout found for [key] alongside its bubbles, or null (not detected / tiled page). */
    fun panels(key: String): PanelNode? = panelCache.get(key)

    /** True while detection for [key] has been requested but no result (even an empty one) is cached yet. */
    fun isPending(key: String): Boolean = cache.get(key) == null

    /** Drops all cached detections so the next page bind re-runs the detector (e.g. after the confidence changed). */
    fun clearCache() {
        cache.evictAll()
        panelCache.evictAll()
    }

    private const val DEFAULT_CONFIDENCE_PERCENT = 40

    /** Boxes from different tiles overlapping by more than this IoU are treated as the same bubble. */
    private const val MERGE_IOU = 0.4f

    /** Long side, px, of the image the panels are looked for in. */
    private const val PANELS_SIDE = 1200

    /** One horizontal slice of a tall (webtoon) page: full width, [topNorm]..[bottomNorm] of the page height. */
    class DetectionTile(val bitmap: Bitmap, val topNorm: Float, val bottomNorm: Float)

    private fun confidenceThreshold(): Float = runCatching {
        Injekt.get<ReaderPreferences>().bubbleZoomConfidence().get()
    }.getOrDefault(DEFAULT_CONFIDENCE_PERCENT).coerceIn(1, 100) / 100f

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

    /** Detects balloons (and their text blocks) on [bitmap] (the final page image), caching under [key]. */
    suspend fun detect(context: Context, key: String, bitmap: Bitmap): List<Bubble> {
        cache.get(key)?.let { return it }
        val confThreshold = confidenceThreshold()
        _activeDetections.update { it + 1 }
        try {
            val result = detectorFor(context.applicationContext)
                .takeIf { it.isAvailable }
                ?.detect(bitmap, confThreshold)
                ?: emptyList()
            if (result.size > 1) {
                runCatching { panelsOf(context.applicationContext, bitmap, result) }.getOrNull()?.let { panelCache.put(key, it) }
            }
            cache.put(key, result)
            return result
        } finally {
            _activeDetections.update { it - 1 }
        }
    }

    /**
     * Like [enqueue] but for a tall (webtoon) page sliced into overlapping [tiles]: detects each tile,
     * maps the boxes back to full-page 0..1 coordinates and merges duplicates across tile seams.
     * Every tile bitmap is recycled on completion.
     */
    fun enqueueTiled(context: Context, key: String, tiles: List<DetectionTile>) {
        detectionScope.launch {
            try {
                detectTiled(context.applicationContext, key, tiles)
            } finally {
                tiles.forEach { it.bitmap.recycle() }
            }
        }
    }

    private suspend fun detectTiled(context: Context, key: String, tiles: List<DetectionTile>): List<Bubble> {
        cache.get(key)?.let { return it }
        val confThreshold = confidenceThreshold()
        _activeDetections.update { it + 1 }
        try {
            val detector = detectorFor(context.applicationContext).takeIf { it.isAvailable }
            val merged = ArrayList<Bubble>()
            if (detector != null) {
                for (tile in tiles) {
                    val span = (tile.bottomNorm - tile.topNorm).coerceAtLeast(1e-4f)
                    for (b in detector.detect(tile.bitmap, confThreshold)) {
                        fun RectF.toPage() = RectF(left, tile.topNorm + top * span, right, tile.topNorm + bottom * span)
                        merged += Bubble(b.rect.toPage(), b.confidence, b.text.map { it.toPage() })
                    }
                }
            }
            val result = dedupeBubbles(merged)
            cache.put(key, result)
            return result
        } finally {
            _activeDetections.update { it - 1 }
        }
    }

    /** Panels from the panel model; the gutter heuristic ([BubblePanels.detect]) only when the model can't run. */
    private suspend fun panelsOf(appContext: Context, bitmap: Bitmap, bubbles: List<Bubble>): PanelNode {
        panelDetectorFor(appContext)?.detect(bitmap)?.let { return BubblePanels.fromBoxes(it) }
        // panels are found at a fixed scale: the detection bitmap's long side varies (1024-2047 px), and
        // more pixels only cost time (the thresholds are tuned at this size)
        val scale = PANELS_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val src = if (abs(scale - 1f) < 0.01f) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
        }
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (src !== bitmap) src.recycle()
        val luma = FloatArray(px.size) {
            val p = px[it]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        return BubblePanels.detect(luma, w, h, bubbles.map { floatArrayOf(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) })
    }

    /** Greedy NMS on merged tile detections: keep by confidence, drop anything overlapping a kept box. */
    private fun dedupeBubbles(bubbles: List<Bubble>): List<Bubble> {
        if (bubbles.size <= 1) return bubbles
        val kept = ArrayList<Bubble>(bubbles.size)
        for (b in bubbles.sortedByDescending { it.confidence }) {
            if (kept.none { rectIoU(it.rect, b.rect) > MERGE_IOU }) kept += b
        }
        return kept
    }

    private fun rectIoU(a: RectF, b: RectF): Float {
        val ix = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val iy = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Most-recent background SAM warmups, oldest first; superseded ones are cancelled (see [prewarmSam]). */
    private val prewarmHandles = ArrayDeque<PrewarmHandle>()
    private const val MAX_INFLIGHT_PREWARM = 2

    private class PrewarmHandle(val key: String) {
        @Volatile
        var cancelled = false
        var job: Job? = null
    }

    /**
     * Precomputes the cutout masks of [key]'s balloons in the background (see [BubbleExtractor.prewarm])
     * so a floating cutout opens instantly. Waits for the page's detection, which the page holder
     * enqueues alongside. No-op unless the cutout method actually uses SAM.
     *
     * Called per page holder as it binds. Only the [MAX_INFLIGHT_PREWARM] most-recently-requested
     * pages stay live; older warmups are cancelled between balloons, so fast scrolling can't build a
     * long tail of wasted encodes.
     */
    fun prewarmSam(context: Context, key: String, streamFn: () -> InputStream) {
        val method = try {
            Injekt.get<ReaderPreferences>().bubbleZoomCroppingMethod().get()
        } catch (t: Throwable) {
            return
        }
        if (method != "sam") return
        val appContext = context.applicationContext
        // The interactive cutout still runs on demand; only the eager background work is skipped
        // while the device is saving power or thermally throttled.
        if (deviceUnderStress(appContext)) return

        val handle = PrewarmHandle(key)
        synchronized(prewarmHandles) {
            prewarmHandles.addLast(handle)
            while (prewarmHandles.size > MAX_INFLIGHT_PREWARM) {
                val stale = prewarmHandles.removeFirst()
                stale.cancelled = true
                stale.job?.cancel()
            }
        }
        handle.job = detectionScope.launch {
            try {
                var bubbles = cache.get(key)
                var waited = 0L
                while (bubbles == null && !handle.cancelled && waited < PREWARM_WAIT_MS) {
                    delay(PREWARM_POLL_MS)
                    waited += PREWARM_POLL_MS
                    bubbles = cache.get(key)
                }
                if (bubbles != null && !handle.cancelled) {
                    runCatching { BubbleExtractor.prewarm(appContext, key, streamFn, bubbles) { !handle.cancelled } }
                }
            } finally {
                synchronized(prewarmHandles) { prewarmHandles.remove(handle) }
            }
        }
    }

    private const val PREWARM_WAIT_MS = 20_000L
    private const val PREWARM_POLL_MS = 100L

    /** True when a background SAM encode would be antisocial: power-save mode or thermal throttling. */
    private fun deviceUnderStress(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        if (pm.isPowerSaveMode) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }

    /**
     * Releases the detector interpreter (and, via [SamRefiner.close], the SAM models). Called on a
     * low-memory trim and when the reader is destroyed; the cached results are kept — they're cheap
     * and let a re-opened page skip re-detection. The detector re-initialises lazily on next use.
     */
    fun releaseDetector() {
        val old = synchronized(this) { detector.also { detector = null } }
        (old as? TfliteBubbleDetector)?.close()
        synchronized(panelLock) { panelDetector.also { panelDetector = null } }?.close()
        // SAM's close waits for an encode in flight: not on the caller's (main) thread
        detectionScope.launch { SamRefiner.close() }
    }

    /**
     * Loads the models ahead of the first page (reader opened, or back from the background after a
     * [releaseDetector]): creating the interpreters, GPU shader compilation included, is most of the
     * ~1.5 s a cold first cutout costs. No-op when Bubble Zoom is off; SAM only when it is the cutout
     * method and the device isn't saving power.
     */
    fun warmUp(context: Context) {
        val prefs = runCatching { Injekt.get<ReaderPreferences>() }.getOrNull() ?: return
        if (!prefs.bubbleZoom().get()) return
        val appContext = context.applicationContext
        detectionScope.launch {
            if (!isSupported(appContext)) return@launch
            detectorFor(appContext)
            panelDetectorFor(appContext)
            if (prefs.bubbleZoomCroppingMethod().get() == "sam" && !deviceUnderStress(appContext)) {
                SamRefiner.prepare(appContext)
            }
        }
    }

    /** The panel model, or null when the device can't run it or it failed to load (the heuristic is used then). */
    private fun panelDetectorFor(appContext: Context): TflitePanelDetector? {
        val d = panelDetector ?: synchronized(panelLock) {
            panelDetector ?: if (isSupported(appContext)) TflitePanelDetector(appContext).also { panelDetector = it } else null
        }
        return d?.takeIf { it.isAvailable }
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
