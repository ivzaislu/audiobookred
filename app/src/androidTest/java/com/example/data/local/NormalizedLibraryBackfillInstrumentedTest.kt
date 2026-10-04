package com.example.data.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.cache.AppCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.data.model.SeriesProgressBookDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NormalizedLibraryBackfillInstrumentedTest {
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
    fun migrateOnceCopiesLegacyRowsMetadataAndRetention() = runBlocking {
        val store = LocalCacheStore(context)
        store.writeBookShellsIfAbsent(
            listOf(card("series-current", "Fresher retained metadata"))
        )
        val snapshot = AppCacheStore.LibraryCache(
            favorites = listOf(card("favorite", "Favorite")),
            history = listOf(card("history", "History")),
            series = listOf(
                MySeriesDto(
                    id = "series-id",
                    name = "Series",
                    provider = "provider",
                    externalId = "series-external",
                    lastActivityAt = "321",
                    currentBook = SeriesProgressBookDto(
                        book = card("series-current", "Series Current"),
                        position = 2.0,
                        state = "in_progress",
                        progressPercent = 25.0,
                    ),
                    nextBook = SeriesProgressBookDto(
                        book = card("series-next", "Series Next"),
                        position = 3.0,
                        state = "not_started",
                    ),
                )
            ),
            savedAtMs = 123L,
        )
        seedLegacySnapshot(snapshot, savedAtMs = 123L)

        val expected = normalizedLibraryBackfillRows(snapshot)
        val migrated = NormalizedLibraryBackfill(context).migrateOnce()

        assertTrue(migrated)
        val database = AbredDatabase.get(context)
        val dao = database.normalizedLibrary()
        assertEquals(expected.favorites, dao.favorites())
        assertEquals(expected.history, dao.history())
        assertEquals(expected.series, dao.series())
        assertTrue(database.localCatalog().retention("favorite")?.favoriteRef == true)
        assertTrue(database.localCatalog().retention("history")?.historyRef == true)
        assertEquals(
            "Fresher retained metadata",
            store.readBookCard("series-current")?.title,
        )
        assertNotNull(store.readBookCard("series-next"))
        assertNull(database.cachedPayloads().get(LIBRARY_KEY))
        assertNotNull(database.cachedPayloads().get(MIGRATION_MARKER_KEY))
        assertTrue(NormalizedLibraryBackfill.isMigrationComplete(context))
    }

    @Test
    fun migrationMarkerPreventsLegacyReplayAfterNormalizedMutation() = runBlocking {
        val snapshot = AppCacheStore.LibraryCache(
            favorites = listOf(card("legacy-favorite", "Legacy")),
            savedAtMs = 99L,
        )
        seedLegacySnapshot(snapshot, savedAtMs = 99L)

        val backfill = NormalizedLibraryBackfill(context)
        assertTrue(backfill.migrateOnce())

        val store = LocalCacheStore(context)
        store.setFavoriteState("new-favorite", true, card("new-favorite", "New"))

        // Deliberately make the residue disagree with normalized Room.
        seedLegacySnapshot(AppCacheStore.LibraryCache(), savedAtMs = 100L)

        assertFalse(backfill.migrateOnce())
        val database = AbredDatabase.get(context)
        assertEquals(
            listOf("new-favorite", "legacy-favorite"),
            database.normalizedLibrary().favorites().map { it.bookId },
        )
        assertNull(database.cachedPayloads().get(LIBRARY_KEY))
    }

    @Test
    fun migrationWithoutLegacyPreservesExistingNormalizedRows() = runBlocking {
        val store = LocalCacheStore(context)
        store.setFavoriteState("existing", true, card("existing", "Existing"))
        assertNull(AbredDatabase.get(context).cachedPayloads().get(LIBRARY_KEY))

        val migrated = NormalizedLibraryBackfill(context).migrateOnce()

        assertTrue(migrated)
        assertEquals(
            listOf("existing"),
            AbredDatabase.get(context).normalizedLibrary().favorites().map { it.bookId },
        )
        assertTrue(AbredDatabase.get(context).localCatalog().retention("existing")?.favoriteRef == true)
    }

    @Test
    fun libraryCacheStoreIgnoresStaleLegacyResidue() = runBlocking {
        val legacy = AppCacheStore.LibraryCache(
            favorites = listOf(card("favorite", "Favorite")),
            history = listOf(card("history", "History")),
            savedAtMs = 123L,
        )
        seedLegacySnapshot(legacy, savedAtMs = 123L)
        NormalizedLibraryBackfill(context).migrateOnce()

        // Simulate an early v8 prerelease reintroducing stale legacy residue.
        // Normalized reads must ignore it, and a later bridge pass deletes it.
        seedLegacySnapshot(AppCacheStore.LibraryCache(), savedAtMs = 124L)

        val libraryCacheStore = LibraryCacheStore(
            context = context,
            cacheStore = LocalCacheStore(context),
            normalizedStore = NormalizedLibraryStore(context),
        )
        val snapshot = libraryCacheStore.read()

        assertEquals(listOf("favorite"), snapshot.library.favorites.map { it.id })
        assertEquals(listOf("history"), snapshot.library.history.map { it.id })
    }

    @Test
    fun pruneKeepsSeriesOnlyMetadataReferencedByNormalizedLibrary() = runBlocking {
        val store = LocalCacheStore(context)
        store.promoteSeriesState(
            listOf(
                MySeriesDto(
                    id = "series",
                    name = "Series",
                    provider = "provider",
                    currentBook = SeriesProgressBookDto(card("series-current", "Current")),
                    nextBook = SeriesProgressBookDto(card("series-next", "Next")),
                )
            )
        )

        val database = AbredDatabase.get(context)
        database.localCatalog().pruneUnreferencedBooks()

        assertNotNull(database.localCatalog().book("series-current"))
        assertNotNull(database.localCatalog().book("series-next"))
        assertEquals(2, database.localCatalog().retainedOutsideWindowCount())
        assertTrue(database.localCatalog().retainedMetadataBytes() > 0L)
    }

    private suspend fun seedLegacySnapshot(
        snapshot: AppCacheStore.LibraryCache,
        savedAtMs: Long,
    ) {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(AppCacheStore.LibraryCache::class.java)
        AbredDatabase.get(context).cachedPayloads().put(
            CachedPayloadEntity(
                cacheKey = LIBRARY_KEY,
                payloadJson = adapter.toJson(snapshot),
                savedAtMs = savedAtMs,
            )
        )
    }

    private fun card(id: String, title: String): BookCardDto =
        BookCardDto(id = id, title = title)

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val LIBRARY_KEY = "library:v1"
        const val MIGRATION_MARKER_KEY = "library:normalized-room:v1"
    }
}
