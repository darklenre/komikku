package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Per-manga override for Bubble Zoom, packed into `Manga.viewerFlags` (bits 6-7, see [MASK]).
 * [DEFAULT] follows the global `bubble_zoom` preference; the others force it on or off for one series.
 */
enum class BubbleZoomOverride(val stringRes: StringResource, val flagValue: Int) {
    DEFAULT(MR.strings.label_default, 0x00000000),
    ENABLED(MR.strings.on, 0x00000040),
    DISABLED(MR.strings.off, 0x00000080),
    ;

    /** null = follow the global preference; true/false = force. */
    val forced: Boolean?
        get() = when (this) {
            DEFAULT -> null
            ENABLED -> true
            DISABLED -> false
        }

    companion object {
        const val MASK = 0x000000C0

        fun fromPreference(preference: Int?): BubbleZoomOverride =
            entries.find { it.flagValue == (preference ?: 0) } ?: DEFAULT
    }
}
