package com.example.data.player

import com.example.data.model.BookDetailDto

internal data class PlaybackRecoveryPoint(
    val chapterIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
)

internal fun sanitizePlaybackSpeed(
    speed: Float,
    fallbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
): Float {
    val safeFallback = fallbackSpeed
        .takeIf { it.isFinite() }
        ?.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        ?: DEFAULT_PLAYBACK_SPEED
    return speed
        .takeIf { it.isFinite() }
        ?.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        ?: safeFallback
}

/**
 * Resolve the local UI/rebuild point for an idle or empty Media3 session.
 * The durable snapshot wins when present; otherwise the caller's current UI state
 * is preserved and clamped to the recovered book.
 */
internal fun resolvePlaybackRecoveryPoint(
    book: BookDetailDto,
    snapshot: PlaybackResumeStore.Snapshot?,
    fallbackChapterIndex: Int,
    fallbackPositionMs: Long,
    fallbackSpeed: Float,
): PlaybackRecoveryPoint? {
    if (book.chapters.isEmpty()) return null

    val snapshotChapterId = snapshot?.chapterId?.takeIf { it.isNotBlank() }
    val snapshotChapterIndex = snapshotChapterId
        ?.let { id -> book.chapters.indexOfFirst { it.id == id } }
    if (snapshotChapterId != null && snapshotChapterIndex == -1) return null

    val chapterIndex = when {
        snapshotChapterIndex != null -> snapshotChapterIndex
        snapshot != null -> snapshot.chapterIndex.coerceIn(0, book.chapters.lastIndex)
        else -> fallbackChapterIndex.coerceIn(0, book.chapters.lastIndex)
    }
    val positionMs = (snapshot?.positionMs ?: fallbackPositionMs).coerceAtLeast(0L)
    val durationMs = book.chapters[chapterIndex]
        .durationSeconds
        .coerceAtLeast(0L)
        .times(1_000L)
    val speed = sanitizePlaybackSpeed(
        speed = snapshot?.speed ?: fallbackSpeed,
        fallbackSpeed = fallbackSpeed,
    )

    return PlaybackRecoveryPoint(
        chapterIndex = chapterIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
    )
}

private const val MIN_PLAYBACK_SPEED = 0.5f
private const val MAX_PLAYBACK_SPEED = 3.0f
private const val DEFAULT_PLAYBACK_SPEED = 1.0f
