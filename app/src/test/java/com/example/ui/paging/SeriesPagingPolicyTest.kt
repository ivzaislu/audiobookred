package com.example.ui.paging

import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookDetailDto
import com.example.data.model.SourceVariantDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesPagingPolicyTest {
    @Test
    fun audiobooShortLogicalPageContinuesWhileTotalHasMore() {
        assertEquals(
            2,
            nextSeriesPageKey(
                page = 1,
                requestedLimit = 30,
                provider = "audioboo",
                totalCount = 35,
                entriesCount = 12,
            ),
        )
    }

    @Test
    fun audiobooStopsAfterLastLogicalPage() {
        assertNull(
            nextSeriesPageKey(
                page = 3,
                requestedLimit = 30,
                provider = "audioboo",
                totalCount = 35,
                entriesCount = 11,
            ),
        )
    }

    @Test
    fun audiobooExactKnownTotalDoesNotProbeExtraPage() {
        assertNull(
            nextSeriesPageKey(
                page = 1,
                requestedLimit = 30,
                provider = "audioboo",
                totalCount = 12,
                entriesCount = 12,
            ),
        )
    }

    @Test
    fun ordinaryProviderStillTreatsShortUnknownPageAsEnd() {
        assertNull(
            nextSeriesPageKey(
                page = 1,
                requestedLimit = 30,
                provider = "audiopolka",
                totalCount = 0,
                entriesCount = 12,
            ),
        )
    }

    @Test
    fun audiopolkaFullPageWithPageLocalTotalContinues() {
        assertEquals(
            2,
            nextSeriesPageKey(
                page = 1,
                requestedLimit = 30,
                provider = "audiopolka",
                totalCount = 30,
                entriesCount = 30,
            ),
        )
    }

    @Test
    fun uknigFullPageWithPageLocalTotalContinues() {
        assertEquals(
            3,
            nextSeriesPageKey(
                page = 2,
                requestedLimit = 30,
                provider = "uknig",
                totalCount = 30,
                entriesCount = 30,
            ),
        )
    }

    @Test
    fun bazaFullPageWithPageLocalTotalContinues() {
        assertEquals(
            2,
            nextSeriesPageKey(
                page = 1,
                requestedLimit = 30,
                provider = "bazaknig",
                totalCount = 30,
                entriesCount = 30,
            ),
        )
    }

    @Test
    fun knigavuheIsAvailableAsStandaloneSeriesOption() {
        val book = BookDetailDto(
            id = "knigavuhe:test-book",
            title = "Тест",
            selectedSource = "knigavuhe",
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:knigavuhe:test-cycle",
                    name = "Тестовый цикл",
                    position = 1.0,
                    provider = "knigavuhe",
                    externalId = "test-cycle",
                    sourceName = "",
                )
            ),
        )

        val option = seriesSwitchOptions(book).single()
        val request = option.request as SeriesPageRequest.Source

        assertEquals("knigavuhe", request.provider)
        assertEquals("Книга в ухе", option.typeLabel)
        assertEquals("Тестовый цикл", option.title)
    }

    @Test
    fun bazaKnigIsAvailableAsStandaloneSeriesOption() {
        val book = BookDetailDto(
            id = "bazaknig:123-test-book",
            title = "Тест",
            selectedSource = "bazaknig",
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:bazaknig:test-cycle",
                    name = "Тестовый цикл Baza",
                    position = 2.0,
                    provider = "bazaknig",
                    externalId = "test-cycle",
                    sourceName = "",
                )
            ),
        )

        val option = seriesSwitchOptions(book).single()
        val request = option.request as SeriesPageRequest.Source

        assertEquals("bazaknig", request.provider)
        assertEquals("Baza-Knig", option.typeLabel)
        assertEquals("Тестовый цикл Baza", option.title)
    }

    @Test
    fun largePageUsesLongForConsumedCount() {
        assertNull(
            nextSeriesPageKey(
                page = 100_000_000,
                requestedLimit = 30,
                provider = "audiopolka",
                totalCount = Int.MAX_VALUE,
                entriesCount = 30,
            ),
        )
    }

    @Test
    fun maximumPageNeverWrapsToNegativeKey() {
        assertNull(
            nextSeriesPageKey(
                page = Int.MAX_VALUE,
                requestedLimit = 30,
                provider = "uknig",
                totalCount = 0,
                entriesCount = 30,
            ),
        )
    }

    @Test
    fun emptyPageAlwaysStops() {
        assertNull(
            nextSeriesPageKey(
                page = 2,
                requestedLimit = 30,
                provider = "audioboo",
                totalCount = 100,
                entriesCount = 0,
            ),
        )
    }
    @Test
    fun crossProviderSeriesUsesMatchingProviderBookSeed() {
        val book = BookDetailDto(
            id = "audioboo:fantastika/100-main.html",
            title = "Тест",
            selectedSource = "audioboo",
            selectedBookSourceId = "live:audioboo:fantastika/100-main.html",
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = "live:audioboo:fantastika/100-main.html",
                    sourceCode = "audioboo",
                    sourceName = "Audioboo",
                    seriesName = "Основной цикл",
                ),
                SourceVariantDto(
                    bookSourceId = "live:bazaknig:200-alt-book",
                    sourceCode = "bazaknig",
                    sourceName = "Baza-Knig",
                    seriesName = "Альтернативный цикл",
                ),
            ),
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:bazaknig:alt-cycle",
                    name = "Альтернативный цикл",
                    provider = "bazaknig",
                    externalId = "alt-cycle",
                )
            ),
        )

        val option = seriesSwitchOptions(book).single { it.title == "Альтернативный цикл" }
        val request = option.request as SeriesPageRequest.Source

        assertEquals("bazaknig", request.provider)
        assertEquals("bazaknig:200-alt-book", request.bookId)
    }

    @Test
    fun crossProviderSeriesWithoutMatchingBookSeedIsNotExposed() {
        val book = BookDetailDto(
            id = "audioboo:fantastika/100-main.html",
            title = "Тест",
            selectedSource = "audioboo",
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:bazaknig:alt-cycle",
                    name = "Недоступный вариант",
                    provider = "bazaknig",
                    externalId = "alt-cycle",
                )
            ),
        )

        assertEquals(emptyList<SeriesSwitchOption>(), seriesSwitchOptions(book))
    }

    @Test
    fun liveBookSourceIdBecomesParserBookKey() {
        assertEquals(
            "myaudiobooks:litrpg/42-test.html",
            seriesSeedBookIdFromVariant(
                "live:myaudiobooks:litrpg/42-test.html",
                "myaudiobooks",
            ),
        )
    }

}
