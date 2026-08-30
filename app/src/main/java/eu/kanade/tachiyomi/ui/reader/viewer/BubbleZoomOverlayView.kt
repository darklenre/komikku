package eu.kanade.tachiyomi.ui.reader.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import eu.kanade.tachiyomi.ui.reader.bubble.BubbleCutout
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
    private val extractedCutouts: MutableMap<Int, BubbleCutout?> = mutableMapOf()
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

    /** FLOATING exit animation: the cutout shrinks + fades to [exitTo] (bubble's on-page rect) then tears down. */
    private var exiting = false
    private var exitAnimStart = 0L
    private var exitTo: RectF? = null

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
        color = Color.argb(BACKDROP_ALPHA, 0, 0, 0) // Dimmed 82% black backdrop
    }

    /** Sticker outline, stroked live from [BubbleCutout.outline] so the edge stays crisp at any scale. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val outlineMatrix = Matrix()
    private val outlinePath = Path()
    private val unitRect = RectF(0f, 0f, 1f, 1f)

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                exit(animate = true)
                return true
            }

            // While zoomed, a double tap must only exit (never fall through to the page's 2x zoom).
            override fun onDoubleTap(e: MotionEvent): Boolean {
                exit(animate = true)
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
        exiting = false
        exitTo = null
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
        this.extractedCutouts.clear()
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

    /**
     * Leaves Bubble Zoom. [animate] plays the reverse of the entry animation for a FLOATING cutout
     * (shrinks + fades back to the bubble's on-page position) before tearing down; callers on a
     * page-turn / viewer teardown leave it false so the exit is immediate.
     */
    fun exit(animate: Boolean = false) {
        if (!isActive || exiting) return
        if (animate && zoomStyle == ZoomStyle.FLOATING) {
            val to = target?.let { t ->
                bubbleRects.getOrNull(index)?.let { t.sourceToViewRect(it.scaledToPx()) }
            }
            if (to != null && extractedCutouts[index] != null) {
                exitTo = to
                exitAnimStart = SystemClock.uptimeMillis()
                exiting = true
                extractJob?.cancel()
                prefetchJob?.cancel()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                postInvalidateOnAnimation()
                return
            }
        }
        teardown()
    }

    private fun teardown() {
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
        extractedCutouts.values.forEach { it?.bitmap?.recycle() }
        extractedCutouts.clear()
        onEdgeListener = null
        onExitListener = null
        hint = null
        enterAnimFrom = null
        exiting = false
        exitTo = null
        isVisible = false
        cb?.invoke()
    }

    private fun next() {
        if (exiting) return
        if (index < rawBubbles.lastIndex) {
            index++
            step()
        } else {
            onEdgeListener?.invoke(true)
        }
    }

    private fun prev() {
        if (exiting) return
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
        if (extractedCutouts.containsKey(idx)) return
        val p = currentPage ?: return
        val bubble = rawBubbles.getOrNull(idx) ?: return
        val t = target

        extractJob?.cancel()
        extractJob = extractScope.launch {
            val cutout = extractCutout(p, bubble, t)
            withContext(Dispatchers.Main) {
                extractedCutouts[idx] = cutout
                invalidate()
            }
        }
    }

    private fun prefetchExtraction(idx: Int) {
        if (extractedCutouts.containsKey(idx)) return
        val p = currentPage ?: return
        val bubble = rawBubbles.getOrNull(idx) ?: return
        val t = target

        prefetchJob?.cancel()
        prefetchJob = extractScope.launch {
            val cutout = extractCutout(p, bubble, t)
            withContext(Dispatchers.Main) {
                if (isActive && !extractedCutouts.containsKey(idx)) {
                    extractedCutouts[idx] = cutout
                } else {
                    cutout?.bitmap?.recycle()
                }
            }
        }
    }

    /** Bubble rects are normalised; [BubbleExtractor] takes them as-is, the crop fallback needs px. */
    private fun extractCutout(p: ReaderPage, bubble: Bubble, t: ReaderPageImageView?): BubbleCutout? =
        BubbleExtractor.extractBubble(p, bubble, sourceSize.takeIf { it.width > 0 && it.height > 0 })
            ?: t?.cropSourceRect(bubble.rect.scaledToPx())?.let { BubbleCutout(it) }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        extractJob?.cancel()
        prefetchJob?.cancel()
        // If we're detached mid exit-animation the deferred teardown won't run — do it now.
        if (exiting) teardown()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        // Swallow input during the exit animation so a stray tap can't re-enter / double-fire.
        if (exiting) return true
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Linear interpolation between two rects. */
    private fun lerpRect(a: RectF, b: RectF, f: Float) = RectF(
        a.left + (b.left - a.left) * f,
        a.top + (b.top - a.top) * f,
        a.right + (b.right - a.right) * f,
        a.bottom + (b.bottom - a.bottom) * f,
    )

    /** Strokes the live vector outline (if any) then the shaped bitmap into [rect] at [alpha] (0..255). */
    private fun drawCutout(canvas: Canvas, cutout: BubbleCutout, rect: RectF, alpha: Int) {
        val path = cutout.outline
        if (path != null && cutout.outlineFraction > 0f) {
            outlineMatrix.setRectToRect(unitRect, rect, Matrix.ScaleToFit.FILL)
            path.transform(outlineMatrix, outlinePath)
            outlinePaint.alpha = alpha
            outlinePaint.strokeWidth = cutout.outlineFraction * minOf(rect.width(), rect.height()) * 2f
            canvas.drawPath(outlinePath, outlinePaint)
            outlinePaint.alpha = 255
        }
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(cutout.bitmap, null, rect, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        if (!isActive) return

        if (zoomStyle == ZoomStyle.FLOATING) {
            val exitFrac = if (exiting) {
                ((SystemClock.uptimeMillis() - exitAnimStart) / EXIT_ANIM_MS.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            if (backdropEnabled) {
                // Draw dimmed lightbox backdrop (fading out as the cutout leaves).
                backdropPaint.alpha = (BACKDROP_ALPHA * (1f - exitFrac)).toInt().coerceIn(0, BACKDROP_ALPHA)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
                backdropPaint.alpha = BACKDROP_ALPHA
            }

            // Draw centered floating extracted bubble maximized on screen
            val cutout = extractedCutouts[index]
            val bmp = cutout?.bitmap
            if (cutout != null && bmp != null && bmp.width > 0 && bmp.height > 0) {
                val maxW = width * 0.92f
                val maxH = height * 0.85f
                val scale = minOf(maxW / bmp.width, maxH / bmp.height, 4.0f).coerceAtLeast(1.0f)
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                // Tail-aware framing: put the bubble *body* centre on the screen centre, kept on-screen.
                val offX = cutout.bodyOffsetX.coerceIn(-0.12f, 0.12f)
                val offY = cutout.bodyOffsetY.coerceIn(-0.12f, 0.12f)
                val drawL = ((width - drawW) / 2f - offX * drawW).coerceIn(0f, (width - drawW).coerceAtLeast(0f))
                val drawT = ((height - drawH) / 2f - offY * drawH).coerceIn(0f, (height - drawH).coerceAtLeast(0f))
                val finalRect = RectF(drawL, drawT, drawL + drawW, drawT + drawH)

                val exitToRect = exitTo
                val enterFrom = enterAnimFrom
                when {
                    exiting -> {
                        if (exitToRect != null) {
                            val e = exitFrac * exitFrac // ease-in: accelerate back toward the page
                            drawCutout(
                                canvas,
                                cutout,
                                lerpRect(finalRect, exitToRect, e),
                                ((1f - exitFrac) * 255f).toInt().coerceIn(0, 255),
                            )
                        }
                        if (exitFrac >= 1f) post { teardown() } else postInvalidateOnAnimation()
                        return
                    }
                    enterFrom != null -> {
                        if (enterAnimStart == 0L) enterAnimStart = SystemClock.uptimeMillis()
                        val lin = ((SystemClock.uptimeMillis() - enterAnimStart) / ENTER_ANIM_MS.toFloat())
                            .coerceIn(0f, 1f)
                        val f = 1f - (1f - lin) * (1f - lin) // ease-out quad
                        drawCutout(canvas, cutout, lerpRect(enterFrom, finalRect, f), (f * 255f).toInt().coerceIn(0, 255))
                        if (lin < 1f) postInvalidateOnAnimation() else enterAnimFrom = null
                    }
                    else -> drawCutout(canvas, cutout, finalRect, 255)
                }
            } else if (exiting) {
                post { teardown() }
                return
            } else if (!extractedCutouts.containsKey(index)) {
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
        const val EXIT_ANIM_MS = 160L
        const val BACKDROP_ALPHA = 210
    }
}
