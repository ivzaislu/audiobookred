package com.example.data.textbook

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TextLibraryActivityStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "test_text_library_activity"
    private val id = "txt_" + "a".repeat(64)
    @Before fun setUp() { clear() }
    @After fun tearDown() { clear() }
    private fun clear() { context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit() }
    private fun store() = TextLibraryActivityStore(context, preferencesName)

    @Test fun restoreHideAndVisitLeaveBookIdentityIntact() {
        val store = store()
        store.catalog(TextLibraryActivity(id, "source", "Источник", "123", "cycle", "Цикл", "1.5"))
        store.visit(id)
        store.hideHistory(id)
        store.hideSeries("text:source:cycle")
        val restored = store().state.value.getValue(id)
        assertFalse(restored.historyVisible)
        assertFalse(restored.seriesVisible)
        assertEquals("1.5", restored.seriesNumber)
        assertTrue(restored.lastActivityAtMs > 0)
        store.restoreActivity(id, 1L) // Legacy backfill must not resurrect explicitly hidden entries.
        assertFalse(store.state.value.getValue(id).historyVisible)
        store.visit(id)
        assertTrue(store.state.value.getValue(id).historyVisible)
        assertTrue(store.state.value.getValue(id).seriesVisible)
    }

    @Test fun catalogRefreshPreservesActivityAndDoesNotStartUnopenedBooks() {
        val store = store()
        store.catalog(TextLibraryActivity(id, "source", seriesId = "cycle", seriesName = "Цикл"))
        assertEquals(0L, store.state.value.getValue(id).lastActivityAtMs)
        store.visit(id)
        val time = store.state.value.getValue(id).lastActivityAtMs
        store.catalog(TextLibraryActivity(id, "source", seriesId = "cycle", seriesName = "Новое имя"))
        assertEquals(time, store().state.value.getValue(id).lastActivityAtMs)
        assertTrue(store().state.value.getValue(id).historyVisible)
        store.remove(id)
        assertTrue(store().state.value.isEmpty())
    }
}
