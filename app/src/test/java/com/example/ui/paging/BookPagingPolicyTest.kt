package com.example.ui.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookPagingPolicyTest {
    @Test
    fun unknownTotalContinuesWhilePageHasItems() {
        assertEquals(
            2,
            nextBookPageKey(page = 1, limit = 30, total = 0, itemsCount = 12),
        )
    }

    @Test
    fun knownTotalStopsAtEnd() {
        assertNull(
            nextBookPageKey(page = 2, limit = 30, total = 60, itemsCount = 30),
        )
    }

    @Test
    fun knownTotalContinuesBeforeEnd() {
        assertEquals(
            2,
            nextBookPageKey(page = 1, limit = 30, total = 31, itemsCount = 30),
        )
    }

    @Test
    fun emptyPageAlwaysStops() {
        assertNull(
            nextBookPageKey(page = 2, limit = 30, total = 100, itemsCount = 0),
        )
    }

    @Test
    fun largePageUsesLongForConsumedCount() {
        assertNull(
            nextBookPageKey(
                page = 100_000_000,
                limit = 30,
                total = Int.MAX_VALUE,
                itemsCount = 30,
            ),
        )
    }

    @Test
    fun maximumPageNeverWrapsToNegativeKey() {
        assertNull(
            nextBookPageKey(
                page = Int.MAX_VALUE,
                limit = 30,
                total = 0,
                itemsCount = 30,
            ),
        )
    }
}
