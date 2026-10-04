package com.example.data.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.DownloadFileDto
import com.example.data.model.DownloadManifestDto
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DownloadStoreLifecycleRaceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
        File(context.filesDir, "downloads").deleteRecursively()
    }

    @After
    fun tearDown() {
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
        File(context.filesDir, "downloads").deleteRecursively()
    }

    @Test
    fun staleManifestWorkerCannotMutateReplacementManifest() = runBlocking {
        val store = DownloadStore(context, LocalCacheStore(context))
        store.prepare(manifest(MANIFEST_A), wifiOnly = true)
        store.prepare(manifest(MANIFEST_B), wifiOnly = true)

        store.markFileState(
            bookSourceId = BOOK_SOURCE_ID,
            expectedManifestId = MANIFEST_A,
            fileId = FILE_ID,
            state = "completed",
            downloadedBytes = 123L,
        )

        val book = store.book(BOOK_SOURCE_ID) ?: error("replacement book missing")
        val file = store.files(BOOK_SOURCE_ID).single()
        assertEquals(MANIFEST_B, book.manifestId)
        assertEquals("queued", file.state)
        assertEquals(0L, file.downloadedBytes)
    }

    @Test
    fun rutrackerManifestPreservesConfiguredTorrServeStreamUrl() = runBlocking {
        val store = DownloadStore(context, LocalCacheStore(context))
        val streamUrl =
            "http://192.168.1.20:8090/stream/001.mp3?link=0123456789abcdef&index=1&play"
        store.prepare(
            DownloadManifestDto(
                manifestId = "rutracker-manifest",
                bookId = "rutracker:6910707",
                bookSourceId = "live:rutracker:6910707",
                sourceCode = "rutracker",
                sourceName = "RuTracker",
                title = "Test book",
                filesCount = 1,
                files = listOf(
                    DownloadFileDto(
                        fileId = "rutracker-file-1",
                        chapterId = "rutracker-chapter-1",
                        chapterPosition = 0,
                        title = "Chapter 1",
                        filename = "001.mp3",
                        sizeBytes = 123L,
                        delivery = "torrserve",
                        downloadUrl = streamUrl,
                    )
                ),
            ),
            wifiOnly = true,
        )

        assertEquals(streamUrl, store.files("live:rutracker:6910707").single().downloadUrl)
    }

    @Test
    fun deletedParentCannotBeResurrectedByStaleFileUpdate() = runBlocking {
        val store = DownloadStore(context, LocalCacheStore(context))
        store.prepare(manifest(MANIFEST_B), wifiOnly = true)
        store.markPendingDelete(BOOK_SOURCE_ID)

        store.markFileState(
            bookSourceId = BOOK_SOURCE_ID,
            expectedManifestId = MANIFEST_B,
            fileId = FILE_ID,
            state = "completed",
            downloadedBytes = 123L,
        )

        assertNull(store.book(BOOK_SOURCE_ID))
        assertEquals(emptyList<DownloadFileEntity>(), store.files(BOOK_SOURCE_ID))
    }

    private fun manifest(manifestId: String) = DownloadManifestDto(
        manifestId = manifestId,
        bookId = BOOK_ID,
        bookSourceId = BOOK_SOURCE_ID,
        sourceCode = "test-source",
        sourceName = "Test",
        title = "Test book",
        filesCount = 1,
        files = listOf(
            DownloadFileDto(
                fileId = FILE_ID,
                chapterId = "chapter-1",
                chapterPosition = 1,
                title = "Chapter 1",
                filename = "chapter.mp3",
                sizeBytes = 123L,
                downloadUrl = "https://example.com/chapter.mp3",
            )
        ),
    )

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val BOOK_ID = "download-race-book"
        const val BOOK_SOURCE_ID = "live:test:download-race-book"
        const val FILE_ID = "file:chapter/1"
        const val MANIFEST_A = "manifest-A"
        const val MANIFEST_B = "manifest-B"
    }
}
