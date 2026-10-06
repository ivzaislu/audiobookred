package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_books WHERE deletedAtMs IS NULL ORDER BY createdAtMs DESC, bookSourceId ASC")
    fun observeBooks(): Flow<List<DownloadBookEntity>>

    @Query("SELECT * FROM download_books WHERE deletedAtMs IS NULL ORDER BY createdAtMs DESC, bookSourceId ASC")
    suspend fun books(): List<DownloadBookEntity>

    @Query("SELECT * FROM download_books ORDER BY updatedAtMs DESC")
    suspend fun allBooks(): List<DownloadBookEntity>

    @Query("SELECT * FROM download_books WHERE deletedAtMs IS NOT NULL ORDER BY deletedAtMs DESC")
    suspend fun deletedBooks(): List<DownloadBookEntity>

    @Query("SELECT * FROM download_books WHERE bookSourceId = :bookSourceId LIMIT 1")
    suspend fun book(bookSourceId: String): DownloadBookEntity?

    @Query("SELECT * FROM download_files WHERE bookSourceId = :bookSourceId ORDER BY chapterPosition ASC, fileId ASC")
    suspend fun files(bookSourceId: String): List<DownloadFileEntity>

    @Upsert
    suspend fun putBook(value: DownloadBookEntity)

    @Upsert
    suspend fun putFiles(values: List<DownloadFileEntity>)

    @Query("DELETE FROM download_files WHERE bookSourceId = :bookSourceId")
    suspend fun deleteFiles(bookSourceId: String)

    @Query("DELETE FROM download_books WHERE bookSourceId = :bookSourceId")
    suspend fun deleteBook(bookSourceId: String)

    @Query("UPDATE download_books SET wifiOnly = :wifiOnly, updatedAtMs = :now WHERE bookSourceId = :bookSourceId")
    suspend fun setWifiOnly(bookSourceId: String, wifiOnly: Boolean, now: Long): Int

    @Query(
        """
        UPDATE download_files
        SET state = 'queued',
            downloadedBytes = :downloadedBytes,
            error = '',
            updatedAtMs = :now
        WHERE bookSourceId = :bookSourceId
          AND fileId = :fileId
          AND state = 'completed'
        """
    )
    suspend fun requeueMissingCompletedFile(
        bookSourceId: String,
        fileId: String,
        downloadedBytes: Long,
        now: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM download_books WHERE state = 'completed' AND deletedAtMs IS NULL")
    suspend fun completedBookCount(): Int

    @Query("SELECT COALESCE(SUM(downloadedBytes), 0) FROM download_books")
    suspend fun downloadedBytes(): Long
}

@Dao
interface LibraryTrashDao {
    @Query("DELETE FROM library_trash WHERE purgeAfterMs <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int

}

@Dao
interface CachedPayloadDao {
    @Query("SELECT * FROM cached_payloads WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): CachedPayloadEntity?

    @Query("SELECT * FROM cached_payloads WHERE cacheKey LIKE :prefix || '%' ORDER BY savedAtMs DESC LIMIT 1")
    suspend fun latestWithPrefix(prefix: String): CachedPayloadEntity?

    @Query("SELECT * FROM cached_payloads WHERE cacheKey LIKE :prefix || '%' ORDER BY savedAtMs DESC")
    suspend fun allWithPrefix(prefix: String): List<CachedPayloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: CachedPayloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(values: List<CachedPayloadEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putAllIfAbsent(values: List<CachedPayloadEntity>)

    @Query("SELECT cacheKey FROM cached_payloads WHERE cacheKey IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Query("DELETE FROM cached_payloads WHERE cacheKey = :key")
    suspend fun delete(key: String)

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(CAST(payloadJson AS BLOB))), 0)
        FROM cached_payloads
        WHERE cacheKey = 'home:v1'
           OR cacheKey = 'home:v2'
           OR cacheKey LIKE 'catalog:%'
           OR cacheKey LIKE 'browse:%'
           OR cacheKey LIKE 'book:%'
           OR cacheKey LIKE 'similar:%'
           OR cacheKey LIKE 'series:%'
        """
    )
    suspend fun disposablePayloadBytes(): Long

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(CAST(payloadJson AS BLOB))), 0)
        FROM cached_payloads
        WHERE cacheKey LIKE 'progress:%'
           OR cacheKey LIKE 'bookmarks:%'
        """
    )
    suspend fun profilePayloadBytes(): Long

    @Query(
        """
        DELETE FROM cached_payloads
        WHERE cacheKey = 'home:v1'
           OR cacheKey = 'home:v2'
           OR cacheKey LIKE 'catalog:%'
           OR cacheKey LIKE 'browse:%'
           OR cacheKey LIKE 'book:%'
           OR cacheKey LIKE 'similar:%'
           OR cacheKey LIKE 'series:%'
        """
    )
    suspend fun deleteDisposablePayloads()

    @Query(
        """
        DELETE FROM cached_payloads
        WHERE cacheKey LIKE 'progress:%'
           OR cacheKey LIKE 'bookmarks:%'
        """
    )
    suspend fun deleteProfilePayloads()

}

@Dao
interface LocalCatalogDao {
    @Upsert
    suspend fun upsertBooks(values: List<LocalBookEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooksIfAbsent(values: List<LocalBookEntity>)

    @Query("SELECT * FROM local_books WHERE bookId = :bookId LIMIT 1")
    suspend fun book(bookId: String): LocalBookEntity?

    @Query("SELECT * FROM local_books WHERE bookId IN (:bookIds)")
    suspend fun books(bookIds: List<String>): List<LocalBookEntity>

    @Query("SELECT * FROM local_books")
    suspend fun allBooks(): List<LocalBookEntity>

    @Query("DELETE FROM catalog_window")
    suspend fun clearWindow()

    @Upsert
    suspend fun putState(value: CatalogCacheStateEntity)

    @Query("SELECT * FROM catalog_cache_state WHERE id = 1 LIMIT 1")
    suspend fun state(): CatalogCacheStateEntity?

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(CAST(b.cardJson AS BLOB))), 0)
        FROM local_books b
        WHERE b.bookId NOT IN (SELECT bookId FROM catalog_window)
          AND (
              b.bookId IN (
                  SELECT bookId FROM book_retention
                  WHERE favoriteRef = 1
                     OR historyRef = 1
                     OR progressRef = 1
                     OR bookmarkRef = 1
                     OR downloadedRef = 1
              )
              OR b.bookId IN (SELECT bookId FROM library_favorites)
              OR b.bookId IN (SELECT bookId FROM library_history)
              OR b.bookId IN (
                  SELECT currentBookId FROM library_series WHERE currentBookId IS NOT NULL
              )
              OR b.bookId IN (
                  SELECT nextBookId FROM library_series WHERE nextBookId IS NOT NULL
              )
          )
        """
    )
    suspend fun retainedMetadataBytes(): Long

    @Query("SELECT COUNT(*) FROM book_retention WHERE favoriteRef = 1")
    suspend fun favoriteRefCount(): Int

    @Query("SELECT COUNT(*) FROM book_retention WHERE historyRef = 1")
    suspend fun historyRefCount(): Int

    @Query("SELECT COUNT(*) FROM book_retention WHERE progressRef = 1")
    suspend fun progressRefCount(): Int

    @Query("SELECT COUNT(*) FROM book_retention WHERE bookmarkRef = 1")
    suspend fun bookmarkRefCount(): Int

    @Query("SELECT COUNT(*) FROM book_retention WHERE downloadedRef = 1")
    suspend fun downloadedRefCount(): Int

    @Query("SELECT * FROM book_retention WHERE bookId = :bookId LIMIT 1")
    suspend fun retention(bookId: String): BookRetentionEntity?

    @Query("SELECT * FROM book_retention WHERE bookId IN (:bookIds)")
    suspend fun retentions(bookIds: List<String>): List<BookRetentionEntity>

    @Upsert
    suspend fun putRetention(value: BookRetentionEntity)

    @Upsert
    suspend fun putRetentions(values: List<BookRetentionEntity>)

    @Query("UPDATE book_retention SET favoriteRef = 0, updatedAtMs = :now WHERE favoriteRef = 1")
    suspend fun clearFavoriteRefs(now: Long)

    @Query("UPDATE book_retention SET historyRef = 0, updatedAtMs = :now WHERE historyRef = 1")
    suspend fun clearHistoryRefs(now: Long)

    @Query(
        """
        UPDATE book_retention
        SET favoriteRef = 0,
            historyRef = 0,
            progressRef = 0,
            bookmarkRef = 0,
            updatedAtMs = :now
        WHERE favoriteRef = 1
           OR historyRef = 1
           OR progressRef = 1
           OR bookmarkRef = 1
        """
    )
    suspend fun clearProfileRefs(now: Long)

    @Query(
        """
        DELETE FROM local_books
        WHERE bookId NOT IN (SELECT bookId FROM catalog_window)
          AND bookId NOT IN (
              SELECT bookId FROM book_retention
              WHERE favoriteRef = 1
                 OR historyRef = 1
                 OR progressRef = 1
                 OR bookmarkRef = 1
                 OR downloadedRef = 1
          )
          AND bookId NOT IN (SELECT bookId FROM library_favorites)
          AND bookId NOT IN (SELECT bookId FROM library_history)
          AND bookId NOT IN (
              SELECT currentBookId FROM library_series WHERE currentBookId IS NOT NULL
          )
          AND bookId NOT IN (
              SELECT nextBookId FROM library_series WHERE nextBookId IS NOT NULL
          )
        """
    )
    suspend fun pruneUnreferencedBooks()

    @Query(
        """
        DELETE FROM book_retention
        WHERE favoriteRef = 0
          AND historyRef = 0
          AND progressRef = 0
          AND bookmarkRef = 0
          AND downloadedRef = 0
          AND bookId NOT IN (SELECT bookId FROM local_books)
        """
    )
    suspend fun pruneEmptyRetention()

    @Query(
        """
        SELECT COUNT(*)
        FROM local_books b
        WHERE b.bookId NOT IN (SELECT bookId FROM catalog_window)
          AND (
              b.bookId IN (
                  SELECT bookId FROM book_retention
                  WHERE favoriteRef = 1
                     OR historyRef = 1
                     OR progressRef = 1
                     OR bookmarkRef = 1
                     OR downloadedRef = 1
              )
              OR b.bookId IN (SELECT bookId FROM library_favorites)
              OR b.bookId IN (SELECT bookId FROM library_history)
              OR b.bookId IN (
                  SELECT currentBookId FROM library_series WHERE currentBookId IS NOT NULL
              )
              OR b.bookId IN (
                  SELECT nextBookId FROM library_series WHERE nextBookId IS NOT NULL
              )
          )
        """
    )
    suspend fun retainedOutsideWindowCount(): Int
}
