package com.example.ui

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.*
import org.junit.Test

class TextNavigationHistoryTest {
    @Test fun playerReturnsToTheExistingBookCardInsteadOfAddingAnotherCopy() {
        val book = TextBookDestination("one")
        val history = mutableListOf<NavKey>(TopLevelDestination.Library, book, PlayerDestination)
        history.openOrReturnTo(TextBookDestination("one"))
        assertEquals(listOf(TopLevelDestination.Library, book), history)
        assertSame(book, history.last())
    }

    @Test fun aCycleBookCycleRoundTripReturnsToTheExistingCycle() {
        val book = TextBookDestination("one")
        val cycle = TextCycleDestination("cycle")
        val history = mutableListOf<NavKey>(TopLevelDestination.Library, book, cycle, TextBookDestination("two"))
        history.openOrReturnTo(TextCycleDestination("cycle"))
        assertEquals(listOf(TopLevelDestination.Library, book, cycle), history)
    }

    @Test fun audioPositionCanReturnToAnExistingReaderAndLegacyDuplicateEntriesAreCollapsed() {
        val reader = TextReaderDestination("one")
        val history = mutableListOf<NavKey>(TopLevelDestination.Library, reader, TextBookDestination("one"),
            TextReaderDestination("one"), PlayerDestination)
        history.openOrReturnTo(TextReaderDestination("one"))
        assertEquals(listOf(TopLevelDestination.Library, reader), history)
        assertSame(reader, history.last())
        history.openOrReturnTo(TextBookDestination("two"))
        assertEquals(TextBookDestination("two"), history.last())
        assertEquals(3, history.size)
        history.openOrReturnTo(TextBookDestination("two"))
        assertEquals(3, history.size)
    }
}
