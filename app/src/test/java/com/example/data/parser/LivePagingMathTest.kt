package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePagingMathTest {
    @Test
    fun logicalPageMapsAcrossPhysicalBoundary() {
        assertEquals(
            PhysicalPageWindow(page = 2, skip = 3),
            physicalPageWindow(page = 2, limit = 30, sitePageSize = 27),
        )
    }

    @Test
    fun hugeLogicalPageDoesNotWrapPhysicalPage() {
        assertNull(
            physicalPageWindow(
                page = Int.MAX_VALUE,
                limit = 30,
                sitePageSize = 27,
            )
        )
    }

    @Test
    fun lastRepresentablePhysicalPageHasNoWrappedSuccessor() {
        assertEquals(
            PhysicalPageWindow(page = Int.MAX_VALUE, skip = 0),
            physicalPageWindow(
                page = Int.MAX_VALUE,
                limit = 1,
                sitePageSize = 1,
            ),
        )
        assertNull(nextPhysicalPage(Int.MAX_VALUE))
    }

    @Test
    fun normalPhysicalPageStillAdvances() {
        assertEquals(3, nextPhysicalPage(2))
    }

    @Test
    fun localSliceUsesLongOffset() {
        val values = (1..10).toList()
        assertEquals(listOf(4, 5, 6), values.logicalPageSlice(page = 2, limit = 3))
        assertTrue(values.logicalPageSlice(page = Int.MAX_VALUE, limit = 30).isEmpty())
    }
}
