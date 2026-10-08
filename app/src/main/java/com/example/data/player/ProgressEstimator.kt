package com.example.data.player

import com.example.data.model.BookDetailDto

/**
 * Computes whole-book progress for both normal sources and RuTracker.
 *
 * When chapter durations are complete, the value is exact. RuTracker currently
 * may expose chapters with duration_seconds=0, so in that case we estimate the
 * whole-book position from the chapter ordinal plus the live fraction of the
 * current chapter. As soon as backend durations become available the exact
 * branch wins automatically.
 */
fun estimateOverallProgressPercent(
    book: BookDetailDto,
    chapterIndex: Int,
    positionMs: Long,
    currentDurationMs: Long,
    completed: Boolean = false
): Double {
    if (completed) return 100.0
    if (book.chapters.isEmpty()) return book.progressPercent.coerceIn(0.0, 100.0)

    val index = chapterIndex.coerceIn(0, book.chapters.lastIndex)
    var beforeDurationMs = 0L
    var beforeDurationsKnown = true
    var summedDurationMs = 0L
    var allDurationsKnown = true

    book.chapters.forEachIndexed { chapter, item ->
        val durationMs = item.durationSeconds.coerceAtLeast(0L) * 1_000L
        summedDurationMs += durationMs
        if (durationMs <= 0L) allDurationsKnown = false
        if (chapter < index) {
            beforeDurationMs += durationMs
            if (durationMs <= 0L) beforeDurationsKnown = false
        }
    }

    val declaredTotalMs = book.durationSeconds.coerceAtLeast(0L) * 1_000L
    val totalMs = when {
        declaredTotalMs > 0L -> declaredTotalMs
        allDurationsKnown -> summedDurationMs
        book.chapters.size == 1 && currentDurationMs > 0L -> currentDurationMs
        else -> 0L
    }
    val currentChapterDurationMs =
        book.chapters[index].durationSeconds.coerceAtLeast(0L) * 1_000L

    return calculateOverallProgressPercent(
        chapterCount = book.chapters.size,
        chapterIndex = index,
        beforeDurationMs = beforeDurationMs,
        beforeDurationsKnown = beforeDurationsKnown,
        currentChapterDurationMs = currentChapterDurationMs,
        totalDurationMs = totalMs,
        positionMs = positionMs,
        currentDurationMs = currentDurationMs,
        untouchedFallbackPercent = book.progressPercent,
    )
}

/** Same estimator for playback owners that already have chapter metadata, not a full DTO. */
internal fun estimateOverallProgressPercent(
    chapterDurationsMs: List<Long>,
    totalDurationMs: Long,
    storedProgressPercent: Double,
    chapterIndex: Int,
    positionMs: Long,
    currentDurationMs: Long,
    completed: Boolean = false,
): Double {
    if (completed) return 100.0
    if (chapterDurationsMs.isEmpty()) return storedProgressPercent.coerceIn(0.0, 100.0)

    val durations = chapterDurationsMs.map { it.coerceAtLeast(0L) }
    val index = chapterIndex.coerceIn(0, durations.lastIndex)
    val knownBefore = durations.take(index)
    val totalMs = when {
        totalDurationMs > 0L -> totalDurationMs
        durations.all { it > 0L } -> durations.sum()
        durations.size == 1 && currentDurationMs > 0L -> currentDurationMs
        else -> 0L
    }

    return calculateOverallProgressPercent(
        chapterCount = durations.size,
        chapterIndex = index,
        beforeDurationMs = knownBefore.sum(),
        beforeDurationsKnown = knownBefore.all { it > 0L },
        currentChapterDurationMs = durations[index],
        totalDurationMs = totalMs,
        positionMs = positionMs,
        currentDurationMs = currentDurationMs,
        untouchedFallbackPercent = storedProgressPercent,
    )
}

/**
 * Shared exact/ordinal progress arithmetic.
 *
 * Callers own index validation/clamping and metadata lookup. That keeps
 * PlaybackTimelineSnapshot O(1) while the DTO estimator can retain its stored
 * progress fallback for an untouched unknown-duration source.
 */
internal fun calculateOverallProgressPercent(
    chapterCount: Int,
    chapterIndex: Int,
    beforeDurationMs: Long,
    beforeDurationsKnown: Boolean,
    currentChapterDurationMs: Long,
    totalDurationMs: Long,
    positionMs: Long,
    currentDurationMs: Long,
    untouchedFallbackPercent: Double? = null,
): Double {
    require(chapterCount > 0)
    require(chapterIndex in 0 until chapterCount)

    if (totalDurationMs > 0L && beforeDurationsKnown) {
        val currentDuration = currentChapterDurationMs.takeIf { it > 0L }
            ?: currentDurationMs.coerceAtLeast(0L)
        val safePosition = if (currentDuration > 0L) {
            positionMs.coerceIn(0L, currentDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        return ((beforeDurationMs + safePosition).toDouble() * 100.0 / totalDurationMs.toDouble())
            .coerceIn(0.0, 100.0)
    }

    val liveDuration = currentDurationMs.takeIf { it > 0L }
        ?: currentChapterDurationMs.takeIf { it > 0L }
        ?: 0L
    val currentFraction = if (liveDuration > 0L) {
        positionMs.coerceIn(0L, liveDuration).toDouble() / liveDuration.toDouble()
    } else {
        0.0
    }
    val estimated = (
        (chapterIndex.toDouble() + currentFraction) /
            chapterCount.toDouble() *
            100.0
        ).coerceIn(0.0, 100.0)

    if (
        untouchedFallbackPercent != null &&
        chapterIndex == 0 &&
        liveDuration == 0L &&
        positionMs <= 0L
    ) {
        return untouchedFallbackPercent.coerceIn(0.0, 100.0)
    }
    return estimated
}
