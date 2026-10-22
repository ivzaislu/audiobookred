package com.example.ui.viewmodel

import com.example.data.textbook.TextBookEntity
import com.example.data.textbook.TextBookMetadata
import com.example.data.textbook.TextLibraryActivity
import org.junit.Assert.*
import org.junit.Test

class TextLibraryMembershipTest {
    private fun book(id: String) = TextBookEntity(id, "Книга $id", "book.txt", "book.source", "hash", "UTF-8", 1, 1, 1, 100, 2, 4)
    private fun record(id: String, position: String) = TextLibraryActivity(id, "source", "Источник", id, "cycle", "Цикл", position)

    @Test fun historyCardsAndCycleNavigationUseLocalTextIds() {
        val activity = mapOf("one" to record("one", "1").visited(10), "two" to record("two", "2.5"))
        val result = textLibraryMembership(listOf(book("one"), book("two")), mapOf("one" to TextBookMetadata(author = "Автор")), activity)
        assertEquals(listOf("one"), result.history.map { it.id })
        assertEquals("Автор", result.history.single().authorText)
        val cycle = result.series.single()
        assertEquals("text:source", cycle.provider)
        assertEquals("one", cycle.currentBook?.book?.id)
        assertEquals("two", cycle.nextBook?.book?.id)
        assertEquals(2.5, cycle.nextBook?.position ?: 0.0, 0.0)
        assertEquals(2.5, result.seriesBooks.getValue(cycle.id).last().series.single().position ?: 0.0, 0.0)
        assertEquals(listOf("one", "two"), result.seriesBooks.getValue(cycle.id).map { it.id })
        assertEquals(2, cycle.availableCount)
        assertEquals(0, cycle.completedCount) // Opening a chapter is not evidence of completing a book.
    }

    @Test fun removingOneBookSelectsRemainingActivityAndDoesNotLeaveBrokenLinks() {
        val activity = mapOf("one" to record("one", "1").visited(20), "two" to record("two", "2").visited(10))
        val result = textLibraryMembership(listOf(book("two")), emptyMap(), activity)
        assertEquals(listOf("two"), result.history.map { it.id })
        assertEquals("two", result.series.single().currentBook?.book?.id)
        assertEquals(1, result.series.single().availableCount)
    }

    @Test fun hiddenHistoryAndHiddenCycleAreIndependent() {
        val hiddenHistory = record("one", "1").visited(10).copy(historyVisible = false)
        val result = textLibraryMembership(listOf(book("one")), emptyMap(), mapOf("one" to hiddenHistory))
        assertTrue(result.history.isEmpty())
        assertEquals(1, result.series.size)
        val hiddenCycle = hiddenHistory.copy(historyVisible = true, seriesVisible = false)
        val next = textLibraryMembership(listOf(book("one")), emptyMap(), mapOf("one" to hiddenCycle))
        assertEquals(1, next.history.size)
        assertTrue(next.series.isEmpty())
    }
    @Test fun listeningProgressAppearsInHistoryWithoutChangingActivityOrMetadata() {
        val activity = mapOf("one" to record("one", "1").visited(10))
        val result = textLibraryMembership(listOf(book("one")), emptyMap(), activity, mapOf("one" to 42.0))
        assertEquals(42.0, result.history.single().progressPercent, 0.0)
        assertEquals(42.0, result.seriesBooks.values.single().single().progressPercent, 0.0)
        assertEquals(10L, activity.getValue("one").lastActivityAtMs)
        val rewound = textLibraryMembership(listOf(book("one")), emptyMap(), activity, mapOf("one" to 0.0))
        assertEquals(0.0, rewound.history.single().progressPercent, 0.0)
        assertEquals("Цикл", rewound.history.single().sourceSeriesName)
    }

}
