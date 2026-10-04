package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookPlaybackPoliciesTest {
    @Test
    fun smartRewindLeavesShortPauseUntouched() {
        assertEquals(0L, smartRewindMsForPause(0L))
        assertEquals(0L, smartRewindMsForPause(29_999L))
    }

    @Test
    fun smartRewindUsesFiveSecondsAfterShortBreak() {
        assertEquals(5_000L, smartRewindMsForPause(30_000L))
        assertEquals(5_000L, smartRewindMsForPause(4 * 60_000L + 59_999L))
    }

    @Test
    fun smartRewindUsesTenSecondsAfterMediumBreak() {
        assertEquals(10_000L, smartRewindMsForPause(5 * 60_000L))
        assertEquals(10_000L, smartRewindMsForPause(29 * 60_000L + 59_999L))
    }

    @Test
    fun smartRewindUsesTwentySecondsAfterLongBreak() {
        assertEquals(20_000L, smartRewindMsForPause(30 * 60_000L))
        assertEquals(20_000L, smartRewindMsForPause(3 * 60 * 60_000L))
    }

    @Test
    fun undoSeekIgnoresSmallMovementInsideSameChapter() {
        assertFalse(
            shouldOfferUndoSeek(
                oldChapterIndex = 2,
                oldPositionMs = 120_000L,
                newChapterIndex = 2,
                newPositionMs = 149_999L,
            )
        )
    }

    @Test
    fun undoSeekAppearsForLargeMovementInsideSameChapter() {
        assertTrue(
            shouldOfferUndoSeek(
                oldChapterIndex = 2,
                oldPositionMs = 120_000L,
                newChapterIndex = 2,
                newPositionMs = 150_000L,
            )
        )
    }

    @Test
    fun undoSeekAppearsWhenChapterChanges() {
        assertTrue(
            shouldOfferUndoSeek(
                oldChapterIndex = 2,
                oldPositionMs = 120_000L,
                newChapterIndex = 3,
                newPositionMs = 0L,
            )
        )
    }
}
