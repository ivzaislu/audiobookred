package com.example.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogPageBufferTest {
    @Test
    fun providerSurplusIsCarriedIntoFollowingLogicalPages() = runBlocking {
        val buffer = CatalogPageBuffer<Int>()
        val physical = mapOf(
            1 to (0 until 80).toList(),
            2 to (80 until 100).toList(),
            3 to emptyList(),
        )
        val calls = mutableListOf<Int>()
        suspend fun load(page: Int): CatalogPhysicalBatch<Int> {
            calls += page
            val items = physical[page].orEmpty()
            return CatalogPhysicalBatch(items = items, terminal = items.isEmpty())
        }

        val pages = (1..4).map { page ->
            buffer.page(page = page, limit = 30) { physicalPage -> load(physicalPage) }
        }

        assertEquals((0 until 30).toList(), pages[0].items)
        assertEquals((30 until 60).toList(), pages[1].items)
        assertEquals((60 until 90).toList(), pages[2].items)
        assertEquals((90 until 100).toList(), pages[3].items)
        assertEquals(100, pages[3].total)
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals((0 until 100).toList(), pages.flatMap { it.items })
    }

    @Test
    fun temporaryEmptyBatchDoesNotAdvancePhysicalCursor() = runBlocking {
        val buffer = CatalogPageBuffer<Int>()
        var attempt = 0
        suspend fun load(page: Int): CatalogPhysicalBatch<Int> {
            assertEquals(1, page)
            attempt++
            return if (attempt == 1) {
                CatalogPhysicalBatch(items = emptyList(), terminal = false)
            } else {
                CatalogPhysicalBatch(items = listOf(1, 2, 3), terminal = false)
            }
        }

        assertTrue(buffer.page(1, 2) { physicalPage -> load(physicalPage) }.items.isEmpty())
        assertEquals(listOf(1, 2), buffer.page(1, 2) { physicalPage -> load(physicalPage) }.items)
        assertEquals(2, attempt)
    }

    @Test
    fun aggregateFailureSealsShortPageAndRetriesSameProviderPhysicalPage() = runBlocking {
        val buffer = AggregateCatalogPageBuffer<String, String>(
            providers = listOf("a", "b"),
            pageSize = 3,
        )
        val calls = mutableListOf<Pair<String, Int>>()
        var firstBFailure = true

        suspend fun load(provider: String, page: Int): CatalogPhysicalBatch<String> {
            calls += provider to page
            return when (provider) {
                "a" -> when (page) {
                    1 -> CatalogPhysicalBatch(listOf("a1", "a2"))
                    else -> CatalogPhysicalBatch(emptyList(), terminal = true)
                }
                "b" -> {
                    if (page == 1 && firstBFailure) {
                        firstBFailure = false
                        error("temporary b failure")
                    }
                    when (page) {
                        1 -> CatalogPhysicalBatch(listOf("b1", "b2"))
                        else -> CatalogPhysicalBatch(emptyList(), terminal = true)
                    }
                }
                else -> error("unexpected provider")
            }
        }

        val first = buffer.page(1) { provider, physicalPage -> load(provider, physicalPage) }
        val second = buffer.page(2) { provider, physicalPage -> load(provider, physicalPage) }

        assertEquals(listOf("a1", "a2"), first.items)
        assertEquals(0, first.total)
        assertEquals(listOf("b1", "b2"), second.items)
        assertEquals(4, second.total)
        assertEquals(
            listOf(1, 1, 2),
            calls.filter { it.first == "b" }.map { it.second },
        )
    }

    @Test
    fun aggregateAllProviderFailureIsRetryableInsteadOfLookingLikeEndOfCatalog() = runBlocking {
        val buffer = AggregateCatalogPageBuffer<String, String>(
            providers = listOf("a"),
            pageSize = 3,
        )
        var failFirst = true

        try {
            buffer.page(1) { _, _ ->
                if (failFirst) {
                    failFirst = false
                    error("temporary provider failure")
                }
                CatalogPhysicalBatch(listOf("a1"), terminal = true)
            }
            fail("Expected provider failure")
        } catch (error: IllegalStateException) {
            assertEquals("temporary provider failure", error.message)
        }

        val recovered = buffer.page(1) { _, page ->
            assertEquals(1, page)
            CatalogPhysicalBatch(listOf("a1"), terminal = true)
        }
        assertEquals(listOf("a1"), recovered.items)
        assertEquals(1, recovered.total)
    }
}
