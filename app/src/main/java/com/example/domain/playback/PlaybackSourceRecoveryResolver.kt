package com.example.domain.playback

internal data class PlaybackSourceRecoveryPoint(val chapterIndex: Int, val positionMs: Long)

/**
 * An automatic URL refresh repairs the current stream, not the user's resume
 * intent. Never apply completed-book restart, near-end rewind or ordinal fallback.
 * TorrServe chapters have unknown metadata durations and stable torrent/file IDs.
 */
internal fun resolvePlaybackSourceRecoveryPoint(
    chapterIds: List<String>,
    chapterId: String,
    positionMs: Long,
): PlaybackSourceRecoveryPoint? {
    if (chapterId.isBlank()) return null
    val index = chapterIds.indexOf(chapterId)
    if (index < 0) return null
    return PlaybackSourceRecoveryPoint(index, positionMs.coerceAtLeast(0L))
}

/** System resumption keeps the captured checkpoint; completion never means replay. */
internal fun resolvePlaybackResumptionPoint(
    chapterIds: List<String>,
    chapterId: String?,
    chapterIndex: Int,
    positionMs: Long,
): PlaybackSourceRecoveryPoint? {
    if (!chapterId.isNullOrBlank()) {
        return resolvePlaybackSourceRecoveryPoint(chapterIds, chapterId, positionMs)
    }
    if (chapterIndex !in chapterIds.indices) return null
    return PlaybackSourceRecoveryPoint(chapterIndex, positionMs.coerceAtLeast(0L))
}
