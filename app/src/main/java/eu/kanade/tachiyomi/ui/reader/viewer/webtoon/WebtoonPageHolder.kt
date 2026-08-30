package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.bubble.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubble.bubbleKeyFor
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : WebtoonBaseHolder(frame, viewer) {

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { error -> setError(error) }
        frame.onScaleChanged = { viewer.activity.hideMenu() }
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null

        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator.setProgress(0)

        val streamFn = page?.stream ?: return

        val currentPage = page ?: return
        // KMK: detection input, decoded from the FINAL image (post rotate/split/crop)
        var detectionInput: DetectionInput? = null
        val wantBubbleDetection = viewer.config.bubbleZoomEnabled &&
            BubbleDetection.isSupported(context) &&
            BubbleDetection.cached(bubbleKeyFor(currentPage)) == null

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                if (!isAnimated && wantBubbleDetection) {
                    detectionInput = decodeForDetection(source.peek())
                }
                Pair(source, isAnimated)
            }
            withUIContext {
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders =
                        (viewer.config.imageCropBorders && viewer.isContinuous) ||
                            (viewer.config.continuousCropBorders && !viewer.isContinuous),
                        // KMK: let Bubble Zoom own the double-tap when it's bound to it
                        doubleTapZoom = !viewer.config.bubbleZoomUsesDoubleTap,
                    ),
                )
                removeErrorLayout()
            }
            // KMK --> Bubble Zoom: run detection on the final page image in the background
            when (val input = detectionInput) {
                is DetectionInput.Single -> BubbleDetection.enqueue(context, bubbleKeyFor(currentPage), input.bitmap)
                is DetectionInput.Tiled -> BubbleDetection.enqueueTiled(context, bubbleKeyFor(currentPage), input.tiles)
                null -> Unit
            }
            if (viewer.config.bubbleZoomEnabled) {
                BubbleDetection.prewarmSam(context, bubbleKeyFor(currentPage), streamFn)
            }
            // KMK <--
        } catch (e: Throwable) {
            detectionInput?.recycle()
            if (e is kotlinx.coroutines.CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    // KMK --> Bubble Zoom
    /** Either one downscaled bitmap, or (for a tall strip) overlapping full-width horizontal tiles. */
    private sealed interface DetectionInput {
        class Single(val bitmap: Bitmap) : DetectionInput
        class Tiled(val tiles: List<BubbleDetection.DetectionTile>) : DetectionInput

        fun recycle() = when (this) {
            is Single -> bitmap.recycle()
            is Tiled -> tiles.forEach { it.bitmap.recycle() }
        }
    }

    /**
     * Builds the detection input from [source] (a peek of the final page image). A normal page is one
     * bitmap downscaled so its long side is <= [DETECTION_MAX_SIDE]. A tall webtoon strip would lose
     * its width to that clamp (a 1000x15000 page → ~68 px wide), so it is instead sliced into
     * overlapping full-width tiles decoded with a *width*-based sample factor.
     */
    private fun decodeForDetection(source: BufferedSource): DetectionInput? = try {
        val bytes = source.readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        when {
            w <= 0 || h <= 0 -> null
            h <= w * TILE_TRIGGER_ASPECT -> {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = max(1, max(w, h) / DETECTION_MAX_SIDE)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { DetectionInput.Single(it) }
            }
            else -> decodeTiles(bytes, w, h)
        }
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "Bubble detection decode failed" }
        null
    }

    private fun decodeTiles(bytes: ByteArray, w: Int, h: Int): DetectionInput? {
        val sample = max(1, w / DETECTION_MAX_SIDE)
        val decW = w / sample
        var tileSrcH = (decW * TILE_ASPECT).roundToInt() * sample
        val overlap = (tileSrcH * TILE_OVERLAP).roundToInt()
        var step = (tileSrcH - overlap).coerceAtLeast(sample)
        // Cap the tile count on very long pages by growing each tile.
        if ((h + step - 1) / step > MAX_TILES) {
            step = (h + MAX_TILES - 1) / MAX_TILES
            tileSrcH = step + overlap
        }
        @Suppress("DEPRECATION")
        val decoder = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            } else {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            }
            ) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val tiles = ArrayList<BubbleDetection.DetectionTile>()
        var y = 0
        while (y < h) {
            val bottom = min(y + tileSrcH, h)
            val tile = runCatching { decoder.decodeRegion(Rect(0, y, w, bottom), opts) }.getOrNull()
            if (tile != null) {
                tiles += BubbleDetection.DetectionTile(tile, y.toFloat() / h, bottom.toFloat() / h)
            }
            if (bottom >= h) break
            y += step
        }
        decoder.recycle()
        return if (tiles.isEmpty()) null else DetectionInput.Tiled(tiles)
    }
    // KMK <--

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressContainer.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(
            context,
            // KMK -->
            seedColor = seedColor,
            // KMK <--
        ).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}

// KMK: longest side (px) the page is downscaled to before running bubble detection
private const val DETECTION_MAX_SIDE = 1024

// KMK: a page taller than width * this is tiled for detection instead of downscaled whole
private const val TILE_TRIGGER_ASPECT = 2.2f
private const val TILE_ASPECT = 1.3f // decoded tile height ≈ width * this
private const val TILE_OVERLAP = 0.16f // fraction of tile height shared with the next tile
private const val MAX_TILES = 12
