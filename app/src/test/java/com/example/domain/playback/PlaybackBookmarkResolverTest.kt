package com.example.domain.playback

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.SourceVariantDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBookmarkResolverTest {
    @Test
    fun chapterIdWinsOverOrdinalFallback() {
        assertEquals(
            1,
            resolveBookmarkChapterIndex(
                chapterIds = listOf("a", "b", "c"),
                bookmarkChapterId = "b",
                bookmarkChapterIndex = 2,
            ),
        )
    }

    @Test
    fun unknownChapterIdFallsBackToOrdinal() {
        assertEquals(
            2,
            resolveBookmarkChapterIndex(
                chapterIds = listOf("a", "b", "c"),
                bookmarkChapterId = "missing",
                bookmarkChapterIndex = 2,
            ),
        )
    }

    @Test
    fun missingChapterIdFallsBackToOrdinal() {
        assertEquals(
            1,
            resolveBookmarkChapterIndex(
                chapterIds = listOf("a", "b", "c"),
                bookmarkChapterId = null,
                bookmarkChapterIndex = 1,
            ),
        )
    }

    @Test
    fun negativeOrdinalClampsToFirstChapter() {
        assertEquals(
            0,
            resolveBookmarkChapterIndex(
                chapterIds = listOf("a", "b", "c"),
                bookmarkChapterId = null,
                bookmarkChapterIndex = -4,
            ),
        )
    }

    @Test
    fun oversizedOrdinalClampsToLastChapter() {
        assertEquals(
            2,
            resolveBookmarkChapterIndex(
                chapterIds = listOf("a", "b", "c"),
                bookmarkChapterId = null,
                bookmarkChapterIndex = 99,
            ),
        )
    }

    @Test
    fun emptyChapterListPreservesLegacyZeroIndex() {
        assertEquals(
            0,
            resolveBookmarkChapterIndex(
                chapterIds = emptyList(),
                bookmarkChapterId = "missing",
                bookmarkChapterIndex = 5,
            ),
        )
    }

    @Test
    fun ruTrackerBookmarkWithMagnetRequiresPlayableResolution() {
        val book = BookDetailDto(
            id = "rutracker:6910707",
            title = "Книга",
            selectedSource = "rutracker",
            selectedBookSourceId = "live:rutracker:6910707",
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = "live:rutracker:6910707",
                    sourceCode = "rutracker",
                    sourceName = "RuTracker",
                    magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                )
            ),
        )

        assertTrue(shouldResolveRuTrackerBookmarkSource(book))
        assertFalse(
            shouldResolveRuTrackerBookmarkSource(
                book.copy(selectedSource = "audiopolka")
            )
        )
    }

    @Test
    fun completedRuTrackerDownloadCanBypassTorrServePreparation() {
        assertTrue(
            shouldUseCompletedRuTrackerDownload(
                sourceCode = "rutracker",
                bookSourceId = "live:rutracker:6910707",
                downloadState = "completed",
                deletedAtMs = null,
                expectedFilesCount = 2,
                fileStates = listOf("completed", "completed"),
            )
        )
    }

    @Test
    fun incompleteOrMissingRuTrackerFilesCannotBypassTorrServePreparation() {
        assertFalse(
            shouldUseCompletedRuTrackerDownload(
                sourceCode = "rutracker",
                bookSourceId = "live:rutracker:6910707",
                downloadState = "completed",
                deletedAtMs = null,
                expectedFilesCount = 2,
                fileStates = listOf("completed"),
            )
        )
        assertFalse(
            shouldUseCompletedRuTrackerDownload(
                sourceCode = "rutracker",
                bookSourceId = "live:rutracker:6910707",
                downloadState = "queued",
                deletedAtMs = null,
                expectedFilesCount = 2,
                fileStates = listOf("completed", "completed"),
            )
        )
    }

    @Test
    fun staleRuTrackerChapterIdCanFallbackToSavedOrdinal() {
        val book = BookDetailDto(
            id = "rutracker:6910707",
            title = "Книга",
            selectedSource = "rutracker",
            selectedBookSourceId = "live:rutracker:6910707",
            chapters = listOf(
                ChapterDto(
                    id = "rutracker:6910707:torrserve:newhash:1",
                    position = 0,
                    title = "01",
                    streamUrl = "http://127.0.0.1/stream/01",
                ),
                ChapterDto(
                    id = "rutracker:6910707:torrserve:newhash:2",
                    position = 1,
                    title = "02",
                    streamUrl = "http://127.0.0.1/stream/02",
                ),
            ),
        )

        val oldChapterId = "rutracker:6910707:torrserve:oldhash:2"
        assertTrue(shouldUseRuTrackerBookmarkOrdinalFallback(book, oldChapterId))
        assertEquals(
            1,
            resolveBookmarkChapterIndex(
                chapterIds = book.chapters.map { it.id },
                bookmarkChapterId = oldChapterId,
                bookmarkChapterIndex = 1,
            ),
        )
    }

    @Test
    fun unrelatedMissingRuTrackerChapterDoesNotUseOrdinalFallback() {
        val book = BookDetailDto(
            id = "rutracker:6910707",
            title = "Книга",
            selectedSource = "rutracker",
            chapters = listOf(
                ChapterDto(
                    id = "rutracker:6910707:torrserve:newhash:1",
                    position = 0,
                    title = "01",
                    streamUrl = "http://127.0.0.1/stream/01",
                )
            ),
        )

        assertFalse(shouldUseRuTrackerBookmarkOrdinalFallback(book, "legacy:chapter:1"))
    }

    @Test
    fun nonRuTrackerMissingChapterDoesNotUseRuTrackerOrdinalFallback() {
        val book = BookDetailDto(
            id = "audiopolka:book",
            title = "Книга",
            selectedSource = "audiopolka",
            chapters = listOf(
                ChapterDto(
                    id = "fresh",
                    position = 0,
                    title = "01",
                    streamUrl = "https://example.com/01.mp3",
                )
            ),
        )

        assertFalse(shouldUseRuTrackerBookmarkOrdinalFallback(book, "stale"))
    }

    @Test
    fun preferredSourceWinsForBookmarkPlayback() {
        assertEquals(
            "rutracker",
            resolveBookmarkPlaybackSource(
                preferredSource = " rutracker ",
                resumeSource = "audiopolka",
            ),
        )
    }

    @Test
    fun durableResumeSourceIsFallbackForBookmarkPlayback() {
        assertEquals(
            "audiopolka",
            resolveBookmarkPlaybackSource(
                preferredSource = null,
                resumeSource = "audiopolka",
            ),
        )
    }

    @Test
    fun unknownAndBlankBookmarkSourcesAreIgnored() {
        assertNull(
            resolveBookmarkPlaybackSource(
                preferredSource = "   ",
                resumeSource = "UNKNOWN",
            )
        )
    }

    @Test
    fun cachedBookmarkSourceMatchesHintIgnoringCaseAndWhitespace() {
        assertTrue(
            bookmarkCachedSourceMatchesHint(
                cachedSource = " RUTRACKER ",
                sourceHint = "rutracker",
            )
        )
    }

    @Test
    fun cachedBookmarkSourceMustNotReplaceRequestedVariant() {
        assertFalse(
            bookmarkCachedSourceMatchesHint(
                cachedSource = "audiopolka",
                sourceHint = "rutracker",
            )
        )
    }

    @Test
    fun cachedBookmarkSourceIsAcceptedWhenThereIsNoHint() {
        assertTrue(
            bookmarkCachedSourceMatchesHint(
                cachedSource = "audiopolka",
                sourceHint = null,
            )
        )
    }
}
