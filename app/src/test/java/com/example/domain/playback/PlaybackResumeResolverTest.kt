package com.example.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumeResolverTest {
    private val chapters = listOf("chapter-0", "chapter-1", "chapter-2")
    private val durations = listOf(100_000L, 100_000L, 100_000L)

    @Test
    fun completedBazaStyleBookWithPreferredLocalCheckpointDoesNotResolveToFirstChapter() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = listOf(0L, 0L, 0L),
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-2", 2, 100_000L, completed = true),
            immediate = PlaybackResumeCandidate("chapter-2", 2, 100_000L, preferOverPersisted = true),
        )
        assertEquals(2, result.chapterIndex)
        assertEquals(100_000L, result.positionMs)
        assertFalse(result.clearResumeStore)
    }

    @Test
    fun immediateOverrideWinsOverPersisted() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-0", 0, 20_000L),
            immediate = PlaybackResumeCandidate("chapter-1", 1, 30_000L, preferOverPersisted = true),
        )

        assertEquals(1, result.chapterIndex)
        assertEquals(30_000L, result.positionMs)
        assertNull(result.persistedChapterIndexToMirror)
    }

    @Test
    fun singleSourcePersistedFallsBackToIndexWhenChapterIdChanged() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("old-parser-chapter-id", 1, 42_000L),
            immediate = null,
        )

        assertEquals(1, result.chapterIndex)
        assertEquals(42_000L, result.positionMs)
        assertEquals(1, result.persistedChapterIndexToMirror)
    }

    @Test
    fun immediateCheckpointFallsBackToIndexWhenChapterIdChanged() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 2,
            persisted = null,
            immediate = PlaybackResumeCandidate(
                chapterId = "old-parser-chapter-id",
                chapterIndex = 2,
                positionMs = 31_000L,
                preferOverPersisted = true,
            ),
        )

        assertEquals(2, result.chapterIndex)
        assertEquals(31_000L, result.positionMs)
    }

    @Test
    fun multiSourcePersistedWithoutChapterIdDoesNotUseOrdinalFallback() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 2,
            persisted = PlaybackResumeCandidate(null, 2, 40_000L),
            immediate = null,
        )

        assertEquals(0, result.chapterIndex)
        assertEquals(0L, result.positionMs)
        assertNull(result.persistedChapterIndexToMirror)
    }

    @Test
    fun multiSourceBookIgnoresUnknownLegacyLocalCheckpoint() {
        assertFalse(
            shouldUseResumeStoreCheckpoint(
                snapshotSourceCode = "unknown",
                sourceVariantCount = 2,
            )
        )
    }

    @Test
    fun singleSourceBookKeepsUnknownLegacyLocalCheckpoint() {
        assertTrue(
            shouldUseResumeStoreCheckpoint(
                snapshotSourceCode = "unknown",
                sourceVariantCount = 1,
            )
        )
    }

    @Test
    fun completedPersistedClearsNonOverrideResume() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-2", 2, 100_000L, completed = true),
            immediate = PlaybackResumeCandidate("chapter-2", 2, 80_000L, preferOverPersisted = false),
        )

        assertEquals(0, result.chapterIndex)
        assertEquals(0L, result.positionMs)
        assertTrue(result.clearResumeStore)
        assertNull(result.persistedChapterIndexToMirror)
    }

    @Test
    fun completedPersistedDoesNotOverrideNewerImmediateResume() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-2", 2, 100_000L, completed = true),
            immediate = PlaybackResumeCandidate("chapter-1", 1, 25_000L, preferOverPersisted = true),
        )

        assertEquals(1, result.chapterIndex)
        assertEquals(25_000L, result.positionMs)
        assertNull(result.persistedChapterIndexToMirror)
    }

    @Test
    fun nearEndOfIntermediateChapterAdvancesToNextChapter() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-0", 0, 99_000L),
            immediate = null,
        )

        assertEquals(1, result.chapterIndex)
        assertEquals(0L, result.positionMs)
        assertEquals(0, result.persistedChapterIndexToMirror)
    }

    @Test
    fun nearEndOfLastChapterRewindsFiveSecondsInsteadOfMarkingCompleted() {
        val result = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = durations,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-2", 2, 99_500L),
            immediate = null,
        )

        assertEquals(2, result.chapterIndex)
        assertEquals(95_000L, result.positionMs)
        assertEquals(2, result.persistedChapterIndexToMirror)
    }

    @Test
    fun unknownDurationKeepsPositivePositionUnchanged() {
        val result = resolvePlaybackResumePoint(
            chapterIds = listOf("chapter-0"),
            chapterDurationsMs = listOf(0L),
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-0", 0, 42_000L),
            immediate = null,
        )

        assertEquals(0, result.chapterIndex)
        assertEquals(42_000L, result.positionMs)
        assertEquals(0, result.persistedChapterIndexToMirror)
    }

    @Test
    fun explicitChapterImmediateOverrideWinsOverPersisted() {
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = "chapter-1",
            requestedChapterIndex = 1,
            chapterDurationMs = 100_000L,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-1", 1, 20_000L),
            immediate = PlaybackResumeCandidate("chapter-1", 1, 35_000L, preferOverPersisted = true),
        )

        assertEquals(35_000L, position)
    }

    @Test
    fun explicitChapterPersistedWinsOverNonOverrideImmediate() {
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = "chapter-1",
            requestedChapterIndex = 1,
            chapterDurationMs = 100_000L,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-1", 1, 20_000L),
            immediate = PlaybackResumeCandidate("chapter-1", 1, 35_000L, preferOverPersisted = false),
        )

        assertEquals(20_000L, position)
    }

    @Test
    fun explicitChapterIgnoresCheckpointsForAnotherChapter() {
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = "chapter-1",
            requestedChapterIndex = 1,
            chapterDurationMs = 100_000L,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-0", 0, 20_000L),
            immediate = PlaybackResumeCandidate("chapter-2", 2, 35_000L, preferOverPersisted = true),
        )

        assertEquals(0L, position)
    }

    @Test
    fun explicitChapterMultiSourcePersistedWithoutChapterIdIsIgnored() {
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = "chapter-1",
            requestedChapterIndex = 1,
            chapterDurationMs = 100_000L,
            sourceVariantCount = 2,
            persisted = PlaybackResumeCandidate(null, 1, 40_000L),
            immediate = null,
        )

        assertEquals(0L, position)
    }

    @Test
    fun explicitChapterImmediateWithoutChapterIdCanUseOrdinalFallback() {
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = "chapter-1",
            requestedChapterIndex = 1,
            chapterDurationMs = 100_000L,
            sourceVariantCount = 2,
            persisted = null,
            immediate = PlaybackResumeCandidate(null, 1, 30_000L),
        )

        assertEquals(30_000L, position)
    }

    @Test
    fun explicitChapterNearEndRewindsBeforePlayback() {
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = "chapter-1",
            requestedChapterIndex = 1,
            chapterDurationMs = 100_000L,
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate("chapter-1", 1, 99_000L),
            immediate = null,
        )

        assertEquals(95_000L, position)
    }
}
