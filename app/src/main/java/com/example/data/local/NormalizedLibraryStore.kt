package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.data.cache.AppCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.data.model.SeriesProgressBookDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class NormalizedLibraryReadResult(
    val library: AppCacheStore.LibraryCache,
    val missingBookIds: Set<String> = emptySet(),
)

/**
 * Read-only boundary for normalized library tables.
 *
 * User-facing Library and playback-derived state consume this boundary. Book
 * metadata is resolved from local_books in bounded batches; normalized
 * membership/order rows never carry duplicate card JSON.
 */
@Singleton
class NormalizedLibraryStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val database = AbredDatabase.get(context.applicationContext)
    private val libraryDao = database.normalizedLibrary()
    private val catalogDao = database.localCatalog()
    private val bookCardAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(BookCardDto::class.java)

    suspend fun read(): NormalizedLibraryReadResult =
        database.withTransaction { readWithinTransaction() }

    /**
     * Read helper for callers that already own [AbredDatabase]'s Room transaction.
     * This keeps backup/export on one SQLite snapshot without nesting a second
     * logical transaction boundary.
     */
    internal suspend fun readWithinTransaction(): NormalizedLibraryReadResult {
        val favorites = libraryDao.favorites()
        val history = libraryDao.history()
        val series = libraryDao.series()

        val referencedIds = buildList {
            favorites.forEach { add(it.bookId) }
            history.forEach { add(it.bookId) }
            series.forEach { row ->
                row.currentBookId?.let(::add)
                row.nextBookId?.let(::add)
            }
        }.distinct()

        val cards = referencedIds
            .chunked(BOOK_QUERY_CHUNK_SIZE)
            .flatMap { ids -> catalogDao.books(ids) }
            .mapNotNull { row ->
                runCatching { bookCardAdapter.fromJson(row.cardJson) }
                    .getOrNull()
                    ?.let { card -> row.bookId to card }
            }
            .toMap()

        val missingIds = referencedIds
            .asSequence()
            .filterNot(cards::containsKey)
            .toSet()

        val savedAtMs = sequenceOf(
            favorites.maxOfOrNull(LibraryFavoriteEntity::updatedAtMs),
            history.maxOfOrNull(LibraryHistoryEntity::updatedAtMs),
            series.maxOfOrNull(LibrarySeriesEntity::updatedAtMs),
        ).filterNotNull().maxOrNull() ?: 0L

        return NormalizedLibraryReadResult(
            library = AppCacheStore.LibraryCache(
                favorites = favorites.mapNotNull { row -> cards[row.bookId] },
                history = history.mapNotNull { row -> cards[row.bookId] },
                series = series.map { row -> row.toDto(cards) },
                savedAtMs = savedAtMs,
            ),
            missingBookIds = missingIds,
        )
    }

    private fun LibrarySeriesEntity.toDto(
        cards: Map<String, BookCardDto>,
    ): MySeriesDto = MySeriesDto(
        id = id,
        name = name,
        provider = provider,
        externalId = externalId,
        sourceName = sourceName,
        availableCount = availableCount,
        totalCount = totalCount,
        completedCount = completedCount,
        inProgressCount = inProgressCount,
        notStartedCount = notStartedCount,
        status = status,
        lastActivityAt = lastActivityAt,
        currentBook = currentBookId?.let(cards::get)?.let { card ->
            SeriesProgressBookDto(
                book = card,
                position = currentPosition,
                state = currentState ?: "not_started",
                progressPercent = currentProgressPercent ?: 0.0,
            )
        },
        nextBook = nextBookId?.let(cards::get)?.let { card ->
            SeriesProgressBookDto(
                book = card,
                position = nextPosition,
                state = nextState ?: "not_started",
                progressPercent = nextProgressPercent ?: 0.0,
            )
        },
    )

    private companion object {
        const val BOOK_QUERY_CHUNK_SIZE = 500
    }
}
