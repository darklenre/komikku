package eu.kanade.tachiyomi.ui.reader.bubble

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Stable per-page key for caching bubble-detection results. [InsertPage] (the second half of a
 * split double page) shares its parent's chapter id and index, so it needs its own suffix or the
 * two halves would collide and the second one would reuse the first half's bubble rects.
 */
fun bubbleKeyFor(page: ReaderPage): String =
    "${page.chapter.chapter.id}:${page.index}${if (page is InsertPage) ":insert" else ""}"
