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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

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

    /** Drops all cached detections so the next page bind re-runs the detector (e.g. after the confidence changed). */
    fun clearCache() = cache.evictAll()

    private const val DEFAULT_CONFIDENCE_PERCENT = 30

    /** Boxes from different tiles overlapping by more than this IoU are treated as the same bubble. */
    private const val MERGE_IOU = 0.4f

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

    /** Detects bubbles on [bitmap] (the final page image), caching under [key]. */
    suspend fun detect(context: Context, key: String, bitmap: Bitmap): List<Bubble> {
        cache.get(key)?.let { return it }
        val confThreshold = confidenceThreshold()
        _activeDetections.update { it + 1 }
        try {
            val result = detectorFor(context.applicationContext)
                .takeIf { it.isAvailable }
                ?.detect(bitmap, confThreshold)
                ?: emptyList()
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
                        merged += Bubble(
                            RectF(
                                b.rect.left,
                                tile.topNorm + b.rect.top * span,
                                b.rect.right,
                                tile.topNorm + b.rect.bottom * span,
                            ),
                            b.confidence,
                        )
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
     * Kicks a background EdgeSAM image-encode for [key] so the first floating cutout on the page
     * doesn't pay the ~2 s encoder latency. No-op unless the cutout method actually uses SAM.
     *
     * Called per page holder as it binds. Only the [MAX_INFLIGHT_PREWARM] most-recently-requested
     * pages stay live; older warmups are cancelled and skipped once they reach the encode lock, so
     * fast scrolling can't build a long tail of wasted ~2 s encodes. [SamRefiner] also skips pages
     * whose embedding is already cached, so steady-state reading is one encode per page turn.
     */
    fun prewarmSam(context: Context, key: String, streamFn: () -> InputStream) {
        val method = try {
            Injekt.get<ReaderPreferences>().bubbleZoomCroppingMethod().get()
        } catch (t: Throwable) {
            return
        }
        if (method != "sam") return
        val appContext = context.applicationContext
        // The interactive cutout still runs on demand; only the eager background encode is skipped
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
                runCatching { SamRefiner.prewarm(appContext, key, streamFn) { !handle.cancelled } }
            } finally {
                synchronized(prewarmHandles) { prewarmHandles.remove(handle) }
            }
        }
    }

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
        synchronized(this) {
            (detector as? TfliteBubbleDetector)?.close()
            detector = null
        }
        SamRefiner.close()
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

/**
 * Union-find grouping of boxes `[l, t, r, b]` (normalised 0..1) that are lobes of one linked speech
 * balloon. Two boxes link when, on one axis, they overlap by at least [overlapFrac] of the smaller
 * box's extent on that axis, and on the other axis their gap (which may be negative, i.e. they
 * touch/overlap) is within [stackGapFrac] of the smaller box for a vertical stack or [sideGapFrac]
 * for a side-by-side pair. Returns index groups (singletons included); the first-seen member fixes
 * each group's order.
 *
 * Top-level (not on [BubbleDetection]) so unit tests can exercise it without class-loading the
 * object's Android-typed fields.
 */
internal fun groupLinked(
    boxes: List<FloatArray>,
    overlapFrac: Float,
    stackGapFrac: Float,
    sideGapFrac: Float,
): List<List<Int>> {
    val n = boxes.size
    val parent = IntArray(n) { it }
    fun find(a: Int): Int {
        var x = a
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val a = boxes[i]
            val c = boxes[j]
            val wa = a[2] - a[0]
            val wc = c[2] - c[0]
            val ha = a[3] - a[1]
            val hc = c[3] - c[1]
            if (wa <= 0f || wc <= 0f || ha <= 0f || hc <= 0f) continue
            val minW = minOf(wa, wc)
            val minH = minOf(ha, hc)
            val overlapX = minOf(a[2], c[2]) - maxOf(a[0], c[0])
            val overlapY = minOf(a[3], c[3]) - maxOf(a[1], c[1])
            val gapX = maxOf(a[0], c[0]) - minOf(a[2], c[2])
            val gapY = maxOf(a[1], c[1]) - minOf(a[3], c[3])
            val stacked = overlapX >= overlapFrac * minW && gapY <= stackGapFrac * minH
            val sideBySide = overlapY >= overlapFrac * minH && gapX <= sideGapFrac * minW
            if (stacked || sideBySide) union(i, j)
        }
    }
    val byRoot = LinkedHashMap<Int, MutableList<Int>>()
    for (i in 0 until n) byRoot.getOrPut(find(i)) { mutableListOf() }.add(i)
    return byRoot.values.map { it.toList() }
}

/**
 * On one axis two boxes must overlap by at least this fraction of the smaller box to count as lobes
 * of the same linked balloon.
 */
private const val LINK_OVERLAP_FRAC = 0.5f

/** Max vertical gap between stacked lobes, as a fraction of the shorter box (negative = overlap). */
private const val LINK_STACK_GAP_FRAC = 0.35f

/**
 * Max horizontal gap between side-by-side lobes, as a fraction of the narrower box. Tighter than the
 * stacked case: side-by-side balloons must nearly touch, or two separate balloons from one speaker
 * sitting near each other get swallowed.
 */
private const val LINK_SIDE_GAP_FRAC = 0.16f

/**
 * Collapses [groupLinked] groups of `bubbles` into one [Bubble] each: [Bubble.rect] is the union of
 * the group and [Bubble.lobes] carries the original per-lobe rects (null for a lone bubble) so the
 * extractor can mask each lobe on its own. Input order is preserved by each group's first member.
 */
internal fun mergeLinked(bubbles: List<Bubble>): List<Bubble> {
    if (bubbles.size <= 1) return bubbles
    val boxes = bubbles.map { floatArrayOf(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) }
    val groups = groupLinked(boxes, LINK_OVERLAP_FRAC, LINK_STACK_GAP_FRAC, LINK_SIDE_GAP_FRAC)
    if (groups.size == bubbles.size) return bubbles
    return groups.map { group ->
        if (group.size == 1) return@map bubbles[group[0]]
        val union = RectF(1f, 1f, 0f, 0f)
        var conf = 0f
        val lobes = ArrayList<RectF>(group.size)
        for (idx in group) {
            val rect = bubbles[idx].rect
            union.union(rect)
            conf = maxOf(conf, bubbles[idx].confidence)
            lobes += RectF(rect)
        }
        Bubble(union, conf, lobes)
    }
}
