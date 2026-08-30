package eu.kanade.tachiyomi.ui.reader.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.bubble.Bubble
import eu.kanade.tachiyomi.ui.reader.bubble.BubbleExtractor
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Full-screen overlay that is active only while "Bubble Zoom" mode is on.
 * Supports two distinct styles:
 *  - [ZoomStyle.IN_PLACE]: zooms the underlying page to center on the bubble.
 *  - [ZoomStyle.FLOATING]: displays the extracted bubble cutout floating centered with a darkened backdrop.
 */
@SuppressLint("ClickableViewAccessibility")
class BubbleZoomOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class ZoomStyle {
        IN_PLACE,
        FLOATING,
    }

    private var target: ReaderPageImageView? = null
    private var currentPage: ReaderPage? = null
    private var rawBubbles: List<Bubble> = emptyList()
    /** [rawBubbles]' rects, normalised 0..1 of the source image. */
    private var bubbleRects: List<RectF> = emptyList()
    /** Source-image size in px, for converting the normalised rects when a px rect is needed. */
    private var sourceSize: Size = Size(0, 0)
    private val extractedBitmaps: MutableMap<Int, Bitmap?> = mutableMapOf()
    private var zoomStyle = ZoomStyle.IN_PLACE

    private var index = 0
    private var onExitListener: (() -> Unit)? = null
    private var onEdgeListener: ((forward: Boolean) -> Boolean)? = null

    private var hint: String? = null
    private var hintUntil = 0L

    // Single-threaded: bubble cutouts (esp. the SAM path) must not pile up while the user swipes.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val extractScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var extractJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * FLOATING entry animation: the cutout scales + fades from the bubble's on-page rect to its
     * centered resting place. [enterAnimFrom] is the start rect in view px (null = no animation);
     * [enterAnimStart] is 0 until the first frame that actually has a cutout to draw.
     */
    private var enterAnimFrom: RectF? = null
    private var enterAnimStart = 0L

    val isActive: Boolean get() = isVisible && target != null

    private val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 44f
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 38f
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backdropPaint = Paint().apply {
        color = Color.argb(210, 0, 0, 0) // Dimmed 82% black backdrop
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                exit()
                return true
            }

            // While zoomed, a double tap must only exit (never fall through to the page's 2x zoom).
            override fun onDoubleTap(e: MotionEvent): Boolean {
                exit()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val forward = if (abs(velocityX) >= abs(velocityY)) velocityX < 0 else velocityY < 0
                if (forward) next() else prev()
                return true
            }
        },
    )

    init {
        setWillNotDraw(false)
    }

    private var backdropEnabled = true

    fun enter(
        target: ReaderPageImageView,
        page: ReaderPage,
        bubbles: List<Bubble>,
        startIndex: Int,
        sourceSize: Size,
        style: ZoomStyle = ZoomStyle.IN_PLACE,
        backdrop: Boolean = true,
        hint: String? = null,
        onEdge: (forward: Boolean) -> Boolean = { false },
        onExit: () -> Unit = {},
    ) {
        if (bubbles.isEmpty()) return
        // Re-entering on a page turn: hand the previous page's zoom gestures back.
        this.target?.takeIf { it !== target }?.setGestureZoomEnabled(true)
        this.target = target
        // Kill the page's own pinch / double-tap zoom while the overlay owns the screen, and undo
        // any 2x zoom the triggering double-tap may have just started.
        target.setGestureZoomEnabled(false)
        if (style == ZoomStyle.FLOATING) target.resetZoom(animate = false)
        this.currentPage = page
        this.rawBubbles = bubbles
        this.bubbleRects = bubbles.map { it.rect }
        this.sourceSize = sourceSize
        this.zoomStyle = style
        this.backdropEnabled = backdrop
        this.extractedBitmaps.clear()
        this.index = startIndex.coerceIn(0, bubbles.lastIndex)
        this.onEdgeListener = onEdge
        this.onExitListener = onExit
        if (hint != null) {
            this.hint = hint
            hintUntil = SystemClock.uptimeMillis() + HINT_DURATION_MS
            postInvalidateDelayed(HINT_DURATION_MS)
        }
        isVisible = true
        bringToFront()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        focusCurrent()
    }

    fun exit() {
        if (!isActive) return
        if (zoomStyle == ZoomStyle.IN_PLACE) {
            target?.resetZoom(animate = true)
        }
        target?.setGestureZoomEnabled(true)
        extractJob?.cancel()
        prefetchJob?.cancel()
        val cb = onExitListener
        target = null
        currentPage = null
        rawBubbles = emptyList()
        bubbleRects = emptyList()
        extractedBitmaps.values.forEach { it?.recycle() }
        extractedBitmaps.clear()
        onEdgeListener = null
        onExitListener = null
        hint = null
        enterAnimFrom = null
        isVisible = false
        cb?.invoke()
    }

    private fun next() {
        if (index < rawBubbles.lastIndex) {
            index++
            step()
        } else {
            onEdgeListener?.invoke(true)
        }
    }

    private fun prev() {
        if (index > 0) {
            index--
            step()
        } else {
            onEdgeListener?.invoke(false)
        }
    }

    private fun step() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        focusCurrent()
    }

    private fun focusCurrent() {
        val t = target ?: return
        val rect = bubbleRects.getOrNull(index) ?: return

        if (zoomStyle == ZoomStyle.IN_PLACE) {
            val px = rect.scaledToPx()
            t.post { t.focusOnRect(px) }
        } else {
            // Start the cutout from where the bubble sits on the page, then animate it to centre.
            enterAnimFrom = t.sourceToViewRect(rect.scaledToPx())
            enterAnimStart = 0L
            requestExtraction(index)
            // One-ahead prefetch only (reading direction); the extractor runs single-threaded so this
            // just queues behind the current one and never floods.
            if (index + 1 < rawBubbles.size) prefetchExtraction(index + 1)
        }
        invalidate()
    }

    /** This normalised rect in source-image px. */
    private fun RectF.scaledToPx() = RectF(
        left * sourceSize.width,
        top * sourceSize.height,
        right * sourceSize.width,
        bottom * sourceSize.height,
    )

    private fun requestExtraction(idx: Int) {
        if (extractedBitmaps.containsKey(idx)) return
        val p = currentPage ?: return
        val bubble = rawBubbles.getOrNull(idx) ?: return
        val t = target

        extractJob?.cancel()
        extractJob = extractScope.launch {
            val bmp = extractBubbleBitmap(p, bubble, t)
            withContext(Dispatchers.Main) {
                extractedBitmaps[idx] = bmp
                invalidate()
            }
        }
    }

    private fun prefetchExtraction(idx: Int) {
        if (extractedBitmaps.containsKey(idx)) return
        val p = currentPage ?: return
        val bubble = rawBubbles.getOrNull(idx) ?: return
        val t = target

        prefetchJob?.cancel()
        prefetchJob = extractScope.launch {
            val bmp = extractBubbleBitmap(p, bubble, t)
            withContext(Dispatchers.Main) {
                if (isActive && !extractedBitmaps.containsKey(idx)) {
                    extractedBitmaps[idx] = bmp
                } else {
                    bmp?.recycle()
                }
            }
        }
    }

    /** Bubble rects are normalised; [BubbleExtractor] takes them as-is, the crop fallback needs px. */
    private fun extractBubbleBitmap(p: ReaderPage, bubble: Bubble, t: ReaderPageImageView?): Bitmap? =
        BubbleExtractor.extractBubble(p, bubble, sourceSize.takeIf { it.width > 0 && it.height > 0 })
            ?: t?.cropSourceRect(bubble.rect.scaledToPx())

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        extractJob?.cancel()
        prefetchJob?.cancel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!isActive) return

        if (zoomStyle == ZoomStyle.FLOATING) {
            if (backdropEnabled) {
                // Draw dimmed lightbox backdrop
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
            }

            // Draw centered floating extracted bubble maximized on screen
            val bmp = extractedBitmaps[index]
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val maxW = width * 0.92f
                val maxH = height * 0.85f
                val scale = minOf(maxW / bmp.width, maxH / bmp.height, 4.0f).coerceAtLeast(1.0f)
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                val drawL = (width - drawW) / 2f
                val drawT = (height - drawH) / 2f
                val finalRect = RectF(drawL, drawT, drawL + drawW, drawT + drawH)

                // The bitmap is already cut to the bubble shape (transparent around it): draw it
                // straight onto the backdrop, no card behind it.
                val from = enterAnimFrom
                if (from != null) {
                    if (enterAnimStart == 0L) enterAnimStart = SystemClock.uptimeMillis()
                    val lin = ((SystemClock.uptimeMillis() - enterAnimStart) / ENTER_ANIM_MS.toFloat())
                        .coerceIn(0f, 1f)
                    val f = 1f - (1f - lin) * (1f - lin) // ease-out quad
                    val r = RectF(
                        from.left + (finalRect.left - from.left) * f,
                        from.top + (finalRect.top - from.top) * f,
                        from.right + (finalRect.right - from.right) * f,
                        from.bottom + (finalRect.bottom - from.bottom) * f,
                    )
                    bitmapPaint.alpha = (f * 255f).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(bmp, null, r, bitmapPaint)
                    bitmapPaint.alpha = 255
                    if (lin < 1f) postInvalidateOnAnimation() else enterAnimFrom = null
                } else {
                    canvas.drawBitmap(bmp, null, finalRect, bitmapPaint)
                }
            } else if (!extractedBitmaps.containsKey(index)) {
                canvas.drawText("…", width / 2f, height / 2f, counterPaint)
            }
        }

        // Counter & hints
        if (rawBubbles.size > 1) {
            canvas.drawText(
                "${index + 1} / ${rawBubbles.size}",
                width / 2f,
                counterPaint.textSize + 24f,
                counterPaint,
            )
        }
        val h = hint
        if (h != null && SystemClock.uptimeMillis() < hintUntil) {
            canvas.drawText(h, width / 2f, counterPaint.textSize + hintPaint.textSize + 44f, hintPaint)
        }
    }

    private companion object {
        const val HINT_DURATION_MS = 2500L
        const val ENTER_ANIM_MS = 200L
    }
}
