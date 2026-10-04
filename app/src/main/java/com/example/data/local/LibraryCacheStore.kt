package com.example.data.local

import android.content.Context
import com.example.data.cache.AppCacheStore
import com.example.data.model.BookmarkDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reactive Room-backed read boundary for Library.
 *
 * Normalized membership/order rows plus local_books are the only runtime source
 * of Library state. library:v1 exists only as upgrade input and is consumed by
 * the one-time normalized migration bridge.
 */
class LibraryCacheStore(
    context: Context,
    private val cacheStore: LocalCacheStore,
    private val normalizedStore: NormalizedLibraryStore,
) {
    data class Snapshot(
        val library: AppCacheStore.LibraryCache = AppCacheStore.LibraryCache(),
        val bookmarks: List<BookmarkDto> = emptyList(),
    )

    private val database = AbredDatabase.get(context.applicationContext)

    suspend fun read(): Snapshot {
        return Snapshot(
            library = normalizedStore.read().library,
            bookmarks = cacheStore.readAllBookmarks(),
        )
    }

    // cached_payloads remains here only because Snapshot still includes
    // bookmarks. Library membership/order itself is fully normalized.
    fun observe(): Flow<Snapshot> = database.invalidationTracker
        .createFlow(
            "cached_payloads",
            "local_books",
            "library_favorites",
            "library_history",
            "library_series",
            emitInitialState = true,
        )
        .map { read() }

    /**
     * Library-only invalidation stream for playback/Android Auto.
     * Deliberately excludes cached_payloads so periodic progress writes do not
     * rebuild the car media tree.
     */
    fun observeLibraryOnly(): Flow<AppCacheStore.LibraryCache> = database.invalidationTracker
        .createFlow(
            "local_books",
            "library_favorites",
            "library_history",
            "library_series",
            emitInitialState = true,
        )
        .map { read().library }

}
