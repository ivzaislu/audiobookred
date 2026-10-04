package com.example.data.player

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressEstimatorTest {
    private fun book(
        durationSeconds: Long = 0,
        progressPercent: Double = 0.0,
        chapterDurations: List<Long> = emptyList()
    ) = BookDetailDto(
        id = "book",
        title = "Book",
        durationSeconds = durationSeconds,
        progressPercent = progressPercent,
        chapters = chapterDurations.mapIndexed { index, duration ->
            ChapterDto(
                id = "chapter-$index",
                position = index,
                title = "Chapter ${index + 1}",
                durationSeconds = duration
            )
        }
    )

    @Test
    fun completedAlwaysReturnsHundredPercent() {
        val value = estimateOverallProgressPercent(
            book = book(progressPercent = 12.5),
            chapterIndex = 0,
            positionMs = 0,
            currentDurationMs = 0,
            completed = true
        )

        assertEquals(100.0, value, 0.0001)
    }

    @Test
    fun emptyChaptersFallsBackToStoredProgressAndClamps() {
        assertEquals(
            73.5,
            estimateOverallProgressPercent(book(progressPercent = 73.5), 0, 0, 0),
            0.0001
        )
        assertEquals(
            100.0,
            estimateOverallProgressPercent(book(progressPercent = 150.0), 0, 0, 0),
            0.0001
        )
    }

    @Test
    fun exactDurationsUseAbsoluteBookPosition() {
        val value = estimateOverallProgressPercent(
            book = book(durationSeconds = 300, chapterDurations = listOf(100, 100, 100)),
            chapterIndex = 1,
            positionMs = 50_000,
            currentDurationMs = 100_000
        )

        assertEquals(50.0, value, 0.0001)
    }

    @Test
    fun metadataEstimatorUsesSameAbsoluteBookPosition() {
        val value = estimateOverallProgressPercent(
            chapterDurationsMs = listOf(100_000, 100_000, 100_000),
            totalDurationMs = 300_000,
            storedProgressPercent = 0.0,
            chapterIndex = 1,
            positionMs = 50_000,
            currentDurationMs = 100_000,
        )

        assertEquals(50.0, value, 0.0001)
    }

    @Test
    fun positionIsClampedToCurrentChapterDuration() {
        val value = estimateOverallProgressPercent(
            book = book(durationSeconds = 200, chapterDurations = listOf(100, 100)),
            chapterIndex = 0,
            positionMs = 500_000,
            currentDurationMs = 100_000
        )

        assertEquals(50.0, value, 0.0001)
    }

    @Test
    fun unknownChapterDurationsUseOrdinalEstimate() {
        val value = estimateOverallProgressPercent(
            book = book(chapterDurations = listOf(0, 0, 0, 0)),
            chapterIndex = 1,
            positionMs = 30_000,
            currentDurationMs = 60_000
        )

        assertEquals(37.5, value, 0.0001)
    }

    @Test
    fun untouchedUnknownSourceKeepsStoredProgress() {
        val value = estimateOverallProgressPercent(
            book = book(progressPercent = 21.0, chapterDurations = listOf(0, 0, 0)),
            chapterIndex = 0,
            positionMs = 0,
            currentDurationMs = 0
        )

        assertEquals(21.0, value, 0.0001)
    }

    @Test
    fun chapterIndexIsClampedToAvailableRange() {
        val value = estimateOverallProgressPercent(
            book = book(chapterDurations = listOf(0, 0)),
            chapterIndex = 99,
            positionMs = 30_000,
            currentDurationMs = 60_000
        )

        assertEquals(75.0, value, 0.0001)
    }
}
