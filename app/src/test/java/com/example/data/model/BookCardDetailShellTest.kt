package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCardDetailShellTest {
    @Test
    fun `preserves cover metadata and selects primary source`() {
        val card = BookCardDto(
            id = "book-1",
            title = "Book",
            authors = listOf(PersonDto("author-1", "Author")),
            coverUrl = "https://example.test/cover.jpg",
            durationSeconds = 123L,
            rating = "4.8",
            isFavorite = true,
            progressPercent = 42.5,
            sourceCodes = listOf("a", "b"),
            primarySource = "b",
        )

        val detail = card.toDetailShell()

        assertEquals(card.id, detail.id)
        assertEquals(card.title, detail.title)
        assertEquals(card.authors, detail.authors)
        assertEquals(card.coverUrl, detail.coverUrl)
        assertEquals(card.durationSeconds, detail.durationSeconds)
        assertEquals(card.rating, detail.rating)
        assertEquals(card.isFavorite, detail.isFavorite)
        assertEquals(card.progressPercent, detail.progressPercent, 0.0)
        assertEquals(card.sourceCodes, detail.sourceCodes)
        assertEquals("b", detail.selectedSource)
        assertTrue(detail.chapters.isEmpty())
        assertTrue(detail.sourceVariants.isEmpty())
    }

    @Test
    fun `falls back to first source when primary source is blank`() {
        val detail = BookCardDto(
            id = "book-2",
            title = "Book",
            sourceCodes = listOf("first", "second"),
        ).toDetailShell()

        assertEquals("first", detail.selectedSource)
    }
}
