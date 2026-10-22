package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextReaderNavigationTest {
    @Test fun automaticExportRangesBecomeOneBookEntryWithoutChangingSource() {
        val stored = listOf(chapter("c0", "Часть 1", true), chapter("c1", "Часть 2", true))
        val contents = readingContents(stored)
        assertEquals(listOf(TextContentsEntry("c0", "Текст книги", true)), contents)
        assertEquals(listOf("Часть 1", "Часть 2"), stored.map { it.title })
        assertEquals(2, stored.size)
    }

    @Test fun authoredChaptersKeepTheirNamesOrderAndIdentities() {
        val chapters = listOf(chapter("c0", "Пролог", false), chapter("c1", "Начало пути", false))
        assertEquals(listOf(TextContentsEntry("c0", "Пролог"), TextContentsEntry("c1", "Начало пути")), readingContents(chapters))
        assertTrue(readingContents(emptyList()).isEmpty())
    }

    @Test fun progressUsesCharacterPositionRegardlessOfChapterOrLayoutCounts() {
        assertEquals(0, textReadingPercent(20_000, 0, false))
        assertEquals(50, textReadingPercent(20_000, 10_000, false))
        // Large blocks/chapters must not round to completed before the last screen is reached.
        assertEquals(99, textReadingPercent(20_000, 19_999, false))
        assertEquals(100, textReadingPercent(20_000, 19_000, true))
        assertEquals(0, textReadingPercent(0, 0, true))
    }

    private fun chapter(id: String, title: String, automatic: Boolean) = TextChapterEntity(id, "book", 0, title, 0, 1, automatic)
}
