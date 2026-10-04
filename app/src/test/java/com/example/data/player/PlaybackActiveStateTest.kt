package com.example.data.player

import androidx.media3.common.Player
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackActiveStateTest {
    @Test
    fun resumeFallbackIsOnlyUsedForIdleOrEndedZeroPosition() {
        assertTrue(shouldUsePlaybackResumeFallback(Player.STATE_IDLE, false, 0L))
        assertTrue(shouldUsePlaybackResumeFallback(Player.STATE_ENDED, false, -1L))
        assertFalse(shouldUsePlaybackResumeFallback(Player.STATE_READY, false, 0L))
        assertFalse(shouldUsePlaybackResumeFallback(Player.STATE_IDLE, true, 0L))
        assertFalse(shouldUsePlaybackResumeFallback(Player.STATE_IDLE, false, 1L))
    }

    @Test
    fun livePointWinsWithoutResume() {
        val point = resolvePlaybackActivePoint(
            book = bookWithDurations(10L, 20L),
            currentChapterIndex = 1,
            currentPositionMs = 3_000L,
            currentDurationMs = 19_000L,
            resume = null,
        )

        assertEquals(1, point.chapterIndex)
        assertEquals(3_000L, point.positionMs)
        assertEquals(19_000L, point.durationMs)
    }

    @Test
    fun resumeOverridesZeroIdlePointAndUsesBookDurationFallback() {
        val point = resolvePlaybackActivePoint(
            book = bookWithDurations(10L, 20L),
            currentChapterIndex = 0,
            currentPositionMs = 0L,
            currentDurationMs = 0L,
            resume = snapshot(chapterIndex = 1, positionMs = 4_500L),
        )

        assertEquals(1, point.chapterIndex)
        assertEquals(4_500L, point.positionMs)
        assertEquals(20_000L, point.durationMs)
    }

    @Test
    fun resumeValuesAreClampedToRecoveredBook() {
        val point = resolvePlaybackActivePoint(
            book = bookWithDurations(10L),
            currentChapterIndex = -1,
            currentPositionMs = -5L,
            currentDurationMs = -1L,
            resume = snapshot(chapterIndex = 99, positionMs = -20L),
        )

        assertEquals(0, point.chapterIndex)
        assertEquals(0L, point.positionMs)
        assertEquals(10_000L, point.durationMs)
    }

    private fun bookWithDurations(vararg durations: Long): BookDetailDto = BookDetailDto(
        id = "book",
        title = "Book",
        chapters = durations.mapIndexed { index, duration ->
            ChapterDto(
                id = "chapter-$index",
                position = index,
                title = "Chapter $index",
                durationSeconds = duration,
            )
        },
    )

    private fun snapshot(
        chapterIndex: Int,
        positionMs: Long,
    ) = PlaybackResumeStore.Snapshot(
        bookId = "book",
        sourceCode = "source",
        chapterId = null,
        chapterIndex = chapterIndex,
        positionMs = positionMs,
        speed = 1f,
        progressPercent = 0.0,
        savedAtMs = 1L,
        dirty = false,
    )
}
