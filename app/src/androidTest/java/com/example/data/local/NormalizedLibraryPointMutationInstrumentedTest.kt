package com.example.data.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.data.model.SeriesProgressBookDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NormalizedLibraryPointMutationInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun concurrentFavoriteAndHistoryMutationsPreserveBothCategories() = runBlocking {
        val first = LocalCacheStore(context)
        val second = LocalCacheStore(context)
        val favorite = async(Dispatchers.IO) {
            first.setFavoriteState(
                bookId = FAVORITE_ID,
                favorite = true,
                card = card(FAVORITE_ID, "Favorite"),
            )
        }
        val history = async(Dispatchers.IO) {
            second.recordHistoryState(card(HISTORY_ID, "History"))
        }
        favorite.await()
        history.await()

        val normalized = NormalizedLibraryStore(context).read()
        assertEquals(listOf(FAVORITE_ID), normalized.library.favorites.map { it.id })
        assertEquals(listOf(HISTORY_ID), normalized.library.history.map { it.id })

        assertNull(AbredDatabase.get(context).cachedPayloads().get(LIBRARY_KEY))
    }

    @Test
    fun twoConcurrentFavoriteTogglesReturnToOriginalState() = runBlocking {
        val first = LocalCacheStore(context)
        val second = LocalCacheStore(context)
        val book = card(FAVORITE_ID, "Favorite")

        val a = async(Dispatchers.IO) {
            first.toggleFavoriteState(FAVORITE_ID, book)
        }
        val b = async(Dispatchers.IO) {
            second.toggleFavoriteState(FAVORITE_ID, book)
        }
        a.await()
        b.await()

        val database = AbredDatabase.get(context)
        assertEquals(0, database.normalizedLibrary().favoriteCount())
        assertFalse(database.localCatalog().retention(FAVORITE_ID)?.favoriteRef ?: false)
        assertNull(database.cachedPayloads().get(LIBRARY_KEY))
    }

    @Test
    fun favoritePromotionKeepsDenseRanks() = runBlocking {
        val store = LocalCacheStore(context)
        store.setFavoriteState("a", true, card("a", "A"))
        store.setFavoriteState("b", true, card("b", "B"))
        store.setFavoriteState("c", true, card("c", "C"))
        store.setFavoriteState("a", true, card("a", "A refreshed"))

        val rows = AbredDatabase.get(context).normalizedLibrary().favorites()
        assertEquals(listOf("a", "c", "b"), rows.map { it.bookId })
        assertEquals(listOf(0, 1, 2), rows.map { it.rank })
    }

    @Test
    fun historyPromotionAndRemovalKeepDenseRanks() = runBlocking {
        val store = LocalCacheStore(context)
        store.recordHistoryState(card("a", "A"))
        store.recordHistoryState(card("b", "B"))
        store.recordHistoryState(card("c", "C"))
        store.recordHistoryState(card("a", "A refreshed"))

        val dao = AbredDatabase.get(context).normalizedLibrary()
        assertEquals(listOf("a", "c", "b"), dao.history().map { it.bookId })
        assertEquals(listOf(0, 1, 2), dao.history().map { it.rank })

        store.removeHistoryState("c")
        assertEquals(listOf("a", "b"), dao.history().map { it.bookId })
        assertEquals(listOf(0, 1), dao.history().map { it.rank })
    }

    @Test
    fun seriesPromotionPreservesUnrelatedRowsAndOrder() = runBlocking {
        val store = LocalCacheStore(context)
        val seriesA = series("a", "A", "book-a")
        val seriesB = series("b", "B", "book-b")
        store.promoteSeriesState(listOf(seriesA, seriesB))

        val refreshedB = seriesB.copy(
            status = "completed",
            lastActivityAt = "22",
            completedCount = 1,
        )
        val seriesC = series("c", "C", "book-c")
        store.promoteSeriesState(listOf(refreshedB, seriesC))

        val normalized = NormalizedLibraryStore(context).read().library.series
        assertEquals(listOf("b", "c", "a"), normalized.map { it.id })
        assertEquals("completed", normalized.first().status)

        val rows = AbredDatabase.get(context).normalizedLibrary().series()
        assertEquals(listOf(0, 1, 2), rows.map { it.rank })

        // Live mutations never create the retired whole-library payload.
        assertNull(AbredDatabase.get(context).cachedPayloads().get(LIBRARY_KEY))
    }

    @Test
    fun removingSeriesUsesProviderScopedIdentityWhenIdsCollide() = runBlocking {
        val store = LocalCacheStore(context)
        val first = MySeriesDto(
            id = "shared-id",
            name = "First",
            provider = "provider-a",
            externalId = "series-a",
            currentBook = SeriesProgressBookDto(card("book-a", "A")),
        )
        val second = MySeriesDto(
            id = "shared-id",
            name = "Second",
            provider = "provider-b",
            externalId = "series-b",
            currentBook = SeriesProgressBookDto(card("book-b", "B")),
        )
        store.promoteSeriesState(listOf(first, second))

        assertEquals(true, store.removeMySeriesState(first))

        val remaining = AbredDatabase.get(context).normalizedLibrary().series()
        assertEquals(listOf("provider-b:series-b"), remaining.map { it.seriesKey })
        assertEquals(listOf(0), remaining.map { it.rank })
    }

    private fun card(id: String, title: String): BookCardDto =
        BookCardDto(id = id, title = title)

    private fun series(id: String, name: String, bookId: String): MySeriesDto =
        MySeriesDto(
            id = id,
            name = name,
            provider = "provider",
            externalId = id,
            currentBook = SeriesProgressBookDto(
                book = card(bookId, "Book "+id),
                position = 1.0,
                state = "in_progress",
                progressPercent = 10.0,
            ),
        )

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val FAVORITE_ID = "favorite-point-book"
        const val HISTORY_ID = "history-point-book"
        const val LIBRARY_KEY = "library:v1"
    }
}
