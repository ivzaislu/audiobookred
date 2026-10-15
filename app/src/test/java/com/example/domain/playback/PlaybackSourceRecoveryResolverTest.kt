package com.example.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceRecoveryResolverTest {
    private val chapters = listOf(
        "rutracker:123:torrserve:hash:1",
        "rutracker:123:torrserve:hash:2",
        "rutracker:123:torrserve:hash:3",
    )

    @Test
    fun completedTorrServeBookCanRestartOnUserResumeButRecoveryKeepsLastFile() {
        // The old recovery path reused resume() and could select file 1 once
        // Room held completed=true and the immediate checkpoint was not dirty.
        val userResume = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = listOf(0L, 0L, 0L),
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate(chapters.last(), 2, 100_000L, completed = true),
            immediate = PlaybackResumeCandidate(chapters.last(), 2, 100_000L),
        )
        assertEquals(0, userResume.chapterIndex)
        assertEquals(0L, userResume.positionMs)
        assertTrue(userResume.clearResumeStore)

        val recovery = resolvePlaybackSourceRecoveryPoint(chapters, chapters.last(), 100_000L)
        assertEquals(PlaybackSourceRecoveryPoint(2, 100_000L), recovery)
    }

    @Test
    fun unknownDurationSingleFileKeepsPositivePositionInsteadOfReplayingFromZero() {
        val recovery = resolvePlaybackSourceRecoveryPoint(listOf(chapters.last()), chapters.last(), 3_600_000L)
        assertEquals(PlaybackSourceRecoveryPoint(0, 3_600_000L), recovery)
    }

    @Test
    fun recoveryDoesNotApplyResumeNearEndRewind() {
        val userResume = resolvePlaybackResumePoint(
            chapterIds = chapters,
            chapterDurationsMs = listOf(100_000L, 100_000L, 100_000L),
            sourceVariantCount = 1,
            persisted = PlaybackResumeCandidate(chapters.last(), 2, 99_500L),
            immediate = null,
        )
        assertEquals(95_000L, userResume.positionMs)
        assertFalse(userResume.clearResumeStore)
        assertEquals(
            PlaybackSourceRecoveryPoint(2, 99_500L),
            resolvePlaybackSourceRecoveryPoint(chapters, chapters.last(), 99_500L),
        )
    }

    @Test
    fun reorderedFilesKeepExactFileIdentity() {
        assertEquals(
            PlaybackSourceRecoveryPoint(0, 42_000L),
            resolvePlaybackSourceRecoveryPoint(chapters.reversed(), chapters.last(), 42_000L),
        )
    }

    @Test
    fun missingFileDoesNotFallBackToFirstFileOrSameOrdinal() {
        assertNull(resolvePlaybackSourceRecoveryPoint(chapters.dropLast(1), chapters.last(), 100_000L))
        assertNull(resolvePlaybackSourceRecoveryPoint(listOf("another-torrent:3"), chapters.last(), 100_000L))
    }

    @Test
    fun emptyPlaylistOrMissingIdentityRejectsRecovery() {
        assertNull(resolvePlaybackSourceRecoveryPoint(emptyList(), chapters.last(), 100_000L))
        assertNull(resolvePlaybackSourceRecoveryPoint(chapters, "", 100_000L))
    }

    @Test
    fun negativePositionIsClampedWithoutChangingChapter() {
        assertEquals(
            PlaybackSourceRecoveryPoint(1, 0L),
            resolvePlaybackSourceRecoveryPoint(chapters, chapters[1], -1L),
        )
    }
}
