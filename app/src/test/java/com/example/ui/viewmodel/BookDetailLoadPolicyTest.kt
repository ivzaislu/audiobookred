package com.example.ui.viewmodel

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailLoadPolicyTest {
    @Test
    fun shellFailureIsSurfacedButCompleteCachedBookStaysQuietOffline() {
        val shell = BookDetailDto(id = "knigavuhe:1", title = "Книга")
        val complete = shell.copy(
            chapters = listOf(
                ChapterDto(
                    id = "chapter-1",
                    position = 0,
                    title = "Глава 1",
                    streamUrl = "https://example.test/1.mp3",
                )
            )
        )

        assertTrue(shouldSurfaceBookLoadFailure(shell, previewBook = null))
        assertTrue(shouldSurfaceBookLoadFailure(book = null, previewBook = null))
        assertFalse(shouldSurfaceBookLoadFailure(complete, previewBook = null))
    }

    @Test
    fun previewOnlyStateIsAlwaysSurfaced() {
        val complete = BookDetailDto(
            id = "knigavuhe:1",
            title = "Книга",
            chapters = listOf(
                ChapterDto(
                    id = "chapter-1",
                    position = 0,
                    title = "Глава 1",
                    streamUrl = "https://example.test/1.mp3",
                )
            ),
        )
        val preview = BookDetailDto(id = "knigavuhe:1", title = "Книга")

        assertTrue(shouldSurfaceBookLoadFailure(complete, preview))
    }
}
