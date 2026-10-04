package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCacheRetentionBatchTest {
    @Test
    fun batchMergePreservesProgressBookmarkAndDownloadFlags() {
        val now = 1234L
        val merged = mergeLibraryRetentionRows(
            existing = listOf(
                BookRetentionEntity(
                    bookId = "book-1",
                    progressRef = true,
                    bookmarkRef = true,
                    downloadedRef = true,
                    updatedAtMs = 1L,
                )
            ),
            favoriteIds = setOf("book-1"),
            historyIds = emptySet(),
            now = now,
        )

        val row = merged.single()
        assertTrue(row.favoriteRef)
        assertFalse(row.historyRef)
        assertTrue(row.progressRef)
        assertTrue(row.bookmarkRef)
        assertTrue(row.downloadedRef)
        assertEquals(now, row.updatedAtMs)
    }

    @Test
    fun batchMergeCombinesFavoriteAndHistoryIntoSingleRow() {
        val merged = mergeLibraryRetentionRows(
            existing = emptyList(),
            favoriteIds = linkedSetOf("shared", "favorite-only"),
            historyIds = linkedSetOf("shared", "history-only"),
            now = 99L,
        ).associateBy(BookRetentionEntity::bookId)

        assertEquals(3, merged.size)
        assertTrue(merged.getValue("shared").favoriteRef)
        assertTrue(merged.getValue("shared").historyRef)
        assertTrue(merged.getValue("favorite-only").favoriteRef)
        assertFalse(merged.getValue("favorite-only").historyRef)
        assertFalse(merged.getValue("history-only").favoriteRef)
        assertTrue(merged.getValue("history-only").historyRef)
    }
}
