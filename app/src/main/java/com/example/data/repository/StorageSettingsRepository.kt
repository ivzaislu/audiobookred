package com.example.data.repository

import android.content.Context
import com.example.data.download.AudiobookDownloadManager
import com.example.data.image.PosterImageCache
import com.example.data.local.AbredDatabase
import com.example.data.local.DownloadStore
import com.example.data.local.LibraryCacheStore
import com.example.data.local.LocalCacheStore
import com.example.data.settings.PlayerSettingsStore
import com.example.util.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Data boundary for the standalone local Storage settings destination. */
@Singleton
class StorageSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val cacheStore: LocalCacheStore,
    private val libraryStore: LibraryCacheStore,
    private val downloadStore: DownloadStore,
    private val downloadManager: AudiobookDownloadManager,
    private val settingsStore: PlayerSettingsStore,
) {
    private val appContext = context.applicationContext
    private val database = AbredDatabase.get(appContext)

    val settings = settingsStore.state

    fun observeSnapshot() = database.invalidationTracker
        .createFlow(
            "cached_payloads",
            "local_books",
            "book_retention",
            "library_favorites",
            "library_history",
            "library_series",
            "download_books",
            "download_files",
            emitInitialState = true,
        )
        .map { snapshot() }

    suspend fun snapshot(): StorageSnapshot {
        val library = libraryStore.read().library
        val authoritativeFavoriteBooks = library.favorites.distinctBy { it.id }.size
        val authoritativeHistoryBooks = library.history.distinctBy { it.id }.size

        val storage = cacheStore.storageStats()

        val downloads = runCatchingCancellable { downloadStore.stats() }.getOrNull()
        val actualDownloadBytes = downloadDiskBytes()
        val roomCacheBytes = storage.catalogBytes.coerceAtLeast(0L)
        val posterCacheBytes = withContext(Dispatchers.IO) {
            runCatching { PosterImageCache.diskSizeBytes(appContext) }.getOrDefault(0L)
        }
        return StorageSnapshot(
            roomCacheBytes = roomCacheBytes,
            posterCacheBytes = posterCacheBytes,
            cacheBytes = (roomCacheBytes + posterCacheBytes).coerceAtLeast(0L),
            profileBytes = storage.profileBytes,
            databaseBytes = databaseDiskBytes(),
            favoriteBooks = authoritativeFavoriteBooks,
            historyBooks = authoritativeHistoryBooks,
            progressBooks = storage.progressBooks,
            bookmarkBooks = storage.bookmarkBooks,
            downloadedBooks = downloads?.books ?: storage.downloadedBooks,
            downloadedBytes = actualDownloadBytes,
        )
    }

    /**
     * Remove every cache component shown in Settings. Returns whether SQLite was
     * also compacted successfully; cache deletion itself happens before VACUUM.
     */
    suspend fun clearCatalogCache(): Boolean {
        cacheStore.clearCatalogCache()
        withContext(Dispatchers.IO) {
            PosterImageCache.clear(appContext)
        }
        return compactDatabase()
    }

    suspend fun setCatalogCacheEnabled(value: Boolean) {
        if (settingsStore.state.value.catalogCacheEnabled == value) return
        settingsStore.setCatalogCacheEnabled(value)
        if (!value) clearCatalogCache()
    }

    suspend fun setDownloadWifiOnly(value: Boolean) {
        settingsStore.setDownloadWifiOnly(value)
        downloadManager.updateNetworkPolicy(value)
    }

    private suspend fun downloadDiskBytes(): Long = withContext(Dispatchers.IO) {
        downloadStore.diskBytes()
    }

    private fun databaseDiskBytes(): Long {
        val main = appContext.getDatabasePath(DATABASE_NAME)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"))
            .sumOf { file -> file.takeIf(File::exists)?.length() ?: 0L }
    }

    private suspend fun compactDatabase(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                while (cursor.moveToNext()) Unit
            }
            sqlite.execSQL("VACUUM")
        }.isSuccess
    }

    private companion object {
        const val DATABASE_NAME = "abred-local-v1.db"
    }
}

data class StorageSnapshot(
    val roomCacheBytes: Long,
    val posterCacheBytes: Long,
    val cacheBytes: Long,
    val profileBytes: Long,
    val databaseBytes: Long,
    val favoriteBooks: Int,
    val historyBooks: Int,
    val progressBooks: Int,
    val bookmarkBooks: Int,
    val downloadedBooks: Int,
    val downloadedBytes: Long,
)
