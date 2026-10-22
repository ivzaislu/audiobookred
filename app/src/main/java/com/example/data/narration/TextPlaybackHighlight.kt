package com.example.data.narration

import com.example.data.textbook.TextFragmentEntity

/** Fragment-level only. No word timing is inferred from a playback clock. */
internal data class TextPlaybackHighlight(
    val bookId: String, val chapterId: String, val fragmentId: String, val approximate: Boolean,
)

internal fun resolveTextPlaybackHighlight(
    bookId: String,
    chapterId: String,
    fragments: List<TextFragmentEntity>,
    liveFragmentId: String?,
    positionMs: Long,
    durationMs: Long,
    map: TextAudioSyncMap?,
): TextPlaybackHighlight? {
    val id = if (liveFragmentId != null) liveFragmentId else
        map?.textAt(positionMs)?.first ?: approximateAudioFragment(fragments, positionMs, durationMs)?.first
    if (id == null || fragments.none { it.id == id && it.chapterId == chapterId }) return null
    return TextPlaybackHighlight(bookId, chapterId, id, liveFragmentId == null && map == null)
}
