package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_payloads", primaryKeys = ["cacheKey"])
data class CachedPayloadEntity(
    val cacheKey: String,
    val payloadJson: String,
    val savedAtMs: Long = System.currentTimeMillis()
)

/** Minimal per-book metadata retained independently from screen payload caches. */
@Entity(tableName = "local_books")
data class LocalBookEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val cardJson: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Profile/device references are deliberately separate from catalog membership.
 * A book may leave the latest-N catalog window and still remain locally known
 * because progress/history/favorite/bookmark/download state points at it.
 */
@Entity(tableName = "book_retention")
data class BookRetentionEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val favoriteRef: Boolean = false,
    val historyRef: Boolean = false,
    val progressRef: Boolean = false,
    val bookmarkRef: Boolean = false,
    val downloadedRef: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val retained: Boolean
        get() = favoriteRef || historyRef || progressRef || bookmarkRef || downloadedRef
}

@Entity(
    tableName = "catalog_window",
    indices = [Index(value = ["rank"], unique = true)]
)
data class CatalogWindowEntity(
    @androidx.room.PrimaryKey val bookId: String,
    /** Zero-based position in the newest-first server catalog snapshot. */
    val rank: Int
)

@Entity(tableName = "catalog_cache_state")
data class CatalogCacheStateEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val maxItems: Int = 1_000,
    val serverTotal: Int = 0,
    val lastSyncedAtMs: Long = 0L
)


@Entity(
    tableName = "sync_entities",
    primaryKeys = ["entityType", "entityId"],
    indices = [Index(value = ["revision"])]
)
data class SyncEntityEntity(
    val entityType: String,
    val entityId: String,
    val revision: Long = 0L,
    val operation: String = "upsert",
    val payloadJson: String = "{}",
    val updatedAtMs: Long = System.currentTimeMillis()
)

/** One coalesced pending mutation per durable sync entity. */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["mutationId"], unique = true),
        Index(value = ["createdAtMs"]),
        Index(value = ["blocked"])
    ]
)
data class SyncOutboxEntity(
    @androidx.room.PrimaryKey val entityKey: String,
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val operation: String = "upsert",
    val baseRevision: Long = 0L,
    val payloadJson: String = "{}",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val blocked: Boolean = false,
    val lastError: String = ""
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val profileId: String = "",
    val cursor: Long = 0L,
    val snapshotComplete: Boolean = false,
    val lastAttemptAtMs: Long = 0L,
    val lastSyncedAtMs: Long = 0L,
    val lastPullAtMs: Long = 0L,
    val lastPushAtMs: Long = 0L,
    val lastWorkerAtMs: Long = 0L,
    val lastWorkerResult: String = "",
    val lastError: String = ""
)

@Entity(
    tableName = "download_books",
    indices = [Index(value = ["bookId"]), Index(value = ["state"])]
)
data class DownloadBookEntity(
    @androidx.room.PrimaryKey val bookSourceId: String,
    val bookId: String,
    val sourceCode: String,
    val sourceName: String,
    val title: String,
    val coverUrl: String = "",
    val manifestId: String,
    val state: String = "queued",
    val totalSizeBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val filesCount: Int = 0,
    val completedFiles: Int = 0,
    val wifiOnly: Boolean = true,
    val error: String = "",
    val deletedAtMs: Long? = null,
    val purgeAfterMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "library_trash",
    primaryKeys = ["kind", "targetId"],
    indices = [Index(value = ["purgeAfterMs"])]
)
data class LibraryTrashEntity(
    val kind: String,
    val targetId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    val payloadJson: String = "",
    val deletedAtMs: Long,
    val purgeAfterMs: Long,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "download_files",
    primaryKeys = ["bookSourceId", "fileId"],
    indices = [Index(value = ["bookSourceId"]), Index(value = ["state"])]
)
data class DownloadFileEntity(
    val bookSourceId: String,
    val fileId: String,
    val chapterId: String,
    val chapterPosition: Int,
    val title: String,
    val durationSeconds: Long = 0L,
    val filename: String,
    val mediaType: String,
    val sizeBytes: Long? = null,
    val downloadUrl: String,
    val localPath: String,
    val downloadedBytes: Long = 0L,
    val state: String = "queued",
    val error: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_books WHERE deletedAtMs IS NULL ORDER BY updatedAtMs DESC")
    fun observeBooks(): Flow<List<DownloadBookEntity>>

    @Query("SELECT * FROM download_books WHERE deletedAtMs IS NULL ORDER BY updatedAtMs DESC")
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

@Database(
    entities = [
        CachedPayloadEntity::class,
        LocalBookEntity::class,
        BookRetentionEntity::class,
        CatalogWindowEntity::class,
        CatalogCacheStateEntity::class,
        SyncEntityEntity::class,
        SyncOutboxEntity::class,
        SyncStateEntity::class,
        DownloadBookEntity::class,
        DownloadFileEntity::class,
        LibraryTrashEntity::class,
        LibraryFavoriteEntity::class,
        LibraryHistoryEntity::class,
        LibrarySeriesEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AbredDatabase : RoomDatabase() {
    abstract fun cachedPayloads(): CachedPayloadDao
    abstract fun localCatalog(): LocalCatalogDao
    abstract fun downloads(): DownloadDao
    abstract fun libraryTrash(): LibraryTrashDao
    abstract fun normalizedLibrary(): NormalizedLibraryDao

    companion object {
        @Volatile private var instance: AbredDatabase? = null

        /**
         * Introduces normalized library tables only.
         *
         * The SQL schema migration does not parse library:v1. A one-time startup
         * bridge seeds these tables, deletes the legacy payload, and then normalized
         * Room remains authoritative.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS library_favorites (
                        bookId TEXT NOT NULL,
                        rank INTEGER NOT NULL,
                        addedAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(bookId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_library_favorites_rank ON library_favorites(rank)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS library_history (
                        bookId TEXT NOT NULL,
                        rank INTEGER NOT NULL,
                        lastPlayedAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(bookId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_library_history_rank ON library_history(rank)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_library_history_lastPlayedAtMs ON library_history(lastPlayedAtMs)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS library_series (
                        seriesKey TEXT NOT NULL,
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        externalId TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        rank INTEGER NOT NULL,
                        availableCount INTEGER NOT NULL,
                        totalCount INTEGER NOT NULL,
                        completedCount INTEGER NOT NULL,
                        inProgressCount INTEGER NOT NULL,
                        notStartedCount INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        lastActivityAt TEXT NOT NULL,
                        currentBookId TEXT,
                        currentPosition REAL,
                        currentState TEXT,
                        currentProgressPercent REAL,
                        nextBookId TEXT,
                        nextPosition REAL,
                        nextState TEXT,
                        nextProgressPercent REAL,
                        updatedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(seriesKey)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_library_series_rank ON library_series(rank)"
                )
            }
        }

        fun get(context: Context): AbredDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AbredDatabase::class.java,
                "abred-local-v1.db"
            )
                .addMigrations(MIGRATION_7_8)
                .build()
                .also { instance = it }
        }
    }
}
