package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.app.ActivityCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.bubble.Bubble
import eu.kanade.tachiyomi.ui.reader.bubble.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubble.BubbleHit
import eu.kanade.tachiyomi.ui.reader.bubble.BubbleReadingOrder
import eu.kanade.tachiyomi.ui.reader.bubble.ReadingDirection
import eu.kanade.tachiyomi.ui.reader.bubble.bubbleKeyFor
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.BubbleZoomOverlayView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(
    val activity: ReaderActivity,
    val isContinuous: Boolean = true,
    private val tapByPage: Boolean = false,
    // KMK -->
    @param:ColorInt private val seedColor: Int? = null,
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    // KMK <--
) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(
        this,
        // KMK -->
        seedColor = seedColor,
        // KMK <--
    )

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    /* [EXH] private */
    var currentPage: Any? = null

    private val threshold: Int =
        // KMK -->
        readerPreferences
            // KMK <--
            .readerHideThreshold()
            .get()
            .threshold

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        // KMK --> Bubble Zoom
                        if (config.bubbleZoomEnabled) {
                            if (config.bubbleZoomInPlaceGesture == "long_tap" &&
                                tryEnterBubbleZoom(item, child, event.x - child.left, event.y - child.top, BubbleZoomOverlayView.ZoomStyle.IN_PLACE)
                            ) {
                                return@f true
                            }
                            if (config.bubbleZoomFloatingGesture == "long_tap" &&
                                tryEnterBubbleZoom(item, child, event.x - child.left, event.y - child.top, BubbleZoomOverlayView.ZoomStyle.FLOATING)
                            ) {
                                return@f true
                            }
                        }
                        // KMK <--
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        recycler.doubleTapListener = f@{ event ->
            if (config.bubbleZoomEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        if (config.bubbleZoomFloatingGesture == "double_tap" &&
                            tryEnterBubbleZoom(item, child, event.x - child.left, event.y - child.top, BubbleZoomOverlayView.ZoomStyle.FLOATING)
                        ) {
                            return@f true
                        }
                        if (config.bubbleZoomInPlaceGesture == "double_tap" &&
                            tryEnterBubbleZoom(item, child, event.x - child.left, event.y - child.top, BubbleZoomOverlayView.ZoomStyle.IN_PLACE)
                        ) {
                            return@f true
                        }
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        // KMK -->
        config.pinchToZoomChangedListener = {
            frame.pinchToZoom = it
        }

        config.webtoonScaleTypeChangedListener = f@{ scaleType ->
            if (!isContinuous && !readerPreferences.longStripGapSmartScale().get()) return@f

            recycler.post {
                recycler.doOnLayout doOnLayout@{
                    val currentWidth = recycler.width
                    val currentHeight = recycler.originalHeight
                    if (currentWidth <= 0 || currentHeight <= 0) return@doOnLayout

                    if (scaleType == ReaderPreferences.WebtoonScaleType.FIT) {
                        recycler.scaleTo(1f)
                        return@doOnLayout
                    }

                    val desiredRatio = scaleType.ratio
                    val screenRatio = currentWidth.toFloat() / currentHeight
                    val desiredWidth = currentHeight * desiredRatio
                    val desiredScale = desiredWidth / currentWidth

                    if (screenRatio > desiredRatio) {
                        recycler.scaleTo(desiredScale)
                    } else {
                        recycler.scaleTo(1f)
                    }
                }
            }
        }
        // KMK <--

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
        // KMK --> Bubble Zoom: drop any pending page-turn poll so it can't fire on a dead activity
        pendingBubbleZoomPoll?.let { recycler.removeCallbacks(it) }
        pendingBubbleZoomPoll = null
        // KMK <--
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    // KMK --> Bubble Zoom
    private var pendingBubbleZoomPoll: Runnable? = null

    // Cached bubbles for [page] in reading order (vertical); rects stay normalised 0..1. Null if not ready.
    private fun bubbleDetections(page: ReaderPage): List<Bubble>? {
        val bubbles = BubbleDetection.cached(bubbleKeyFor(page))?.takeIf { it.isNotEmpty() } ?: return null
        return BubbleReadingOrder.sort(bubbles, ReadingDirection.VERTICAL)
    }

    // If the touch lands inside a detected bubble on [page], enter Bubble Zoom on that page's
    // image view with [style] and return true; otherwise false.
    private fun tryEnterBubbleZoom(
        page: ReaderPage,
        child: View,
        viewX: Float,
        viewY: Float,
        style: BubbleZoomOverlayView.ZoomStyle,
    ): Boolean {
        val image = child as? ReaderPageImageView ?: return false
        val size = image.sourceImageSize() ?: return false
        val src = image.viewToSourceCoord(viewX, viewY) ?: return false
        val bubbles = bubbleDetections(page) ?: run {
            if (BubbleDetection.isPending(bubbleKeyFor(page))) activity.notifyBubbleZoomDetecting()
            return false
        }
        val nx = src.x / size.width
        val ny = src.y / size.height
        val hit = BubbleHit.hitTest(bubbles, nx, ny)
        if (hit < 0) {
            // "Tap anywhere to zoom": FLOATING gesture that missed → synthesise a bubble at the tap.
            if (!config.bubbleZoomTapAnywhere || style != BubbleZoomOverlayView.ZoomStyle.FLOATING) return false
            activity.enterBubbleZoom(
                target = image,
                page = page,
                bubbles = listOf(BubbleHit.syntheticBubbleAt(nx, ny, bubbles)),
                startIndex = 0,
                style = style,
                sourceSize = size,
            )
            return true
        }
        activity.enterBubbleZoom(
            target = image,
            page = page,
            bubbles = bubbles,
            startIndex = hit,
            style = style,
            sourceSize = size,
        )
        return true
    }

    // Called from the overlay when the user swipes past the first / last bubble of the page. Scrolls
    // to the next ([forward]) / previous ReaderPage and, once its holder + detection are ready,
    // re-enters Bubble Zoom on its first / last bubble. Returns true if a page turn was started.
    fun advanceBubbleZoom(forward: Boolean): Boolean {
        val current = currentPage as? ReaderPage ?: return false
        val curPos = adapter.items.indexOf(current)
        if (curPos < 0) return false
        val targetPos = if (forward) {
            (curPos + 1 until adapter.items.size).firstOrNull { adapter.items[it] is ReaderPage }
        } else {
            (curPos - 1 downTo 0).firstOrNull { adapter.items[it] is ReaderPage }
        } ?: return false
        val targetPage = adapter.items[targetPos] as ReaderPage

        if (config.usePageTransitions) {
            recycler.smoothScrollToPosition(targetPos)
        } else {
            layoutManager.scrollToPositionWithOffset(targetPos, 0)
        }

        pendingBubbleZoomPoll?.let { recycler.removeCallbacks(it) }
        val poll = object : Runnable {
            var attempts = 0
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    pendingBubbleZoomPoll = null
                    return
                }
                val image = recycler.findViewHolderForAdapterPosition(targetPos)?.itemView as? ReaderPageImageView
                val size = image?.sourceImageSize()
                val bubbles = bubbleDetections(targetPage)
                if (image != null && size != null && bubbles != null) {
                    pendingBubbleZoomPoll = null
                    // Keep currentPage in sync with the page we scrolled to before handing off to the overlay.
                    if (currentPage != targetPage) onScrolled(pos = targetPos)
                    activity.enterBubbleZoom(
                        image,
                        targetPage,
                        bubbles,
                        if (forward) 0 else bubbles.lastIndex,
                        sourceSize = size,
                    )
                    return
                }
                if (++attempts < 25) {
                    recycler.postDelayed(this, 100)
                } else {
                    // Gave up (chapter edge, or detection too slow): leave the new page visible.
                    pendingBubbleZoomPoll = null
                    activity.exitBubbleZoom()
                }
            }
        }
        pendingBubbleZoomPoll = poll
        recycler.postDelayed(poll, 120)
        return true
    }
    // KMK <--

    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls one screen over a period of time
     */
    fun linearScroll(duration: Duration) {
        recycler.smoothScrollBy(
            0,
            activity.resources.displayMetrics.heightPixels,
            LinearInterpolator(),
            duration.inWholeMilliseconds.toInt(),
        )
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    /* [EXH] private */
    fun scrollDown() {
        // SY -->
        if (!isContinuous && tapByPage) {
            val currentPage = currentPage
            if (currentPage is ReaderPage) {
                val position = adapter.items.indexOf(currentPage)
                val nextItem = adapter.items.getOrNull(position + 1)
                if (nextItem is ReaderPage) {
                    if (config.usePageTransitions) {
                        recycler.smoothScrollToPosition(position + 1)
                    } else {
                        recycler.scrollToPosition(position + 1)
                    }
                    return
                }
            }
        }
        scrollDownBy()
    }

    private fun scrollDownBy() {
        // SY <--
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4
