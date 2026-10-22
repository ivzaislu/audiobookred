package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextLibraryActivityTest {
    private fun id(number: Int) = "txt_" + number.toString(16).padStart(64, '0')
    private fun record(number: Int, source: String = "fantasy_worlds", series: String = "cycle", position: String = "$number") =
        TextLibraryActivity(id(number), source, "Источник", "$number", series, "Цикл", position)

    @Test fun importAloneCreatesNeitherHistoryNorAnActiveCycle() {
        val book = record(1)
        assertTrue(textLibraryHistory(listOf(book), setOf(book.bookId)).isEmpty())
        assertTrue(textLibraryCycles(listOf(book), setOf(book.bookId)).isEmpty())
    }

    @Test fun liveAndWavPlaybackResolveToOneTextIdentity() {
        assertEquals(id(1), textLibraryPlaybackBookId("text_narration", id(1), ""))
        assertEquals(id(1), textLibraryPlaybackBookId("text_audio", "export-id", "text_audio:${id(1)}:whole"))
        assertEquals(id(1), textLibraryPlaybackBookId("text_audio", "chapter-export", "text_audio:${id(1)}:chapter"))
        assertNull(textLibraryPlaybackBookId("provider", id(1), ""))
        assertNull(textLibraryPlaybackBookId("text_audio", "export-id", "text_audio:broken:whole"))
    }

    @Test fun historyDeduplicatesThroughStableBookIdentityAndSortsByActivity() {
        val records = listOf(record(1).visited(10).visited(40), record(2).visited(30))
        assertEquals(listOf(id(1), id(2)), textLibraryHistory(records, records.map { it.bookId }.toSet()).map { it.bookId })
    }

    @Test fun hiddenHistoryKeepsCycleAndCatalogIdentityAndReturnsOnNextVisit() {
        val original = record(1).visited(10)
        val hidden = original.copy(historyVisible = false)
        assertTrue(textLibraryHistory(listOf(hidden), setOf(id(1))).isEmpty())
        assertEquals(1, textLibraryCycles(listOf(hidden), setOf(id(1))).size)
        assertEquals(original.copy(lastActivityAtMs = 20), hidden.visited(20))
    }

    @Test fun cycleContainsUnstartedDownloadedBooksAndOrdersFractionalPositions() {
        val records = listOf(record(1, position = "10"), record(2, position = "1,5").visited(10), record(3, position = "2"))
        assertEquals(listOf(id(2), id(3), id(1)), textLibraryCycles(records, records.map { it.bookId }.toSet()).single().map { it.bookId })
    }

    @Test fun identicalCycleIdsFromDifferentSourcesStaySeparate() {
        val records = listOf(record(1, source = "one").visited(10), record(2, source = "two").visited(20))
        assertEquals(listOf("text:two:cycle", "text:one:cycle"), textLibraryCycles(records, records.map { it.bookId }.toSet()).map { it.first().seriesKey })
    }

    @Test fun hiddenCycleDoesNotHideHistoryAndIsPromotedOnNewActivity() {
        val hidden = record(1).visited(10).copy(seriesVisible = false)
        assertTrue(textLibraryCycles(listOf(hidden), setOf(id(1))).isEmpty())
        assertEquals(1, textLibraryHistory(listOf(hidden), setOf(id(1))).size)
        assertEquals(1, textLibraryCycles(listOf(hidden.visited(20)), setOf(id(1))).size)
    }

    @Test fun removedBooksAndMissingCyclesAreExcluded() {
        val records = listOf(record(1).visited(10), record(2, series = "").visited(20))
        assertTrue(textLibraryCycles(records, setOf(id(2))).isEmpty())
        assertEquals(listOf(id(2)), textLibraryHistory(records, setOf(id(2))).map { it.bookId })
    }

    @Test fun stateAndVisibilitySurviveSerialization() {
        val value = record(1, position = "2.5").visited(123).copy(historyVisible = false, seriesVisible = false)
        assertEquals(value, decodeTextLibraryActivity(encodeTextLibraryActivity(value)))
        assertNull(decodeTextLibraryActivity("broken"))
        assertNull(decodeTextLibraryActivity("{\"version\":2,\"bookId\":\"${id(1)}\"}"))
    }
}
