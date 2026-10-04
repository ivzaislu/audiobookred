package com.example.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBookRecoveryRepositoryTest {
    @Test
    fun sameBookSourceAndTimestampMatches() {
        assertTrue(
            samePlaybackSnapshotIdentity(
                snapshot(bookId = "book", sourceCode = "Audiopolka", savedAtMs = 10L),
                snapshot(bookId = "book", sourceCode = "audiopolka", savedAtMs = 10L),
            )
        )
    }

    @Test
    fun blankAndUnknownSourceShareUnconstrainedIdentity() {
        assertTrue(
            samePlaybackSnapshotIdentity(
                snapshot(bookId = "book", sourceCode = "", savedAtMs = 10L),
                snapshot(bookId = "book", sourceCode = "unknown", savedAtMs = 10L),
            )
        )
    }

    @Test
    fun ambiguousLegacySourceIsRejectedForMultiSourceBook() {
        assertFalse(canRecoverPlaybackSnapshotSource("unknown", sourceVariantCount = 2))
        assertFalse(canRecoverPlaybackSnapshotSource("", sourceVariantCount = 2))
    }

    @Test
    fun legacySourceIsAllowedForSingleSourceBook() {
        assertTrue(canRecoverPlaybackSnapshotSource("unknown", sourceVariantCount = 1))
    }

    @Test
    fun knownSourceIsAllowedForMultiSourceBook() {
        assertTrue(canRecoverPlaybackSnapshotSource("audiopolka", sourceVariantCount = 3))
    }

    @Test
    fun newerTimestampDoesNotMatchOlderRecovery() {
        assertFalse(
            samePlaybackSnapshotIdentity(
                snapshot(bookId = "book", sourceCode = "source", savedAtMs = 10L),
                snapshot(bookId = "book", sourceCode = "source", savedAtMs = 11L),
            )
        )
    }

    @Test
    fun differentBookOrSourceDoesNotMatch() {
        assertFalse(
            samePlaybackSnapshotIdentity(
                snapshot(bookId = "book-a", sourceCode = "source-a", savedAtMs = 10L),
                snapshot(bookId = "book-b", sourceCode = "source-a", savedAtMs = 10L),
            )
        )
        assertFalse(
            samePlaybackSnapshotIdentity(
                snapshot(bookId = "book", sourceCode = "source-a", savedAtMs = 10L),
                snapshot(bookId = "book", sourceCode = "source-b", savedAtMs = 10L),
            )
        )
    }

    private fun snapshot(
        bookId: String,
        sourceCode: String,
        savedAtMs: Long,
    ) = PlaybackResumeStore.Snapshot(
        bookId = bookId,
        sourceCode = sourceCode,
        chapterId = null,
        chapterIndex = 0,
        positionMs = 1L,
        speed = 1f,
        progressPercent = 0.0,
        savedAtMs = savedAtMs,
        dirty = false,
    )
}
