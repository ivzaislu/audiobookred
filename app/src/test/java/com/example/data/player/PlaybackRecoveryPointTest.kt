package com.example.data.player

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryPointTest {
    @Test
    fun emptyBookHasNoRecoveryPoint() {
        assertNull(
            resolvePlaybackRecoveryPoint(
                book = BookDetailDto(id = "book", title = "Book"),
                snapshot = null,
                fallbackChapterIndex = 0,
                fallbackPositionMs = 0L,
                fallbackSpeed = 1f,
            )
        )
    }

    @Test
    fun durableSnapshotWinsAndIsClampedToBook() {
        val point = resolvePlaybackRecoveryPoint(
            book = bookWithDurations(10L, 20L),
            snapshot = snapshot(chapterIndex = 99, positionMs = -50L, speed = 2.25f),
            fallbackChapterIndex = 0,
            fallbackPositionMs = 123L,
            fallbackSpeed = 1f,
        )!!

        assertEquals(1, point.chapterIndex)
        assertEquals(0L, point.positionMs)
        assertEquals(20_000L, point.durationMs)
        assertEquals(2.25f, point.speed, 0.0001f)
    }

    @Test
    fun snapshotChapterIdWinsWhenChapterOrderChanges() {
        val point = resolvePlaybackRecoveryPoint(
            book = bookWithDurations(10L, 20L, 30L),
            snapshot = snapshot(
                chapterIndex = 0,
                chapterId = "chapter-2",
                positionMs = 4_000L,
                speed = 1.5f,
            ),
            fallbackChapterIndex = 0,
            fallbackPositionMs = 0L,
            fallbackSpeed = 1f,
        )!!

        assertEquals(2, point.chapterIndex)
        assertEquals(4_000L, point.positionMs)
        assertEquals(30_000L, point.durationMs)
    }

    @Test
    fun missingSnapshotChapterIdDoesNotGuessByIndex() {
        assertNull(
            resolvePlaybackRecoveryPoint(
                book = bookWithDurations(10L, 20L),
                snapshot = snapshot(
                    chapterIndex = 1,
                    chapterId = "removed-chapter",
                    positionMs = 4_000L,
                    speed = 1.5f,
                ),
                fallbackChapterIndex = 0,
                fallbackPositionMs = 0L,
                fallbackSpeed = 1f,
            )
        )
    }

    @Test
    fun uiFallbackIsUsedWithoutSnapshot() {
        val point = resolvePlaybackRecoveryPoint(
            book = bookWithDurations(10L, 20L),
            snapshot = null,
            fallbackChapterIndex = 1,
            fallbackPositionMs = 4_321L,
            fallbackSpeed = 1.7f,
        )!!

        assertEquals(1, point.chapterIndex)
        assertEquals(4_321L, point.positionMs)
        assertEquals(20_000L, point.durationMs)
        assertEquals(1.7f, point.speed, 0.0001f)
    }

    @Test
    fun invalidFallbackValuesAreClamped() {
        val point = resolvePlaybackRecoveryPoint(
            book = bookWithDurations(-1L),
            snapshot = null,
            fallbackChapterIndex = -10,
            fallbackPositionMs = -1L,
            fallbackSpeed = 9f,
        )!!

        assertEquals(0, point.chapterIndex)
        assertEquals(0L, point.positionMs)
        assertEquals(0L, point.durationMs)
        assertEquals(3f, point.speed, 0.0001f)
    }

    @Test
    fun invalidSnapshotSpeedFallsBackToValidUiSpeed() {
        val point = resolvePlaybackRecoveryPoint(
            book = bookWithDurations(10L),
            snapshot = snapshot(chapterIndex = 0, positionMs = 1_000L, speed = Float.NaN),
            fallbackChapterIndex = 0,
            fallbackPositionMs = 0L,
            fallbackSpeed = 1.4f,
        )!!

        assertEquals(1.4f, point.speed, 0.0001f)
    }

    @Test
    fun invalidSnapshotAndFallbackSpeedUseNormalSpeed() {
        val point = resolvePlaybackRecoveryPoint(
            book = bookWithDurations(10L),
            snapshot = snapshot(
                chapterIndex = 0,
                positionMs = 1_000L,
                speed = Float.POSITIVE_INFINITY,
            ),
            fallbackChapterIndex = 0,
            fallbackPositionMs = 0L,
            fallbackSpeed = Float.NaN,
        )!!

        assertEquals(1.0f, point.speed, 0.0001f)
    }

    @Test
    fun playbackSpeedSanitizerClampsFiniteValues() {
        assertEquals(0.5f, sanitizePlaybackSpeed(0.1f), 0.0001f)
        assertEquals(3.0f, sanitizePlaybackSpeed(4.0f), 0.0001f)
    }

    @Test
    fun playbackSpeedSanitizerUsesSafeFallbackForNonFiniteValues() {
        assertEquals(1.4f, sanitizePlaybackSpeed(Float.NaN, 1.4f), 0.0001f)
        assertEquals(1.0f, sanitizePlaybackSpeed(Float.POSITIVE_INFINITY, Float.NaN), 0.0001f)
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
        speed: Float,
        chapterId: String? = null,
    ) = PlaybackResumeStore.Snapshot(
        bookId = "book",
        sourceCode = "source",
        chapterId = chapterId,
        chapterIndex = chapterIndex,
        positionMs = positionMs,
        speed = speed,
        progressPercent = 0.0,
        savedAtMs = 1L,
        dirty = false,
    )
}
