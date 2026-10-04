package com.example.ui.viewmodel

import com.example.data.model.BookDetailDto
import com.example.data.model.SourceVariantDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailUnavailablePolicyTest {
    @Test
    fun rutrackerMagnetIsDownloadableBeforeChaptersExist() {
        val book = BookDetailDto(
            id = "rutracker:6910707",
            title = "Test",
            primarySource = "rutracker",
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

        assertTrue(canDownloadBook(book))
    }

    @Test
    fun unavailableMetadataMustBeSurfacedEvenWhenCachedBookExists() {
        val cached = book(chaptersPresent = true)
        val unavailable = book(chaptersPresent = false)

        assertTrue(shouldSurfaceBookLoadFailure(cached, unavailable))
    }

    @Test
    fun completeCachedBookSuppressesOrdinaryRefreshFailure() {
        val cached = book(chaptersPresent = true)

        assertFalse(shouldSurfaceBookLoadFailure(cached, previewBook = null))
    }

    @Test
    fun emptyCachedBookStillSurfacesOrdinaryFailure() {
        val cached = book(chaptersPresent = false)

        assertTrue(shouldSurfaceBookLoadFailure(cached, previewBook = null))
    }

    private fun book(chaptersPresent: Boolean): BookDetailDto = BookDetailDto(
        id = "myaudiobooks:litrpg/123-audiokniga-test.html",
        title = "Test",
        primarySource = "myaudiobooks",
        selectedSource = "myaudiobooks",
        selectedBookSourceId = "live:myaudiobooks:litrpg/123-audiokniga-test.html",
        chapters = if (chaptersPresent) {
            listOf(
                com.example.data.model.ChapterDto(
                    id = "chapter-1",
                    position = 0,
                    title = "Part 1",
                    streamUrl = "https://cdn.example.test/1.mp3",
                )
            )
        } else {
            emptyList()
        },
    )
}
