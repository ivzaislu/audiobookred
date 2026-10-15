package com.example.domain.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumptionPointTest {
    private val chapters = listOf("first", "middle", "last")

    @Test fun lastChapterKeepsExactPositionWithoutRestartOrRewind() {
        assertEquals(PlaybackSourceRecoveryPoint(2, 100_000L),
            resolvePlaybackResumptionPoint(chapters, "last", 2, 100_000L))
    }
    @Test fun exactChapterIdentitySurvivesReorderedFiles() {
        assertEquals(PlaybackSourceRecoveryPoint(0, 42_000L),
            resolvePlaybackResumptionPoint(chapters.reversed(), "last", 2, 42_000L))
    }
    @Test fun missingChapterDoesNotFallBackToFirstOrOrdinal() {
        assertNull(resolvePlaybackResumptionPoint(chapters, "old-last", 0, 100_000L))
    }
    @Test fun legacyCheckpointWithoutIdKeepsValidIndex() {
        assertEquals(PlaybackSourceRecoveryPoint(2, 10_000L),
            resolvePlaybackResumptionPoint(chapters, null, 2, 10_000L))
    }
    @Test fun invalidLegacyIndexAndEmptyPlaylistRejectResumption() {
        assertNull(resolvePlaybackResumptionPoint(chapters, null, 3, 10_000L))
        assertNull(resolvePlaybackResumptionPoint(chapters, null, -1, 10_000L))
        assertNull(resolvePlaybackResumptionPoint(emptyList(), null, 0, 0L))
    }
    @Test fun negativePositionIsClamped() {
        assertEquals(PlaybackSourceRecoveryPoint(1, 0L),
            resolvePlaybackResumptionPoint(chapters, "middle", 1, -1L))
    }
}
