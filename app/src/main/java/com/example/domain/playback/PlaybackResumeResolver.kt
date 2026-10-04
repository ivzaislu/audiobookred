package com.example.domain.playback

internal data class PlaybackResumeCandidate(
    val chapterId: String?,
    val chapterIndex: Int,
    val positionMs: Long,
    val completed: Boolean = false,
    val preferOverPersisted: Boolean = false,
)

internal data class PlaybackResumeResolution(
    val chapterIndex: Int,
    val positionMs: Long,
    val clearResumeStore: Boolean = false,
    val persistedChapterIndexToMirror: Int? = null,
)

/**
 * Pure resume-point selection shared by playback preparation and JVM tests.
 *
 * `persisted` is the durable Room checkpoint. `immediate` is the source-aware
 * SharedPreferences checkpoint written directly by playback. The latter may be
 * marked to take precedence when it represents a newer foreground checkpoint.
 */
internal fun resolvePlaybackResumePoint(
    chapterIds: List<String>,
    chapterDurationsMs: List<Long>,
    sourceVariantCount: Int,
    persisted: PlaybackResumeCandidate?,
    immediate: PlaybackResumeCandidate?,
): PlaybackResumeResolution {
    if (chapterIds.isEmpty()) return PlaybackResumeResolution(0, 0L)

    fun indexFor(candidate: PlaybackResumeCandidate?, allowIndexFallback: Boolean): Int {
        candidate ?: return -1
        val chapterId = candidate.chapterId
        if (chapterId != null) {
            val exactIndex = chapterIds.indexOf(chapterId)
            if (exactIndex >= 0) return exactIndex
        }
        return if (allowIndexFallback) {
            candidate.chapterIndex.coerceIn(0, chapterIds.lastIndex)
        } else {
            -1
        }
    }

    val persistedIndex = indexFor(persisted, allowIndexFallback = sourceVariantCount <= 1)
    val persistedPosition = if (persistedIndex >= 0) persisted?.positionMs?.coerceAtLeast(0L) ?: 0L else 0L
    val immediateIndex = indexFor(immediate, allowIndexFallback = true)
    val immediatePosition = if (immediateIndex >= 0) immediate?.positionMs?.coerceAtLeast(0L) ?: 0L else 0L

    if (persisted?.completed == true && persistedIndex >= 0 && immediate?.preferOverPersisted != true) {
        return PlaybackResumeResolution(
            chapterIndex = 0,
            positionMs = 0L,
            clearResumeStore = true,
        )
    }

    val useImmediate = when {
        immediateIndex < 0 -> false
        immediate?.preferOverPersisted == true -> true
        persistedIndex >= 0 -> false
        else -> true
    }
    var index = when {
        useImmediate -> immediateIndex
        persistedIndex >= 0 -> persistedIndex
        immediateIndex >= 0 -> immediateIndex
        else -> 0
    }
    var position = when {
        useImmediate -> immediatePosition
        persistedIndex >= 0 -> persistedPosition
        immediateIndex >= 0 -> immediatePosition
        else -> 0L
    }
    val persistedChapterIndexToMirror = persistedIndex.takeIf {
        !useImmediate && it >= 0 && persisted != null
    }

    val durationMs = chapterDurationsMs.getOrNull(index)?.coerceAtLeast(0L) ?: 0L
    if (durationMs > 0L && position >= durationMs - NEAR_END_TOLERANCE_MS) {
        if (index < chapterIds.lastIndex) {
            index += 1
            position = 0L
        } else {
            position = (durationMs - LAST_CHAPTER_REWIND_MS).coerceAtLeast(0L)
        }
    }

    val normalizedDurationMs = chapterDurationsMs.getOrNull(index)?.coerceAtLeast(0L) ?: 0L
    return PlaybackResumeResolution(
        chapterIndex = index,
        positionMs = normalizePlaybackStartPosition(normalizedDurationMs, position),
        persistedChapterIndexToMirror = persistedChapterIndexToMirror,
    )
}

internal fun resolveExplicitChapterStartPosition(
    requestedChapterId: String?,
    requestedChapterIndex: Int,
    chapterDurationMs: Long,
    sourceVariantCount: Int,
    persisted: PlaybackResumeCandidate?,
    immediate: PlaybackResumeCandidate?,
): Long {
    val persistedMatches = persisted != null && (
        persisted.chapterId == requestedChapterId ||
            (persisted.chapterId == null &&
                sourceVariantCount <= 1 &&
                persisted.chapterIndex == requestedChapterIndex)
        )
    val immediateMatches = immediate != null && (
        immediate.chapterId == requestedChapterId ||
            (immediate.chapterId == null && immediate.chapterIndex == requestedChapterIndex)
        )
    val startPosition = when {
        immediateMatches && immediate?.preferOverPersisted == true -> immediate?.positionMs?.coerceAtLeast(0L) ?: 0L
        persistedMatches -> persisted?.positionMs?.coerceAtLeast(0L) ?: 0L
        immediateMatches -> immediate?.positionMs?.coerceAtLeast(0L) ?: 0L
        else -> 0L
    }
    return normalizePlaybackStartPosition(chapterDurationMs, startPosition)
}

internal fun normalizePlaybackStartPosition(durationMs: Long, positionMs: Long): Long {
    if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
    val position = positionMs.coerceAtLeast(0L)
    if (position >= durationMs - NEAR_END_TOLERANCE_MS) {
        return (durationMs - LAST_CHAPTER_REWIND_MS).coerceAtLeast(0L)
    }
    return position.coerceAtMost((durationMs - START_POSITION_END_GUARD_MS).coerceAtLeast(0L))
}

private const val NEAR_END_TOLERANCE_MS = 1_500L
private const val LAST_CHAPTER_REWIND_MS = 5_000L
private const val START_POSITION_END_GUARD_MS = 1_000L
