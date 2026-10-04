package com.example.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbredDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton(ignoreCloseFailure = true)
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        resetAbredDatabaseSingleton(ignoreCloseFailure = true)
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun version7MigratesToCurrentSchemaAndPreservesSupportedFloorState() = runBlocking {
        createVersion7Database()

        // Open through the production singleton so this test exercises the exact
        // migration registration shipped by the app, not a test-only chain.
        val database = AbredDatabase.get(context)
        val sqlite = database.openHelper.writableDatabase

        assertEquals(CURRENT_VERSION, sqlite.version)

        val sentinel = database.cachedPayloads().get(SENTINEL_KEY)
        assertNotNull(sentinel)
        assertEquals(SENTINEL_JSON, sentinel?.payloadJson)
        assertEquals(SENTINEL_SAVED_AT, sentinel?.savedAtMs)

        val retention = database.localCatalog().retention(V7_DOWNLOAD_BOOK_ID)
        assertNotNull(retention)
        assertTrue(retention?.downloadedRef == true)

        val migratedBook = database.downloads().book(V7_DOWNLOAD_SOURCE_ID)
        assertNotNull(migratedBook)
        assertEquals(V7_DOWNLOAD_BOOK_ID, migratedBook?.bookId)
        assertEquals("completed", migratedBook?.state)
        assertEquals(4096L, migratedBook?.downloadedBytes)
        assertEquals(null, migratedBook?.deletedAtMs)
        assertEquals(null, migratedBook?.purgeAfterMs)

        val migratedFiles = database.downloads().files(V7_DOWNLOAD_SOURCE_ID)
        assertEquals(1, migratedFiles.size)
        assertEquals(V7_DOWNLOAD_FILE_ID, migratedFiles.single().fileId)
        assertEquals("completed", migratedFiles.single().state)

        // v7 -> v8 adds the normalized Library tables while all supported-floor
        // v7 tables remain usable through current DAOs.
        assertTrue(database.normalizedLibrary().favorites().isEmpty())
        assertTrue(database.normalizedLibrary().history().isEmpty())
        assertTrue(database.normalizedLibrary().series().isEmpty())
        assertEquals(0, database.libraryTrash().purgeExpired(Long.MIN_VALUE))

        val tables = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        ).use { cursor ->
            buildSet {
                val column = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(column))
            }
        }
        assertTrue("catalog_window" in tables)
        assertTrue("catalog_cache_state" in tables)
        assertTrue("sync_entities" in tables)
        assertTrue("sync_outbox" in tables)
        assertTrue("sync_state" in tables)
        assertTrue("download_books" in tables)
        assertTrue("download_files" in tables)
        assertTrue("library_trash" in tables)
        assertTrue("library_favorites" in tables)
        assertTrue("library_history" in tables)
        assertTrue("library_series" in tables)

        val downloadColumns = sqlite.query("PRAGMA table_info(download_books)").use { cursor ->
            buildSet {
                val column = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(column))
            }
        }
        assertTrue("deletedAtMs" in downloadColumns)
        assertTrue("purgeAfterMs" in downloadColumns)
    }

    private fun createVersion7Database() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createVersion7Schema(db)

                        db.execSQL(
                            "INSERT INTO cached_payloads(cacheKey, payloadJson, savedAtMs) VALUES (?, ?, ?)",
                            arrayOf<Any>(SENTINEL_KEY, SENTINEL_JSON, SENTINEL_SAVED_AT),
                        )
                        db.execSQL(
                            """
                            INSERT INTO local_books(bookId, cardJson, updatedAtMs)
                            VALUES (?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any>(V7_DOWNLOAD_BOOK_ID, "{}", 1500L),
                        )
                        db.execSQL(
                            """
                            INSERT INTO book_retention(
                                bookId, favoriteRef, historyRef, progressRef,
                                bookmarkRef, downloadedRef, updatedAtMs
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any>(V7_DOWNLOAD_BOOK_ID, 0, 0, 0, 0, 1, 2000L),
                        )
                        db.execSQL(
                            """
                            INSERT INTO download_books(
                                bookSourceId, bookId, sourceCode, sourceName, title, coverUrl,
                                manifestId, state, totalSizeBytes, downloadedBytes, filesCount,
                                completedFiles, wifiOnly, error, deletedAtMs, purgeAfterMs,
                                createdAtMs, updatedAtMs
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                V7_DOWNLOAD_SOURCE_ID,
                                V7_DOWNLOAD_BOOK_ID,
                                "test-source",
                                "Test source",
                                "Existing v7 download",
                                "https://example.invalid/cover.jpg",
                                "manifest-v7",
                                "completed",
                                4096L,
                                4096L,
                                1,
                                1,
                                0,
                                "",
                                null,
                                null,
                                1000L,
                                2000L,
                            ),
                        )
                        db.execSQL(
                            """
                            INSERT INTO download_files(
                                bookSourceId, fileId, chapterId, chapterPosition, title,
                                durationSeconds, filename, mediaType, sizeBytes, downloadUrl,
                                localPath, downloadedBytes, state, error, updatedAtMs
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any>(
                                V7_DOWNLOAD_SOURCE_ID,
                                V7_DOWNLOAD_FILE_ID,
                                "chapter-v7",
                                0,
                                "Existing chapter",
                                60L,
                                "chapter.mp3",
                                "audio/mpeg",
                                4096L,
                                "https://cdn.example.invalid/chapter.mp3",
                                "manifest-v7/chapter.mp3",
                                4096L,
                                "completed",
                                "",
                                2000L,
                            ),
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }
            )
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.writableDatabase
        helper.close()
    }

    /**
     * Supported production floor: exact v7 shape obtained from the historical
     * production schema through 6 -> 7. Existing tables are intentionally built
     * independently of MIGRATION_7_8 so opening this fixture proves that the
     * current production registration can migrate a v7 database by itself.
     */
    private fun createVersion7Schema(db: SupportSQLiteDatabase) {
        createVersion7BaseSchema(db)
        db.execSQL("ALTER TABLE download_books ADD COLUMN deletedAtMs INTEGER")
        db.execSQL("ALTER TABLE download_books ADD COLUMN purgeAfterMs INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS library_trash (
                kind TEXT NOT NULL,
                targetId TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                coverUrl TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                deletedAtMs INTEGER NOT NULL,
                purgeAfterMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                PRIMARY KEY(kind, targetId)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_library_trash_purgeAfterMs ON library_trash(purgeAfterMs)"
        )
    }

    /** Base tables present in the supported Room v7 production schema before v7 trash columns/table. */
    private fun createVersion7BaseSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_payloads (
                cacheKey TEXT NOT NULL PRIMARY KEY,
                payloadJson TEXT NOT NULL,
                savedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_books (
                bookId TEXT NOT NULL PRIMARY KEY,
                cardJson TEXT NOT NULL,
                updatedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_retention (
                bookId TEXT NOT NULL PRIMARY KEY,
                favoriteRef INTEGER NOT NULL,
                historyRef INTEGER NOT NULL,
                progressRef INTEGER NOT NULL,
                bookmarkRef INTEGER NOT NULL,
                downloadedRef INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_window (
                bookId TEXT NOT NULL PRIMARY KEY,
                rank INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_catalog_window_rank ON catalog_window(rank)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_cache_state (
                id INTEGER NOT NULL PRIMARY KEY,
                maxItems INTEGER NOT NULL,
                serverTotal INTEGER NOT NULL,
                lastSyncedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_entities (
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                revision INTEGER NOT NULL,
                operation TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                PRIMARY KEY(entityType, entityId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_entities_revision ON sync_entities(revision)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_outbox (
                entityKey TEXT NOT NULL PRIMARY KEY,
                mutationId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                operation TEXT NOT NULL,
                baseRevision INTEGER NOT NULL,
                payloadJson TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                blocked INTEGER NOT NULL,
                lastError TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_mutationId ON sync_outbox(mutationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_createdAtMs ON sync_outbox(createdAtMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_blocked ON sync_outbox(blocked)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_state (
                id INTEGER NOT NULL PRIMARY KEY,
                cursor INTEGER NOT NULL,
                snapshotComplete INTEGER NOT NULL,
                lastAttemptAtMs INTEGER NOT NULL,
                lastSyncedAtMs INTEGER NOT NULL,
                lastError TEXT NOT NULL,
                profileId TEXT NOT NULL DEFAULT '',
                lastPullAtMs INTEGER NOT NULL DEFAULT 0,
                lastPushAtMs INTEGER NOT NULL DEFAULT 0,
                lastWorkerAtMs INTEGER NOT NULL DEFAULT 0,
                lastWorkerResult TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS download_books (
                bookSourceId TEXT NOT NULL PRIMARY KEY,
                bookId TEXT NOT NULL,
                sourceCode TEXT NOT NULL,
                sourceName TEXT NOT NULL,
                title TEXT NOT NULL,
                coverUrl TEXT NOT NULL,
                manifestId TEXT NOT NULL,
                state TEXT NOT NULL,
                totalSizeBytes INTEGER,
                downloadedBytes INTEGER NOT NULL,
                filesCount INTEGER NOT NULL,
                completedFiles INTEGER NOT NULL,
                wifiOnly INTEGER NOT NULL,
                error TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_books_bookId ON download_books(bookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_books_state ON download_books(state)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS download_files (
                bookSourceId TEXT NOT NULL,
                fileId TEXT NOT NULL,
                chapterId TEXT NOT NULL,
                chapterPosition INTEGER NOT NULL,
                title TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                filename TEXT NOT NULL,
                mediaType TEXT NOT NULL,
                sizeBytes INTEGER,
                downloadUrl TEXT NOT NULL,
                localPath TEXT NOT NULL,
                downloadedBytes INTEGER NOT NULL,
                state TEXT NOT NULL,
                error TEXT NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                PRIMARY KEY(bookSourceId, fileId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_files_bookSourceId ON download_files(bookSourceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_files_state ON download_files(state)")
    }

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val CURRENT_VERSION = 8
        const val SENTINEL_KEY = "migration:sentinel"
        const val SENTINEL_JSON = "{\"ok\":true}"
        const val SENTINEL_SAVED_AT = 123456789L
        const val V7_DOWNLOAD_SOURCE_ID = "book-7::test-source"
        const val V7_DOWNLOAD_BOOK_ID = "book-7"
        const val V7_DOWNLOAD_FILE_ID = "file-7"
    }
}
