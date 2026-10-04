package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUiStateTest {
    @Test
    fun emptyQueryKeepsSelectedSourceEvenWhenGlobalSearchIsEnabled() {
        val state = CatalogUiState(
            selectedSource = "rutracker",
            query = "",
            searchAllSources = true,
        )

        assertEquals("rutracker", state.requestSource)
        assertTrue(state.selectedSourceIsApplied)
    }

    @Test
    fun nonEmptyGlobalSearchRemovesSourceConstraint() {
        val state = CatalogUiState(
            selectedSource = "rutracker",
            query = "книга",
            searchAllSources = true,
        )

        assertNull(state.requestSource)
        assertFalse(state.selectedSourceIsApplied)
    }

    @Test
    fun nonEmptyLocalSearchKeepsSelectedSource() {
        val state = CatalogUiState(
            selectedSource = "rutracker",
            query = "книга",
            searchAllSources = false,
        )

        assertEquals("rutracker", state.requestSource)
        assertTrue(state.selectedSourceIsApplied)
    }
}
