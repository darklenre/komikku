package eu.kanade.tachiyomi.ui.reader.bubble

import kotlin.math.abs

enum class ReadingDirection { LTR, RTL, VERTICAL }

/**
 * Orders bubbles into reading sequence:
 *  1. group into horizontal bands using a threshold proportional to the median bubble height,
 *  2. bands top-to-bottom,
 *  3. within a band, left-to-right (LTR/VERTICAL) or right-to-left (RTL).
 *
 * Works on normalised rects; ordering is scale-invariant.
 */
object BubbleReadingOrder {

    fun sort(bubbles: List<Bubble>, direction: ReadingDirection, bandFactor: Float = 0.5f): List<Bubble> {
        if (bubbles.size <= 1) return bubbles

        val heights = bubbles.map { it.rect.height() }.sorted()
        val medianH = heights[heights.size / 2].coerceAtLeast(1e-4f)
        val bandThreshold = medianH * bandFactor

        val byTop = bubbles.sortedBy { it.rect.centerY() }
        val bands = mutableListOf<MutableList<Bubble>>()
        var bandMeanY = Float.NaN
        for (b in byTop) {
            val cy = b.rect.centerY()
            if (bands.isEmpty() || abs(cy - bandMeanY) > bandThreshold) {
                bands.add(mutableListOf(b))
                bandMeanY = cy
            } else {
                val band = bands.last()
                band.add(b)
                bandMeanY = band.sumOf { it.rect.centerY().toDouble() }.toFloat() / band.size
            }
        }

        val within = when (direction) {
            ReadingDirection.RTL -> compareByDescending<Bubble> { it.rect.centerX() }
            else -> compareBy<Bubble> { it.rect.centerX() }
        }
        return bands.flatMap { it.sortedWith(within) }
    }
}
