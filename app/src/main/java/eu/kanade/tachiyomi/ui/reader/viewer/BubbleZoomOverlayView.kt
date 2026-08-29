package eu.kanade.tachiyomi.ui.reader.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import kotlin.math.abs

/**
 * Full-screen overlay that is active only while "Bubble Zoom" mode is on. While active it consumes
 * every touch so the underlying viewer never sees it:
 *  - swipe (horizontal on Pager, vertical on Webtoon) -> next / previous bubble
 *  - single tap -> exit
 *  - anything else -> swallowed (no manual pinch / pan)
 *
 * Swiping past the first / last bubble asks the host (via [onEdge]) to turn the page and re-enter
 * on the first / last bubble there; if it can't, the current bubble stays selected.
 */
@SuppressLint("ClickableViewAccessibility")
class BubbleZoomOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var target: ReaderPageImageView? = null
    private var bubbles: List<RectF> = emptyList()
    private var index = 0
    private var onExitListener: (() -> Unit)? = null
    private var onEdgeListener: ((forward: Boolean) -> Boolean)? = null

    private var hint: String? = null
    private var hintUntil = 0L

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

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
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

    fun enter(
        target: ReaderPageImageView,
        bubbles: List<RectF>,
        startIndex: Int,
        hint: String? = null,
        onEdge: (forward: Boolean) -> Boolean = { false },
        onExit: () -> Unit = {},
    ) {
        if (bubbles.isEmpty()) return
        this.target = target
        this.bubbles = bubbles
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
        target?.resetZoom(animate = true)
        val cb = onExitListener
        target = null
        bubbles = emptyList()
        onEdgeListener = null
        onExitListener = null
        hint = null
        isVisible = false
        cb?.invoke()
    }

    private fun next() {
        if (index < bubbles.lastIndex) {
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
        val rect = bubbles.getOrNull(index) ?: return
        // Post so this runs after the current touch gesture is delivered; otherwise the trailing
        // events of the long-press still reach the SubsamplingScaleImageView and cancel the animation.
        t.post { t.focusOnRect(rect) }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!isActive) return
        if (bubbles.size > 1) {
            canvas.drawText(
                "${index + 1} / ${bubbles.size}",
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
    }
}
