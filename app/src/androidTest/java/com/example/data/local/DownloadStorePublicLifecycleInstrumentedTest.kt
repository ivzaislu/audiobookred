package com.example.data.local

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.download.PublicAudiobookStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DownloadStorePublicLifecycleInstrumentedTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    @Suppress("DEPRECATION")
    fun completedPublicDownloadPlaysDeletesAndCanBePublishedAgain() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val cacheStore = LocalCacheStore(context)
        val store = DownloadStore(context, cacheStore)
        val database = AbredDatabase.get(context)
        val publicStorage = PublicAudiobookStorage(context)
        val token = System.currentTimeMillis().toString()
        val bookSourceId = "instrumented-store:" + token
        val bookId = "book-" + token
        val bookTitle = "Lifecycle " + token
        val source = File(context.cacheDir, "download-store-lifecycle-" + token + ".mp3")
        source.writeBytes(ByteArray(96) { index -> (index and 0xff).toByte() })

        val file = DownloadFileEntity(
            bookSourceId = bookSourceId,
            fileId = "file-" + token,
            chapterId = "chapter-1",
            chapterPosition = 0,
            title = "Chapter",
            filename = "chapter.mp3",
            mediaType = "audio/mpeg",
            sizeBytes = source.length(),
            downloadUrl = "https://example.invalid/chapter.mp3",
            localPath = "downloads/instrumented/" + token + "/chapter.mp3",
            downloadedBytes = source.length(),
            state = "completed",
        )
        val book = DownloadBookEntity(
            bookSourceId = bookSourceId,
            bookId = bookId,
            sourceCode = "instrumented",
            sourceName = "Instrumented",
            title = bookTitle,
            manifestId = "manifest-" + token,
            state = "completed",
            totalSizeBytes = source.length(),
            downloadedBytes = source.length(),
            filesCount = 1,
            completedFiles = 1,
        )

        var publishedUri: Uri? = null
        var bookDirectory: File? = null
        try {
            database.downloads().putBook(book)
            database.downloads().putFiles(listOf(file))
            cacheStore.setDownloadedReference(bookId, true)

            val published = publicStorage.publish(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
                file = file,
                source = source,
            )
            publishedUri = published.uri
            bookDirectory = mediaDirectory(published.uri)

            assertTrue(store.playbackUri(file) == published.uri)
            assertTrue(database.localCatalog().retention(bookId)?.downloadedRef == true)

            val removed = store.markPendingDelete(bookSourceId)
            assertTrue(removed?.state == "purged")
            assertNull(database.downloads().book(bookSourceId))
            assertTrue(database.downloads().files(bookSourceId).isEmpty())
            assertFalse(contentUriExists(published.uri))
            assertFalse(database.localCatalog().retention(bookId)?.downloadedRef ?: false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertFalse(bookDirectory?.exists() ?: false)
            }

            val republished = publicStorage.publish(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
                file = file,
                source = source,
            )
            publishedUri = republished.uri
            bookDirectory = mediaDirectory(republished.uri)
            assertTrue(contentUriExists(republished.uri))
        } finally {
            runCatching {
                publicStorage.deleteBookArtifacts(
                    bookSourceId = bookSourceId,
                    bookTitle = bookTitle,
                )
            }
            publishedUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            bookDirectory
                ?.takeIf { directory -> directory.isDirectory && directory.listFiles()?.isEmpty() == true }
                ?.delete()
            source.delete()
        }
    }

    private fun mediaDirectory(uri: Uri): File? =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                ?.let(::File)
                ?.parentFile
        }

    private fun contentUriExists(uri: Uri): Boolean =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads._ID),
            null,
            null,
            null,
        )?.use { cursor -> cursor.moveToFirst() } ?: false

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
    }
}
