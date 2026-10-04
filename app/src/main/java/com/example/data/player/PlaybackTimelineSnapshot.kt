package com.example.data.player

internal data class PlaybackTimelineItem(
    val bookId: String,
    val chapterDurationMs: Long,
    val bookDurationMs: Long,
)

/**
 * Immutable O(1) progress index for one playback queue.
 *
 * Building the snapshot is O(chapters) and happens when Media3 changes the
 * timeline. Checkpoints then use prefix sums instead of scanning every media
 * item again.
 */
internal class PlaybackTimelineSnapshot private constructor(
    val bookId: String,
    private val chapterDurationsMs: LongArray,
    private val prefixDurationsMs: LongArray,
    private val firstUnknownDurationIndex: Int,
    private val metadataBookDurationMs: Long,
) {
    val chapterCount: Int
        get() = chapterDurationsMs.size

    fun progressPercent(
        chapterIndex: Int,
        positionMs: Long,
        currentDurationMs: Long,
        completed: Boolean = false,
    ): Double? {
        if (completed) return 100.0
        if (chapterIndex !in chapterDurationsMs.indices) return null

        val index = chapterIndex
        val beforeIsKnown = index <= firstUnknownDurationIndex
        val allDurationsKnown = firstUnknownDurationIndex == chapterDurationsMs.size
        val totalDurationMs = when {
            metadataBookDurationMs > 0L -> metadataBookDurationMs
            allDurationsKnown -> prefixDurationsMs.last()
            chapterDurationsMs.size == 1 && currentDurationMs > 0L -> currentDurationMs
            else -> 0L
        }

        return calculateOverallProgressPercent(
            chapterCount = chapterDurationsMs.size,
            chapterIndex = index,
            beforeDurationMs = prefixDurationsMs[index],
            beforeDurationsKnown = beforeIsKnown,
            currentChapterDurationMs = chapterDurationsMs[index],
            totalDurationMs = totalDurationMs,
            positionMs = positionMs,
            currentDurationMs = currentDurationMs,
        )
    }

    companion object {
        fun from(items: List<PlaybackTimelineItem>): PlaybackTimelineSnapshot? {
            if (items.isEmpty()) return null

            val bookId = items.first().bookId.takeIf(String::isNotBlank) ?: return null
            if (items.any { it.bookId != bookId }) return null

            val metadataBookDurationMs = items.first().bookDurationMs.coerceAtLeast(0L)
            if (
                items.any {
                    it.bookDurationMs.coerceAtLeast(0L) != metadataBookDurationMs
                }
            ) {
                return null
            }

            val durations = LongArray(items.size)
            val prefix = LongArray(items.size + 1)
            var firstUnknown = items.size
            for (index in items.indices) {
                val duration = items[index].chapterDurationMs.coerceAtLeast(0L)
                durations[index] = duration
                if (duration <= 0L && firstUnknown == items.size) {
                    firstUnknown = index
                }
                prefix[index + 1] = prefix[index] + duration
            }

            return PlaybackTimelineSnapshot(
                bookId = bookId,
                chapterDurationsMs = durations,
                prefixDurationsMs = prefix,
                firstUnknownDurationIndex = firstUnknown,
                metadataBookDurationMs = metadataBookDurationMs,
            )
        }
    }
}
