package com.example.data.local

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.SourceVariantDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuTrackerBookCacheMergeTest {
    private val metadata = BookDetailDto(
        id = "rutracker:123", title = "Book", selectedSource = "rutracker",
        selectedBookSourceId = "live:rutracker:123",
        sourceVariants = listOf(SourceVariantDto(
            "live:rutracker:123", "rutracker", "RuTracker", magnetUri = "magnet:?xt=urn:btih:hash",
        )),
    )
    private val resolved = metadata.copy(
        durationSeconds = 700L,
        chapters = List(7) { index -> ChapterDto(
            id = "rutracker:123:torrserve:hash:${index + 1}", position = index,
            title = "Chapter ${index + 1}", streamUrl = "http://server/stream?index=${index + 1}",
        ) },
    )

    @Test
    fun backgroundTopicRefreshKeepsAllSevenTorrServeFilesAndFreshMetadata() {
        val incoming = metadata.copy(title = "Fresh title", coverUrl = "https://example.org/fresh.jpg")
        val result = mergeRuTrackerBookForCache(incoming, resolved)
        assertEquals(resolved.chapters, result.chapters)
        assertEquals(7, result.chapters.size)
        assertEquals("Fresh title", result.title)
        assertEquals(incoming.coverUrl, result.coverUrl)
        assertEquals(700L, result.durationSeconds)
    }

    @Test
    fun newResolvedPlaylistWinsOverOldPlaylist() {
        val incoming = resolved.copy(chapters = resolved.chapters.dropLast(1))
        assertSame(incoming, mergeRuTrackerBookForCache(incoming, resolved))
    }

    @Test
    fun differentTorrentDoesNotInheritOldFiles() {
        val incoming = metadata.copy(sourceVariants = listOf(
            metadata.sourceVariants.single().copy(magnetUri = "magnet:?xt=urn:btih:other"),
        ))
        assertSame(incoming, mergeRuTrackerBookForCache(incoming, resolved))
    }

    @Test
    fun differentBookOrVariantDoesNotInheritOldFiles() {
        val otherBook = metadata.copy(id = "rutracker:456")
        val otherVariant = metadata.copy(selectedBookSourceId = "live:rutracker:456")
        assertSame(otherBook, mergeRuTrackerBookForCache(otherBook, resolved))
        assertSame(otherVariant, mergeRuTrackerBookForCache(otherVariant, resolved))
    }

    @Test
    fun missingMagnetDoesNotInheritAnUnverifiedPlaylist() {
        val missing = metadata.copy(sourceVariants = emptyList())
        assertSame(missing, mergeRuTrackerBookForCache(missing, resolved))
        assertSame(metadata, mergeRuTrackerBookForCache(metadata, resolved.copy(sourceVariants = emptyList())))
    }

    @Test
    fun otherProvidersAndMissingCacheKeepNormalReplacementBehavior() {
        val incoming = metadata.copy(selectedSource = "bazaknig")
        assertSame(incoming, mergeRuTrackerBookForCache(incoming, resolved))
        assertSame(metadata, mergeRuTrackerBookForCache(metadata, null))
        assertSame(metadata, mergeRuTrackerBookForCache(metadata, metadata))
    }
}
