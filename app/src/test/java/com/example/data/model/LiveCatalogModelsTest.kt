package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCatalogModelsTest {
    @Test
    fun `live catalog item keeps standalone provider metadata`() {
        val item = LiveCatalogItemDto(
            key = "rutracker:6862086",
            source = "rutracker",
            externalId = "6862086",
            externalUrl = "https://rutracker.org/forum/viewtopic.php?t=6862086",
            title = "Строитель 1, Путь строителя 1",
            coverUrl = "https://img.example/cover.jpg",
            durationSeconds = 3601,
            sourceMeta = "Аудиокнига",
            authors = listOf("Ковтунов Алексей"),
            narrators = listOf("Андрей Федоренко"),
            genres = listOf("Фантастика"),
            seriesName = "Строитель",
            seriesExternalId = "topic-series:6862086",
            seriesPosition = 1,
        )

        assertEquals("rutracker:6862086", item.key)
        assertEquals("rutracker", item.source)
        assertEquals("6862086", item.externalId)
        assertEquals("https://rutracker.org/forum/viewtopic.php?t=6862086", item.externalUrl)
        assertEquals("https://img.example/cover.jpg", item.coverUrl)
        assertEquals(3601L, item.durationSeconds)
        assertEquals("Аудиокнига", item.sourceMeta)
        assertEquals(listOf("Ковтунов Алексей"), item.authors)
        assertEquals(listOf("Андрей Федоренко"), item.narrators)
        assertEquals(listOf("Фантастика"), item.genres)
        assertEquals("Строитель", item.seriesName)
        assertEquals("topic-series:6862086", item.seriesExternalId)
        assertEquals(1, item.seriesPosition)
    }

    @Test
    fun `live catalog item defaults optional provider metadata`() {
        val item = LiveCatalogItemDto(
            key = "uknig:42",
            source = "uknig",
            externalId = "42",
            title = "Ночной дозор",
        )

        assertEquals("", item.externalUrl)
        assertEquals("", item.coverUrl)
        assertEquals(0L, item.durationSeconds)
        assertEquals("", item.sourceMeta)
        assertTrue(item.authors.isEmpty())
        assertTrue(item.narrators.isEmpty())
        assertTrue(item.genres.isEmpty())
        assertEquals("", item.seriesName)
        assertEquals("", item.seriesExternalId)
        assertEquals(null, item.seriesPosition)
    }
}
