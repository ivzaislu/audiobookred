package com.example.data.storage

import android.content.Context
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PublicTextBookStorageInstrumentedTest {
    @Test fun originalIsPublishedInBookReusedAndRestoredIfUserDeletedTheCopy() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val prefsName = "text-public-$token"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefsName, mode)
        }
        val original = File(base.cacheDir, "$token.source")
        val bytes = "Исходная текстовая книга — проверка сохранения.".toByteArray()
        original.writeBytes(bytes)
        val uris = mutableListOf<Uri>()
        try {
            val prefs = base.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val title = "Книга $token.txt"
            fun storedUri() = Uri.parse(prefs.all.values.filterIsInstance<String>().single()).also { uris += it }
            PublicTextBookStorage(context).publish(original, title, "text/plain", bytes) {}
            val first = storedUri()
            base.contentResolver.query(first, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Download/AudioBookRed/Books/", cursor.getString(0))
            }
            assertArrayEquals(bytes, base.contentResolver.openInputStream(first)!!.use { it.readBytes() })
            PublicTextBookStorage(context).publish(original, title, "text/plain", bytes) {}
            assertEquals(first, storedUri())
            assertEquals(1, base.contentResolver.delete(first, null, null))
            PublicTextBookStorage(context).publish(original, title, "text/plain", bytes) {}
            val restored = storedUri()
            assertArrayEquals(bytes, base.contentResolver.openInputStream(restored)!!.use { it.readBytes() })
            assertArrayEquals(bytes, original.readBytes())
        } finally {
            uris.distinct().forEach { base.contentResolver.delete(it, null, null) }
            base.deleteSharedPreferences(prefsName)
            original.delete()
        }
    }

    @Test fun cancelledWriteRemovesItsPendingPublicDocument() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cancelled-${UUID.randomUUID()}.txt"
        val storage = PublicAppDocumentStorage(context)
        var calls = 0
        try {
            assertThrows(CancellationException::class.java) {
                storage.writeBook(name, "text/plain", ByteArray(100_000)) {
                    if (++calls == 3) throw CancellationException("Cancelled")
                }
            }
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { cursor ->
                assertEquals(0, cursor.count)
            }
        } finally {
            context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name))
        }
    }

    @Test fun moveCreatesAFreshCopyAndRemovalDeletesAllTrackedPublicCopies() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val prefsName = "text-move-$token"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefsName, mode)
        }
        val original = File(base.cacheDir, "$token.source").apply { writeText("Книга $token") }
        val prefs = base.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val storage = PublicTextBookStorage(context)
        val uris = mutableSetOf<Uri>()
        fun currentUri() = Uri.parse(prefs.all.values.filterIsInstance<String>().single()).also { uris += it }
        try {
            storage.publish(original, "$token.txt", "text/plain", original.readBytes()) {}
            val first = currentUri()
            storage.publishForMove(original, "$token.txt", "text/plain", original.readBytes()) {}
            val moved = currentUri()
            assertNotEquals(first, moved)
            assertArrayEquals(original.readBytes(), base.contentResolver.openInputStream(first)!!.use { it.readBytes() })
            assertArrayEquals(original.readBytes(), base.contentResolver.openInputStream(moved)!!.use { it.readBytes() })
            storage.remove(original)
            uris.forEach { uri ->
                base.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                    assertEquals(0, it.count)
                }
            }
            assertTrue(prefs.all.isEmpty())
            assertTrue(original.isFile) // Private-file deletion belongs to the repository.
            storage.remove(original) // A retry after deletion is harmless.
        } finally {
            uris.forEach { base.contentResolver.delete(it, null, null) }
            base.deleteSharedPreferences(prefsName)
            original.delete()
        }
    }

    @Test fun aPublicCopyMovedOutOfBooksByTheUserIsPreserved() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val prefsName = "text-relocated-$token"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefsName, mode)
        }
        val original = File(base.cacheDir, "$token.source").apply { writeText("Книга $token") }
        var uri: Uri? = null
        try {
            val storage = PublicTextBookStorage(context)
            storage.publish(original, "$token.txt", "text/plain", original.readBytes()) {}
            uri = Uri.parse(base.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.values.filterIsInstance<String>().single())
            val saved = checkNotNull(uri)
            assertEquals(1, base.contentResolver.update(saved, ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AudioBookRed/Exports/")
            }, null, null))
            storage.remove(original)
            assertArrayEquals(original.readBytes(), base.contentResolver.openInputStream(saved)!!.use { it.readBytes() })
        } finally {
            uri?.let { base.contentResolver.delete(it, null, null) }
            base.deleteSharedPreferences(prefsName)
            original.delete()
        }
    }
}
