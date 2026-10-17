package eu.kanade.tachiyomi.ui.reader.bubble

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Debug-only offline eval harness for Bubble Zoom: runs the shipped detection + cutout pipeline over
 * every image in `<external files>/bzeval/pages` exactly as the reader does (pager decode, webtoon
 * tiling for tall pages) and writes per page `out/<name>.json` (bubbles, text blocks, cutout crop rects)
 * plus `out/<name>__<i>.png` (each cutout's alpha as a grey mask). Scored by the playground eval.
 *
 * `adb shell am start -n app.komikku.dev/eu.kanade.tachiyomi.ui.reader.bubble.BubbleEvalActivity`
 * — done when logcat tag BubbleEval prints `done`.
 */
class BubbleEvalActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Timings are only meaningful with the device awake: keep the screen on and show over the lock
        // screen for the duration of the run (window flags, cleared when the activity finishes).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // External files dir (emulator); on devices where adb-pushed files there aren't visible to the
        // app, push to /data/local/tmp and `run-as` copy them into the internal files dir instead.
        val root = listOf(File(getExternalFilesDir(null), "bzeval"), File(filesDir, "bzeval"))
            .firstOrNull { !File(it, "pages").listFiles().isNullOrEmpty() } ?: File(getExternalFilesDir(null), "bzeval")
        val out = File(root, "out").apply { mkdirs() }
        val pages = File(root, "pages").listFiles()?.sortedBy { it.name }.orEmpty()
        scope.launch {
            BubbleDetection.clearCache()
            for ((i, f) in pages.withIndex()) {
                runCatching { evalPage(i, f, out) }
                    .onFailure { logcat(LogPriority.ERROR, it) { "BubbleEval ${f.name} failed" } }
            }
            logcat(LogPriority.WARN) { "BubbleEval done ${pages.size}" }
            runOnUiThread { finish() }
        }
    }

    private suspend fun evalPage(index: Int, file: File, out: File) {
        val name = file.name.substringBeforeLast('.')
        // Resume: a run interrupted (process frozen in background) keeps the pages it already wrote.
        if (File(out, "$name.json").exists() && intent.getBooleanExtra("resume", true)) return
        val bytes = file.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val page = ReaderPage(index, stream = { file.inputStream() })
        page.chapter = ReaderChapter(ChapterImpl().apply { id = -77_000L })
        val key = bubbleKeyFor(page)

        val t0 = System.nanoTime()
        val tiled = h > w * TILE_TRIGGER_ASPECT
        val bubbles = if (!tiled) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = max(1, max(w, h) / DETECTION_MAX_SIDE)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BubbleDetection.detect(this, key, BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts))
        } else {
            BubbleDetection.enqueueTiled(this, key, tiles(bytes, w, h))
            var r: List<Bubble>? = null
            while (r == null) {
                delay(50)
                r = BubbleDetection.cached(key)
            }
            r
        }
        val detMs = (System.nanoTime() - t0) / 1_000_000

        val arr = JSONArray()
        for ((bi, b) in bubbles.withIndex()) {
            val t1 = System.nanoTime()
            val cut = BubbleExtractor.extractBubble(page, b, null)
            val ms = (System.nanoTime() - t1) / 1_000_000
            val o = JSONObject()
                .put("rect", rectJson(b.rect.left, b.rect.top, b.rect.right, b.rect.bottom))
                .put("conf", b.confidence.toDouble())
                .put("ms", ms)
            if (b.text.isNotEmpty()) o.put("text", JSONArray(b.text.map { rectJson(it.left, it.top, it.right, it.bottom) }))
            if (cut != null) {
                val bmp = cut.bitmap
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                for (k in px.indices) {
                    val a = px[k] ushr 24
                    px[k] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
                }
                val m = Bitmap.createBitmap(px, bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                FileOutputStream(File(out, "${name}__$bi.png")).use { m.compress(Bitmap.CompressFormat.PNG, 100, it) }
                m.recycle()
                o.put("cut", JSONArray(listOf(bmp.width, bmp.height)))
                cut.source?.let { r -> o.put("source", JSONArray(listOf(r.left, r.top, r.right, r.bottom))) }
                cut.recycle()
            }
            arr.put(o)
        }
        val res = JSONObject().put("size", JSONArray(listOf(w, h))).put("tiled", tiled)
            .put("detMs", detMs).put("bubbles", arr)
        // reading order as the viewers compute it, as indices into "bubbles"
        val panels = BubbleDetection.panels(key)
        for ((tag, dir) in listOf("orderLTR" to ReadingDirection.LTR, "orderRTL" to ReadingDirection.RTL)) {
            val sorted = BubbleReadingOrder.sort(bubbles, dir, panels)
            res.put(tag, JSONArray(sorted.map { b -> bubbles.indexOfFirst { it === b } }))
        }
        res.put("panels", panels?.leaves(ReadingDirection.LTR)?.size ?: 0)
        File(out, "$name.json").writeText(res.toString(1))
        logcat(LogPriority.WARN) { "BubbleEval $name bubbles=${bubbles.size} det=${detMs}ms" }
    }

    /** Same slicing as WebtoonPageHolder.decodeTiles. */
    private fun tiles(bytes: ByteArray, w: Int, h: Int): List<BubbleDetection.DetectionTile> {
        val sample = max(1, w / DETECTION_MAX_SIDE)
        val decW = w / sample
        var tileSrcH = (decW * TILE_ASPECT).roundToInt() * sample
        val overlap = (tileSrcH * TILE_OVERLAP).roundToInt()
        var step = (tileSrcH - overlap).coerceAtLeast(sample)
        if ((h + step - 1) / step > MAX_TILES) {
            step = (h + MAX_TILES - 1) / MAX_TILES
            tileSrcH = step + overlap
        }
        @Suppress("DEPRECATION")
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }!!
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val tiles = ArrayList<BubbleDetection.DetectionTile>()
        var y = 0
        while (y < h) {
            val bottom = min(y + tileSrcH, h)
            tiles += BubbleDetection.DetectionTile(decoder.decodeRegion(Rect(0, y, w, bottom), opts), y.toFloat() / h, bottom.toFloat() / h)
            if (bottom >= h) break
            y += step
        }
        decoder.recycle()
        return tiles
    }

    private fun rectJson(l: Float, t: Float, r: Float, b: Float) =
        JSONArray(listOf(l.toDouble(), t.toDouble(), r.toDouble(), b.toDouble()))

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}

private const val DETECTION_MAX_SIDE = 1024
private const val TILE_TRIGGER_ASPECT = 2.2f
private const val TILE_ASPECT = 1.3f
private const val TILE_OVERLAP = 0.16f
private const val MAX_TILES = 12
