package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.data.cache.AppCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val NORMALIZED_LIBRARY_MIGRATION_MARKER_KEY = "library:normalized-room:v1"

internal data class NormalizedLibraryBackfillRows(
    val favorites: List<LibraryFavoriteEntity>,
    val history: List<LibraryHistoryEntity>,
    val series: List<LibrarySeriesEntity>,
    /**
     * Metadata needed to resolve every normalized library row later.
     *
     * Backfill inserts these into local_books only when missing, so a legacy
     * snapshot can fill gaps but never overwrite fresher retained metadata.
     */
    val referencedCards: List<BookCardDto>,
)

/**
 * Pure legacy snapshot -> normalized rows mapping.
 *
 * First occurrence wins for duplicate ids/series keys, matching the existing
 * LibraryRepository distinctBy semantics. Ranks are always dense and zero-based.
 */
internal fun normalizedLibraryBackfillRows(
    library: AppCacheStore.LibraryCache,
): NormalizedLibraryBackfillRows {
    val snapshotTimestamp = library.savedAtMs.coerceAtLeast(0L)

    val favorites = library.favorites
        .asSequence()
        .distinctBy(BookCardDto::id)
        .mapIndexed { rank, card ->
            LibraryFavoriteEntity(
                bookId = card.id,
                rank = rank,
                addedAtMs = snapshotTimestamp,
                updatedAtMs = snapshotTimestamp,
            )
        }
        .toList()

    val history = library.history
        .asSequence()
        .distinctBy(BookCardDto::id)
        .mapIndexed { rank, card ->
            LibraryHistoryEntity(
                bookId = card.id,
                rank = rank,
                lastPlayedAtMs = snapshotTimestamp,
                updatedAtMs = snapshotTimestamp,
            )
        }
        .toList()

    val normalizedSeries = library.series
        .asSequence()
        .distinctBy(::normalizedSeriesKey)
        .mapIndexed { rank, item ->
            item.toNormalizedLibrarySeriesEntity(rank, snapshotTimestamp)
        }
        .toList()

    val referencedCards = buildList {
        addAll(library.favorites)
        addAll(library.history)
        library.series.forEach { item ->
            item.currentBook?.book?.let { add(it) }
            item.nextBook?.book?.let { add(it) }
        }
    }
        .asSequence()
        .distinctBy(BookCardDto::id)
        .toList()

    return NormalizedLibraryBackfillRows(
        favorites = favorites,
        history = history,
        series = normalizedSeries,
        referencedCards = referencedCards,
    )
}

private fun normalizedSeriesKey(item: MySeriesDto): String =
    normalizedLibrarySeriesKey(
        provider = item.provider,
        externalId = item.externalId,
        name = item.name,
    )

/**
 * One-time upgrade bridge from legacy library:v1 into normalized Room.
 *
 * The migration marker is committed in the same SQLite transaction as rows,
 * favorite/history retention repair and legacy payload deletion. Once present,
 * library:v1 can never overwrite normalized Library state again. If a prior v8
 * prerelease already has normalized rows, those rows are preserved; an older
 * marker-only install also has any leftover library:v1 residue removed.
 */
@Singleton
class NormalizedLibraryBackfill @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val database = AbredDatabase.get(appContext)
    private val payloadDao = database.cachedPayloads()
    private val normalizedDao = database.normalizedLibrary()
    private val catalogDao = database.localCatalog()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val libraryAdapter = moshi.adapter(AppCacheStore.LibraryCache::class.java)
    private val bookCardAdapter = moshi.adapter(BookCardDto::class.java)

    /**
     * Returns true only when this call completed the migration marker transaction.
     * Later calls are no-ops and never read library:v1 again.
     */
    suspend fun migrateOnce(): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (payloadDao.get(NORMALIZED_LIBRARY_MIGRATION_MARKER_KEY) != null) {
                // Early v8 prereleases kept library:v1 as inert residue. Consume
                // that residue without ever replaying it over normalized rows.
                payloadDao.delete(LIBRARY_KEY)
                return@withTransaction false
            }

            val now = System.currentTimeMillis()
            val legacy = payloadDao.get(LIBRARY_KEY)
            if (legacy != null) {
                val library = runCatching { libraryAdapter.fromJson(legacy.payloadJson) }
                    .getOrElse { error ->
                        throw IOException("Не удалось прочитать legacy library:v1 для migration", error)
                    }
                    ?: throw IOException("Legacy library:v1 пуст")

                val rows = normalizedLibraryBackfillRows(library)
                val cardTimestamp = legacy.savedAtMs.coerceAtLeast(0L)
                val cardRows = rows.referencedCards.map { card ->
                    val json = runCatching { bookCardAdapter.toJson(card) }
                        .getOrElse { error ->
                            throw IOException(
                                "Не удалось сериализовать metadata книги " + card.id + " для migration",
                                error,
                            )
                        }
                    LocalBookEntity(
                        bookId = card.id,
                        cardJson = json,
                        updatedAtMs = cardTimestamp,
                    )
                }

                normalizedDao.replaceFavorites(rows.favorites)
                normalizedDao.replaceHistory(rows.history)
                normalizedDao.replaceSeries(rows.series)
                if (cardRows.isNotEmpty()) catalogDao.insertBooksIfAbsent(cardRows)
            }

            // Align lifecycle refs with whichever normalized rows are authoritative:
            // freshly migrated legacy rows or rows already written by a prior v8 build.
            val favoriteIds = normalizedDao.favorites()
                .mapTo(linkedSetOf(), LibraryFavoriteEntity::bookId)
            val historyIds = normalizedDao.history()
                .mapTo(linkedSetOf(), LibraryHistoryEntity::bookId)
            catalogDao.clearFavoriteRefs(now)
            catalogDao.clearHistoryRefs(now)

            val retainedIds = buildList {
                addAll(favoriteIds)
                historyIds.forEach { id -> if (id !in favoriteIds) add(id) }
            }
            if (retainedIds.isNotEmpty()) {
                val existing = retainedIds
                    .chunked(RETENTION_QUERY_CHUNK_SIZE)
                    .flatMap { ids -> catalogDao.retentions(ids) }
                val merged = mergeLibraryRetentionRows(
                    existing = existing,
                    favoriteIds = favoriteIds,
                    historyIds = historyIds,
                    now = now,
                )
                if (merged.isNotEmpty()) catalogDao.putRetentions(merged)
            }

            catalogDao.pruneUnreferencedBooks()
            catalogDao.pruneEmptyRetention()
            payloadDao.delete(LIBRARY_KEY)
            payloadDao.put(
                CachedPayloadEntity(
                    cacheKey = NORMALIZED_LIBRARY_MIGRATION_MARKER_KEY,
                    payloadJson = "true",
                    savedAtMs = now,
                )
            )
            true
        }.also {
            // This marker is only a cold-start fast path. Room remains the durable
            // authority, so any preference write failure merely causes another safe DB check.
            runCatching {
                appContext.getSharedPreferences(FAST_PATH_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(FAST_PATH_COMPLETE, true)
                    .commit()
            }
        }
    }

    companion object {
        internal fun isMigrationComplete(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(FAST_PATH_PREFS, Context.MODE_PRIVATE)
                .getBoolean(FAST_PATH_COMPLETE, false)

        private const val LIBRARY_KEY = "library:v1"
        private const val RETENTION_QUERY_CHUNK_SIZE = 500
        private const val FAST_PATH_PREFS = "abred_normalized_library_migration"
        private const val FAST_PATH_COMPLETE = "normalized_room_v1_complete"
    }
}

