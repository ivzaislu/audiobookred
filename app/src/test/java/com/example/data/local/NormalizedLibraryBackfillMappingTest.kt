package com.example.data.local

import com.example.data.cache.AppCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.data.model.SeriesProgressBookDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizedLibraryBackfillMappingTest {
    @Test
    fun emptyLegacySnapshotMapsToEmptyNormalizedRows() {
        val rows = normalizedLibraryBackfillRows(AppCacheStore.LibraryCache())

        assertTrue(rows.favorites.isEmpty())
        assertTrue(rows.history.isEmpty())
        assertTrue(rows.series.isEmpty())
        assertTrue(rows.referencedCards.isEmpty())
    }

    @Test
    fun favoritesAndHistoryPreserveFirstOccurrenceOrderWithDenseRanks() {
        val snapshot = AppCacheStore.LibraryCache(
            favorites = listOf(
                card("favorite-a", "A first"),
                card("favorite-b", "B"),
                card("favorite-a", "A duplicate"),
                card("", "invalid"),
            ),
            history = listOf(
                card("history-c", "C first"),
                card("history-a", "A"),
                card("history-c", "C duplicate"),
            ),
            savedAtMs = 1234L,
        )

        val rows = normalizedLibraryBackfillRows(snapshot)

        assertEquals(listOf("favorite-a", "favorite-b", ""), rows.favorites.map { it.bookId })
        assertEquals(listOf(0, 1, 2), rows.favorites.map { it.rank })
        assertEquals(listOf(1234L, 1234L, 1234L), rows.favorites.map { it.addedAtMs })
        assertEquals(listOf("history-c", "history-a"), rows.history.map { it.bookId })
        assertEquals(listOf(0, 1), rows.history.map { it.rank })
        assertEquals(listOf(1234L, 1234L), rows.history.map { it.lastPlayedAtMs })
    }

    @Test
    fun seriesPreserveSemanticFieldsAndUseStableIdentity() {
        val current = card("book-current", "Current")
        val next = card("book-next", "Next")
        val external = MySeriesDto(
            id = "series-id",
            name = "Series Name",
            provider = "Provider",
            externalId = "external-42",
            sourceName = "Source",
            availableCount = 7,
            totalCount = 9,
            completedCount = 2,
            inProgressCount = 1,
            notStartedCount = 4,
            status = "active",
            lastActivityAt = "777",
            currentBook = SeriesProgressBookDto(
                book = current,
                position = 3.5,
                state = "in_progress",
                progressPercent = 41.25,
            ),
            nextBook = SeriesProgressBookDto(
                book = next,
                position = 4.0,
                state = "not_started",
                progressPercent = 0.0,
            ),
        )
        val fallbackIdentity = MySeriesDto(
            id = "fallback-id",
            name = "Fallback Name",
            provider = "OtherProvider",
            externalId = "",
            status = "completed",
            lastActivityAt = "not-a-number",
        )

        val rows = normalizedLibraryBackfillRows(
            AppCacheStore.LibraryCache(
                series = listOf(
                    external,
                    external.copy(id = "duplicate-wins-never"),
                    fallbackIdentity,
                ),
                savedAtMs = 555L,
            )
        )

        assertEquals(2, rows.series.size)

        val first = rows.series[0]
        assertEquals("provider:external-42", first.seriesKey)
        assertEquals(0, first.rank)
        assertEquals(external.id, first.id)
        assertEquals(external.name, first.name)
        assertEquals(external.provider, first.provider)
        assertEquals(external.externalId, first.externalId)
        assertEquals(external.sourceName, first.sourceName)
        assertEquals(external.availableCount, first.availableCount)
        assertEquals(external.totalCount, first.totalCount)
        assertEquals(external.completedCount, first.completedCount)
        assertEquals(external.inProgressCount, first.inProgressCount)
        assertEquals(external.notStartedCount, first.notStartedCount)
        assertEquals(external.status, first.status)
        assertEquals(external.lastActivityAt, first.lastActivityAt)
        assertEquals("book-current", first.currentBookId)
        assertEquals(3.5, first.currentPosition ?: error("missing current position"), 0.0)
        assertEquals("in_progress", first.currentState)
        assertEquals(41.25, first.currentProgressPercent ?: error("missing current progress"), 0.0)
        assertEquals("book-next", first.nextBookId)
        assertEquals(4.0, first.nextPosition ?: error("missing next position"), 0.0)
        assertEquals("not_started", first.nextState)
        assertEquals(0.0, first.nextProgressPercent ?: error("missing next progress"), 0.0)
        assertEquals(777L, first.updatedAtMs)

        val second = rows.series[1]
        assertEquals("otherprovider:fallback name", second.seriesKey)
        assertEquals(1, second.rank)
        assertEquals(555L, second.updatedAtMs)
    }

    @Test
    fun referencedCardsCoverFavoritesHistoryAndSeriesWithoutOverwritingFirstOccurrence() {
        val sharedFavorite = card("shared", "Favorite copy")
        val sharedHistory = card("shared", "History copy")
        val seriesCurrent = card("series-current", "Series current")
        val seriesNext = card("series-next", "Series next")

        val rows = normalizedLibraryBackfillRows(
            AppCacheStore.LibraryCache(
                favorites = listOf(sharedFavorite),
                history = listOf(sharedHistory, card("history-only", "History only")),
                series = listOf(
                    MySeriesDto(
                        id = "series",
                        name = "Series",
                        provider = "provider",
                        currentBook = SeriesProgressBookDto(seriesCurrent),
                        nextBook = SeriesProgressBookDto(seriesNext),
                    )
                ),
            )
        )

        assertEquals(
            listOf("shared", "history-only", "series-current", "series-next"),
            rows.referencedCards.map { it.id },
        )
        assertEquals("Favorite copy", rows.referencedCards.first().title)
    }

    @Test
    fun mappingIsDeterministicForSameLegacySnapshot() {
        val snapshot = AppCacheStore.LibraryCache(
            favorites = listOf(card("favorite", "Favorite")),
            history = listOf(card("history", "History")),
            series = listOf(
                MySeriesDto(
                    id = "series",
                    name = "Series",
                    provider = "provider",
                    currentBook = SeriesProgressBookDto(card("current", "Current")),
                )
            ),
            savedAtMs = 42L,
        )

        assertEquals(
            normalizedLibraryBackfillRows(snapshot),
            normalizedLibraryBackfillRows(snapshot),
        )
    }

    private fun card(id: String, title: String): BookCardDto =
        BookCardDto(id = id, title = title)
}
