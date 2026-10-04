package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO contract for the normalized user-owned library.
 *
 * This DAO is exposed by [AbredDatabase] in schema version 8. User-facing Library
 * reads and live favorite/history/series mutations use these normalized rows.
 * library:v1 is no longer part of runtime Library authority.
 *
 * Ordering invariant:
 * - lower rank is displayed first;
 * - bookId / seriesKey is a deterministic tie-breaker for defensive reads;
 * - callers that replace a collection must provide a dense, zero-based rank set.
 */
@Dao
interface NormalizedLibraryDao {
    @Query(
        """
        SELECT * FROM library_favorites
        ORDER BY rank ASC, bookId ASC
        """
    )
    suspend fun favorites(): List<LibraryFavoriteEntity>

    @Query(
        """
        SELECT * FROM library_history
        ORDER BY rank ASC, bookId ASC
        """
    )
    suspend fun history(): List<LibraryHistoryEntity>

    @Query(
        """
        SELECT * FROM library_series
        ORDER BY rank ASC, seriesKey ASC
        """
    )
    suspend fun series(): List<LibrarySeriesEntity>

    @Query(
        """
        SELECT * FROM library_favorites
        ORDER BY rank ASC, bookId ASC
        """
    )
    fun observeFavorites(): Flow<List<LibraryFavoriteEntity>>

    @Query(
        """
        SELECT * FROM library_history
        ORDER BY rank ASC, bookId ASC
        """
    )
    fun observeHistory(): Flow<List<LibraryHistoryEntity>>

    @Query(
        """
        SELECT * FROM library_series
        ORDER BY rank ASC, seriesKey ASC
        """
    )
    fun observeSeries(): Flow<List<LibrarySeriesEntity>>

    @Query("SELECT * FROM library_favorites WHERE bookId = :bookId LIMIT 1")
    suspend fun favorite(bookId: String): LibraryFavoriteEntity?

    @Query("SELECT * FROM library_history WHERE bookId = :bookId LIMIT 1")
    suspend fun historyEntry(bookId: String): LibraryHistoryEntity?

    @Query("SELECT * FROM library_series WHERE seriesKey = :seriesKey LIMIT 1")
    suspend fun seriesEntry(seriesKey: String): LibrarySeriesEntity?

    @Upsert
    suspend fun putFavorite(value: LibraryFavoriteEntity)

    @Upsert
    suspend fun putFavorites(values: List<LibraryFavoriteEntity>)

    @Upsert
    suspend fun putHistory(value: LibraryHistoryEntity)

    @Upsert
    suspend fun putHistory(values: List<LibraryHistoryEntity>)

    @Upsert
    suspend fun putSeries(value: LibrarySeriesEntity)

    @Upsert
    suspend fun putSeries(values: List<LibrarySeriesEntity>)

    @Query("UPDATE library_favorites SET rank = rank + 1")
    suspend fun shiftFavoritesForNewFront()

    @Query("UPDATE library_favorites SET rank = rank + 1 WHERE rank < :currentRank")
    suspend fun shiftFavoritesBefore(currentRank: Int)

    @Query("UPDATE library_favorites SET rank = rank - 1 WHERE rank > :removedRank")
    suspend fun compactFavoritesAfter(removedRank: Int)

    @Query("UPDATE library_history SET rank = rank + 1")
    suspend fun shiftHistoryForNewFront()

    @Query("UPDATE library_history SET rank = rank + 1 WHERE rank < :currentRank")
    suspend fun shiftHistoryBefore(currentRank: Int)

    @Query("UPDATE library_history SET rank = rank - 1 WHERE rank > :removedRank")
    suspend fun compactHistoryAfter(removedRank: Int)

    @Query("DELETE FROM library_favorites WHERE bookId = :bookId")
    suspend fun deleteFavorite(bookId: String): Int

    @Query("DELETE FROM library_history WHERE bookId = :bookId")
    suspend fun deleteHistory(bookId: String): Int

    @Query("DELETE FROM library_series WHERE seriesKey = :seriesKey")
    suspend fun deleteSeries(seriesKey: String): Int

    @Query("DELETE FROM library_favorites")
    suspend fun clearFavorites()

    @Query("DELETE FROM library_history")
    suspend fun clearHistory()

    @Query("DELETE FROM library_series")
    suspend fun clearSeries()

    @Query("SELECT COUNT(*) FROM library_favorites")
    suspend fun favoriteCount(): Int

    @Query("SELECT COUNT(*) FROM library_history")
    suspend fun historyCount(): Int

    @Query("SELECT COUNT(*) FROM library_series")
    suspend fun seriesCount(): Int

    /**
     * Restore/migration primitive: replacement is all-or-nothing inside Room.
     *
     * Retention rows and local_books are deliberately not touched here. The
     * higher-level migration/restore transaction owns those cross-table invariants.
     */
    @Transaction
    suspend fun replaceFavorites(values: List<LibraryFavoriteEntity>) {
        clearFavorites()
        if (values.isNotEmpty()) putFavorites(values)
    }

    @Transaction
    suspend fun replaceHistory(values: List<LibraryHistoryEntity>) {
        clearHistory()
        if (values.isNotEmpty()) putHistory(values)
    }

    @Transaction
    suspend fun replaceSeries(values: List<LibrarySeriesEntity>) {
        clearSeries()
        if (values.isNotEmpty()) putSeries(values)
    }
}
