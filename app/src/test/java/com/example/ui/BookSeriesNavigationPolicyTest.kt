package com.example.ui

import com.example.data.model.BookDetailDto
import com.example.data.model.SeriesBriefDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSeriesNavigationPolicyTest {
    @Test
    fun myAudiobooksSourceSeriesCanOpenWhenCapabilityIsEnabled() {
        val book = BookDetailDto(
            id = "myaudiobooks:detective/64061-audiokniga-smert-tam-eshhe-ne-pobyvala-reks-staut.html",
            title = "Смерть там ещё не побывала",
            selectedSource = "myaudiobooks",
            primarySource = "myaudiobooks",
            sourceSeriesName = "Ниро Вульф",
            sourceSeriesPosition = 1,
        )

        assertTrue(canOpenBookSeries(book))
    }

    @Test
    fun verifiedSourceSeriesCanOpen() {
        val book = BookDetailDto(
            id = "bazaknig:book_slug-1",
            title = "Book",
            selectedSource = "bazaknig",
            primarySource = "bazaknig",
            sourceSeriesName = "Series",
        )

        assertTrue(canOpenBookSeries(book))
    }

    @Test
    fun rutrackerNeverOpensSeriesEvenWhenStaleMetadataExists() {
        val book = BookDetailDto(
            id = "rutracker:123",
            title = "Book",
            selectedSource = "rutracker",
            primarySource = "rutracker",
            sourceSeriesName = "Старый цикл",
            sourceSeriesPosition = 2,
            series = listOf(
                SeriesBriefDto(
                    id = "legacy:rutracker:series",
                    name = "Старый цикл",
                    isPrimary = true,
                )
            ),
        )

        assertTrue(isRuTrackerBook(book))
        assertFalse(canOpenBookSeries(book))
    }

    @Test
    fun bookSeriesMetadataUsesEnabledSourceCapability() {
        val book = BookDetailDto(
            id = "myaudiobooks:detective/64061-audiokniga-smert-tam-eshhe-ne-pobyvala-reks-staut.html",
            title = "Смерть там ещё не побывала",
            selectedSource = "myaudiobooks",
            series = listOf(
                SeriesBriefDto(
                    id = "source:myaudiobooks:niro-vulf",
                    name = "Ниро Вульф",
                    isPrimary = true,
                )
            ),
        )

        assertTrue(canOpenBookSeries(book))
    }
}
