package com.example.data.storage

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicAppDocumentStorageInstrumentedTest {
    @Test
    fun fileAndByteBackupsUseTheSamePublicDirectoryAndPreserveContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PublicAppDocumentStorage(context)
        assumeTrue(storage.canWrite())
        val source = File.createTempFile("text-backup-", ".zip", context.cacheDir)
        val bytes = ByteArray(96 * 1024 + 7) { (it % 251).toByte() }
        val stored = mutableListOf<PublicAppDocumentStorage.StoredDocument>()
        try {
            source.writeBytes(bytes)
            val name = "AudioBookRed-storage-test-" + System.nanoTime()
            stored += storage.writeBackup("$name.zip", "application/zip", source)
            stored += storage.writeBackup("$name.json", "application/json", bytes)
            for (document in stored) {
                assertTrue(document.relativePath.startsWith("Download/AudioBookRed/Backups/"))
                assertEquals(bytes.size.toLong(), document.sizeBytes)
                val actual = if (document.uri.scheme == "file") {
                    File(checkNotNull(document.uri.path)).readBytes()
                } else {
                    checkNotNull(context.contentResolver.openInputStream(document.uri)).use { it.readBytes() }
                }
                assertArrayEquals(bytes, actual)
            }
        } finally {
            source.delete()
            stored.forEach { deleteDocument(context, it.uri) }
        }
    }

    private fun deleteDocument(context: Context, uri: Uri) {
        if (uri.scheme == "file") {
            File(checkNotNull(uri.path)).delete()
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
