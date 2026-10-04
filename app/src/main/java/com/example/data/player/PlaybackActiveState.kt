package com.example.data.player

import androidx.media3.common.Player
import com.example.data.model.BookDetailDto

internal data class PlaybackActivePoint(
    val chapterIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
)

internal fun shouldUsePlaybackResumeFallback(
    playbackState: Int,
    isPlaying: Boolean,
    positionMs: Long,
): Boolean = !isPlaying &&
    (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) &&
    positionMs.coerceAtLeast(0L) == 0L

internal fun resolvePlaybackActivePoint(
    book: BookDetailDto?,
    currentChapterIndex: Int,
    currentPositionMs: Long,
    currentDurationMs: Long,
    resume: PlaybackResumeStore.Snapshot?,
): PlaybackActivePoint {
    var chapterIndex = currentChapterIndex.coerceAtLeast(0)
    var positionMs = currentPositionMs.coerceAtLeast(0L)
    var durationMs = currentDurationMs.takeIf { it > 0L } ?: 0L

    if (book != null && resume != null) {
        chapterIndex = resume.chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))
        positionMs = resume.positionMs.coerceAtLeast(0L)
    }
    if (durationMs <= 0L && book != null) {
        durationMs = book.chapters.getOrNull(chapterIndex)
            ?.durationSeconds
            ?.coerceAtLeast(0L)
            ?.times(1_000L)
            ?: 0L
    }

    return PlaybackActivePoint(
        chapterIndex = chapterIndex,
        positionMs = positionMs,
        durationMs = durationMs,
    )
}
