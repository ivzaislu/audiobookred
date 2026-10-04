package com.example.ui

import com.example.data.model.BookCardDto
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDisplayLabelsTest {
    @Test
    fun sourceDisplayLabelUsesReadableProviderNames() {
        assertEquals("Книга в ухе", sourceDisplayLabel("knigavuhe"))
        assertEquals("Baza-Knig", sourceDisplayLabel("bazaknig"))
        assertEquals("уКниг", sourceDisplayLabel("UKNIG"))
        assertEquals("RuTracker", sourceDisplayLabel("rutracker"))
    }

    @Test
    fun bookCardSourcePrefersPrimarySourceAndFallsBackToSourceCodes() {
        assertEquals(
            "Audiopolka",
            BookCardDto(
                id = "book-1",
                title = "Book",
                primarySource = "audiopolka",
                sourceCodes = listOf("uknig"),
            ).sourceDisplayLabel(),
        )
        assertEquals(
            "уКниг",
            BookCardDto(
                id = "book-2",
                title = "Book",
                sourceCodes = listOf("uknig"),
            ).sourceDisplayLabel(),
        )
    }

    @Test
    fun cachedLiveBookFallsBackToSourceQualifiedId() {
        assertEquals(
            "Audiopolka",
            BookCardDto(id = "audiopolka:12345", title = "Book").sourceDisplayLabel(),
        )
        assertEquals(
            "Книга в ухе",
            BookCardDto(id = "knigavuhe:67890", title = "Book").sourceDisplayLabel(),
        )
        assertEquals(
            "Baza-Knig",
            BookCardDto(id = "bazaknig:123094-krov-vasiliska", title = "Book").sourceDisplayLabel(),
        )
    }

    @Test
    fun rutrackerCardNeverShowsSeriesLabelEvenFromStaleCache() {
        val card = BookCardDto(
            id = "rutracker:123",
            title = "Book",
            primarySource = "rutracker",
            sourceCodes = listOf("rutracker"),
            sourceSeriesName = "Старый цикл",
            sourceSeriesPosition = 2,
        )

        assertEquals("", card.seriesDisplayLabel())
    }

    @Test
    fun bookCardWithoutSourceDoesNotRenderFallbackAllLabel() {
        assertEquals(
            "",
            BookCardDto(id = "book-3", title = "Book").sourceDisplayLabel(),
        )
    }
}
