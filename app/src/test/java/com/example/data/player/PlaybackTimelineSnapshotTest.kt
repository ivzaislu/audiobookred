package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaybackTimelineSnapshotTest {
    @Test
    fun cachedTimelineMatchesExactProgressEstimator() {
        val snapshot = requireNotNull(
            PlaybackTimelineSnapshot.from(
                listOf(
                    PlaybackTimelineItem("book", 100_000L, 300_000L),
                    PlaybackTimelineItem("book", 100_000L, 300_000L),
                    PlaybackTimelineItem("book", 100_000L, 300_000L),
                )
            )
        )

        val cached = snapshot.progressPercent(
            chapterIndex = 1,
            positionMs = 50_000L,
            currentDurationMs = 100_000L,
        )
        val baseline = estimateOverallProgressPercent(
            chapterDurationsMs = listOf(100_000L, 100_000L, 100_000L),
            totalDurationMs = 300_000L,
            storedProgressPercent = 0.0,
            chapterIndex = 1,
            positionMs = 50_000L,
            currentDurationMs = 100_000L,
        )

        assertEquals(baseline, cached!!, 0.0001)
    }

    @Test
    fun cachedTimelineMatchesOrdinalFallbackForUnknownDurations() {
        val snapshot = requireNotNull(
            PlaybackTimelineSnapshot.from(
                List(4) { PlaybackTimelineItem("book", 0L, 0L) }
            )
        )

        val cached = snapshot.progressPercent(
            chapterIndex = 1,
            positionMs = 30_000L,
            currentDurationMs = 60_000L,
        )

        assertEquals(37.5, cached!!, 0.0001)
    }

    @Test
    fun knownPrefixCanUseLiveDurationForUnknownCurrentChapter() {
        val snapshot = requireNotNull(
            PlaybackTimelineSnapshot.from(
                listOf(
                    PlaybackTimelineItem("book", 100_000L, 300_000L),
                    PlaybackTimelineItem("book", 0L, 300_000L),
                    PlaybackTimelineItem("book", 100_000L, 300_000L),
                )
            )
        )

        val cached = snapshot.progressPercent(
            chapterIndex = 1,
            positionMs = 50_000L,
            currentDurationMs = 100_000L,
        )

        assertEquals(50.0, cached!!, 0.0001)
    }

    @Test
    fun completionDoesNotNeedAnotherTimelineScan() {
        val snapshot = requireNotNull(
            PlaybackTimelineSnapshot.from(
                listOf(PlaybackTimelineItem("book", 0L, 0L))
            )
        )

        assertEquals(
            100.0,
            snapshot.progressPercent(
                chapterIndex = 0,
                positionMs = 0L,
                currentDurationMs = 0L,
                completed = true,
            )!!,
            0.0001,
        )
    }

    @Test
    fun cachedTimelinePreservesZeroProgressForUntouchedUnknownSource() {
        val snapshot = requireNotNull(
            PlaybackTimelineSnapshot.from(
                List(3) { PlaybackTimelineItem("book", 0L, 0L) }
            )
        )

        assertEquals(
            0.0,
            snapshot.progressPercent(
                chapterIndex = 0,
                positionMs = 0L,
                currentDurationMs = 0L,
            )!!,
            0.0001,
        )

        val estimatorWithStoredFallback = estimateOverallProgressPercent(
            chapterDurationsMs = listOf(0L, 0L, 0L),
            totalDurationMs = 0L,
            storedProgressPercent = 21.0,
            chapterIndex = 0,
            positionMs = 0L,
            currentDurationMs = 0L,
        )
        assertEquals(21.0, estimatorWithStoredFallback, 0.0001)
    }

    @Test
    fun mixedBooksCannotShareOneTimelineSnapshot() {
        assertNull(
            PlaybackTimelineSnapshot.from(
                listOf(
                    PlaybackTimelineItem("book-a", 10_000L, 20_000L),
                    PlaybackTimelineItem("book-b", 10_000L, 20_000L),
                )
            )
        )
    }

    @Test
    fun inconsistentBookDurationMetadataIsRejected() {
        assertNull(
            PlaybackTimelineSnapshot.from(
                listOf(
                    PlaybackTimelineItem("book", 10_000L, 20_000L),
                    PlaybackTimelineItem("book", 10_000L, 30_000L),
                )
            )
        )
    }

    @Test
    fun chapterOutsideSnapshotReturnsNull() {
        val snapshot = PlaybackTimelineSnapshot.from(
            listOf(PlaybackTimelineItem("book", 10_000L, 10_000L))
        )
        assertNotNull(snapshot)
        assertNull(snapshot!!.progressPercent(1, 0L, 0L))
    }
}
