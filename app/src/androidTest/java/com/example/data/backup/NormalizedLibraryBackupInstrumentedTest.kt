package com.example.data.backup

import android.content.Context
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.cache.AppCacheStore
import com.example.data.local.AbredDatabase
import com.example.data.local.BookRetentionEntity
import com.example.data.local.CachedPayloadEntity
import com.example.data.local.LocalCacheStore
import com.example.data.local.NormalizedLibraryStore
import com.example.data.local.resetAbredDatabaseSingleton
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.MySeriesDto
import com.example.data.model.SeriesProgressBookDto
import com.example.data.player.PlaybackResumeStore
import com.example.data.settings.BookSourcePreferenceStore
import com.example.data.settings.PlayerSettingsStore
import com.example.data.settings.SourceAvailabilityStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NormalizedLibraryBackupInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
    }

    @After
    fun tearDown() {
        PlaybackResumeStore(context).clear(CHECKPOINT_ONLY_ID)
        BookSourcePreferenceStore(context).clear(CHECKPOINT_ONLY_ID)
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun exportReadsNormalizedLibraryInsteadOfStaleLegacyMirror() = runBlocking {
        val cacheStore = LocalCacheStore(context)
        cacheStore.setFavoriteState("favorite", true, card("favorite", "Favorite"))
        cacheStore.recordHistoryState(card("history", "History"))
        cacheStore.promoteSeriesState(
            listOf(series("series", "Series", "series-book"))
        )

        // Deliberately stale compatibility mirror: export must ignore it.
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(AppCacheStore.LibraryCache::class.java)
        AbredDatabase.get(context).cachedPayloads().put(
            CachedPayloadEntity(
                cacheKey = LIBRARY_KEY,
                payloadJson = adapter.toJson(AppCacheStore.LibraryCache()),
                savedAtMs = 1L,
            )
        )

        val manager = UserDataExportManager(
            context = context,
            cacheStore = cacheStore,
            normalizedLibraryStore = NormalizedLibraryStore(context),
            resumeStore = PlaybackResumeStore(context),
            sourcePreferenceStore = BookSourcePreferenceStore(context),
            settingsStore = PlayerSettingsStore(context),
            sourceAvailabilityStore = SourceAvailabilityStore(context),
        )

        val backup = manager.buildBackup()

        assertEquals(listOf("favorite"), backup.library.favorites.map { it.id })
        assertEquals(listOf("history"), backup.library.history.map { it.id })
        assertEquals(listOf("series"), backup.library.series.map { it.id })
    }

    @Test
    fun exportIncludesCachedMetadataForCheckpointOnlyBook() = runBlocking {
        val cacheStore = LocalCacheStore(context)
        val resumeStore = PlaybackResumeStore(context)
        val sourcePreferenceStore = BookSourcePreferenceStore(context)
        val database = AbredDatabase.get(context)
        val detail = BookDetailDto(
            id = CHECKPOINT_ONLY_ID,
            title = "Checkpoint only",
            sourceCodes = listOf("rutracker"),
            primarySource = "rutracker",
            selectedSource = "rutracker",
        )
        cacheStore.writeBook(detail)
        // Reproduce upgrade residue: the retained card is gone but the local
        // source-aware detail cache still exists.
        database.localCatalog().pruneUnreferencedBooks()
        assertNull(database.localCatalog().book(CHECKPOINT_ONLY_ID))

        sourcePreferenceStore.set(CHECKPOINT_ONLY_ID, "rutracker")
        resumeStore.saveImmediate(
            bookId = CHECKPOINT_ONLY_ID,
            sourceCode = "rutracker",
            chapterId = "file:0",
            chapterIndex = 0,
            positionMs = 12_345L,
            speed = 1.0f,
            progressPercent = 12.0,
        )

        val manager = UserDataExportManager(
            context = context,
            cacheStore = cacheStore,
            normalizedLibraryStore = NormalizedLibraryStore(context),
            resumeStore = resumeStore,
            sourcePreferenceStore = sourcePreferenceStore,
            settingsStore = PlayerSettingsStore(context),
            sourceAvailabilityStore = SourceAvailabilityStore(context),
        )

        val backup = manager.buildBackup()

        assertTrue(backup.playbackCheckpoints.any { it.bookId == CHECKPOINT_ONLY_ID })
        assertEquals(
            "Checkpoint only",
            backup.bookMetadata.single { it.id == CHECKPOINT_ONLY_ID }.title,
        )
        assertEquals("rutracker", backup.sourcePreferences[CHECKPOINT_ONLY_ID])
    }

    @Test
    fun restorePrefersLibraryCardOverGenericMetadataForSameBook() = runBlocking {
        val backup = UserDataBackup(
            applicationId = "test",
            versionName = "test",
            versionCode = 1,
            exportedAtMs = 1L,
            library = AppCacheStore.LibraryCache(
                favorites = listOf(card("same-book", "Library title")),
            ),
            bookMetadata = listOf(card("same-book", "Stale metadata title")),
        )
        val manager = UserDataImportManager(
            context = context,
            resumeStore = PlaybackResumeStore(context),
            sourcePreferenceStore = BookSourcePreferenceStore(context),
            settingsStore = PlayerSettingsStore(context),
            sourceAvailabilityStore = SourceAvailabilityStore(context),
        )

        AbredDatabase.get(context).withTransaction {
            manager.restoreRoomStateWithinTransaction(backup)
        }

        val restored = NormalizedLibraryStore(context).read().library
        assertEquals("Library title", restored.favorites.single().title)
    }

    @Test
    fun restoreReplacesNormalizedLibraryAndPreservesDownloadRetention() = runBlocking {
        val cacheStore = LocalCacheStore(context)
        cacheStore.setFavoriteState("old-favorite", true, card("old-favorite", "Old"))
        cacheStore.setDownloadedReference(DOWNLOAD_ONLY_ID, true)
        val legacyAdapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(AppCacheStore.LibraryCache::class.java)
        AbredDatabase.get(context).cachedPayloads().put(
            CachedPayloadEntity(
                cacheKey = LIBRARY_KEY,
                payloadJson = legacyAdapter.toJson(
                    AppCacheStore.LibraryCache(
                        favorites = listOf(card("legacy-residue", "Legacy residue")),
                    )
                ),
                savedAtMs = 1L,
            )
        )

        val backup = UserDataBackup(
            applicationId = "test",
            versionName = "test",
            versionCode = 1,
            exportedAtMs = 1L,
            library = AppCacheStore.LibraryCache(
                favorites = listOf(card("favorite", "Favorite")),
                history = listOf(card("history", "History")),
                series = listOf(series("series", "Series", "series-book")),
                savedAtMs = 2L,
            ),
        )
        val manager = UserDataImportManager(
            context = context,
            resumeStore = PlaybackResumeStore(context),
            sourcePreferenceStore = BookSourcePreferenceStore(context),
            settingsStore = PlayerSettingsStore(context),
            sourceAvailabilityStore = SourceAvailabilityStore(context),
        )
        val database = AbredDatabase.get(context)

        database.withTransaction {
            manager.restoreRoomStateWithinTransaction(backup)
        }

        val normalized = NormalizedLibraryStore(context).read().library
        assertEquals(listOf("favorite"), normalized.favorites.map { it.id })
        assertEquals(listOf("history"), normalized.history.map { it.id })
        assertEquals(listOf("series"), normalized.series.map { it.id })
        assertFalse(normalized.favorites.any { it.id == "old-favorite" })

        assertNull(database.cachedPayloads().get(LIBRARY_KEY))

        val downloadRetention = database.localCatalog().retention(DOWNLOAD_ONLY_ID)
            ?: BookRetentionEntity(bookId = DOWNLOAD_ONLY_ID)
        assertTrue(downloadRetention.downloadedRef)
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
                book = card(bookId, "Book " + id),
                position = 1.0,
                state = "in_progress",
                progressPercent = 10.0,
            ),
        )

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val LIBRARY_KEY = "library:v1"
        const val DOWNLOAD_ONLY_ID = "download-only"
        const val CHECKPOINT_ONLY_ID = "rutracker:checkpoint-only"
    }
}
