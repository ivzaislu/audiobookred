package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
