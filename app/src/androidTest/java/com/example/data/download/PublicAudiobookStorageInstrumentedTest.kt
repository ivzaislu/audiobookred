package com.example.data.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.DownloadFileEntity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PublicAudiobookStorageInstrumentedTest {
    @Test
    @Suppress("DEPRECATION")
    fun scopedDeleteRemovesMediaStoreFileAndEmptyBookDirectory() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PublicAudiobookStorage(context)
        assertTrue(storage.canPublish())

        val token = System.currentTimeMillis().toString()
        val bookSourceId = "instrumented-storage:" + token
        val bookTitle = "Storage cleanup " + token
        val source = File(context.cacheDir, "public-audiobook-" + token + ".mp3")
        source.writeBytes(ByteArray(64) { index -> (index and 0xff).toByte() })

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

        var publishedUri: Uri? = null
        var bookDirectory: File? = null
        try {
            val published = storage.publish(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
                file = file,
                source = source,
            )
            publishedUri = published.uri

            val dataPath = context.contentResolver.query(
                published.uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
            } ?: error("Published MediaStore row is missing")

            val directory = File(dataPath).parentFile
                ?: error("Published audiobook directory is missing")
            bookDirectory = directory
            assertTrue(directory.isDirectory)
            assertTrue(contentUriExists(context, published.uri))
            assertTrue(
                storage.playbackUri(
                    bookSourceId = bookSourceId,
                    bookTitle = bookTitle,
                    file = file,
                    expectedBytes = source.length(),
                ) == published.uri,
            )

            storage.deleteBookArtifacts(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
            )

            assertFalse(contentUriExists(context, published.uri))
            assertTrue(
                storage.playbackUri(
                    bookSourceId = bookSourceId,
                    bookTitle = bookTitle,
                    file = file,
                    expectedBytes = source.length(),
                ) == null,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertFalse(
                    "Empty audiobook directory must be removed after deleting the book",
                    directory.exists(),
                )
            }

            val republished = storage.publish(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
                file = file,
                source = source,
            )
            publishedUri = republished.uri
            assertTrue(contentUriExists(context, republished.uri))
            assertTrue(
                storage.playbackUri(
                    bookSourceId = bookSourceId,
                    bookTitle = bookTitle,
                    file = file,
                    expectedBytes = source.length(),
                ) == republished.uri,
            )

            storage.deleteBookArtifacts(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
            )
            assertFalse(contentUriExists(context, republished.uri))
        } finally {
            runCatching {
                storage.deleteBookArtifacts(
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

    private fun contentUriExists(context: Context, uri: Uri): Boolean =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads._ID),
            null,
            null,
            null,
        )?.use { cursor -> cursor.moveToFirst() } ?: false
}
