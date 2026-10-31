package com.example.data.textbook

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.storage.PublicTextBookStorage
import com.example.data.textsource.RemoteTextDownload
import com.example.data.textsource.TextBookFileStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TextManagedOriginalsInstrumentedTest {
    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "managed-originals-$token").apply { mkdirs() }
        private val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$token-$name"
                preferences += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(base, TextLibraryDatabase::class.java).build()
        val books = TextLibraryRepository(context, db)
        val files = TextBookFileStore(context.filesDir)
        val publicStorage = PublicTextBookStorage(context)
        private val copies get() = context.getSharedPreferences(PublicTextBookStorage.PREFS_NAME, Context.MODE_PRIVATE)

        fun source(extension: String = "txt", bytes: ByteArray = "Книга $token".toByteArray()): File =
            File(root, "input-$token.$extension").apply { writeBytes(bytes) }
        fun currentUri(): Uri = Uri.parse(copies.all.filterKeys { !it.startsWith("retired:") }.values.filterIsInstance<String>().single())
        fun exists(uri: Uri): Boolean = base.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
            null, null, null)?.use { it.moveToFirst() } ?: false
        override fun close() {
            val uris = copies.all.values.flatMap {
                when (it) {
                    is String -> listOf(it)
                    is Set<*> -> it.filterIsInstance<String>()
                    else -> emptyList()
                }
            }.distinct()
            uris.forEach { runCatching { base.contentResolver.delete(Uri.parse(it), null, null) } }
            db.close()
            root.deleteRecursively()
            preferences.toList().forEach { base.deleteSharedPreferences(it) }
        }
    }

    @Test fun leavingTheSourceInPlacePreservesItWhenTheBookIsRemoved() = runBlocking {
        Fixture().use { f ->
            val source = f.source()
            val bytes = source.readBytes()
            val result = f.books.importBook(Uri.fromFile(source))
            val row = f.db.library().book(result.bookId)!!
            assertFalse(result.originalMoved)
            assertNull(result.moveError)
            assertTrue(f.files.originalsFor(row.sourceSha256).isEmpty())
            f.books.remove(result.bookId)
            assertNull(f.db.library().book(result.bookId))
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(File(f.context.filesDir, "text-library/" + row.sourceFileName).exists())
        }
    }

    @Test fun movingTheSourceAndRemovingTheBookCleansPrivateAndPublicOriginals() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Fixture().use { f ->
            val source = f.source()
            val bytes = source.readBytes()
            val result = f.books.importBook(Uri.fromFile(source), moveOriginal = true)
            assertNull(result.moveError)
            assertTrue(result.originalMoved)
            assertFalse(source.exists())
            val row = f.db.library().book(result.bookId)!!
            val original = f.files.originalsFor(row.sourceSha256).single()
            val uri = f.currentUri()
            assertArrayEquals(bytes, original.readBytes())
            assertArrayEquals(bytes, f.base.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            f.books.remove(result.bookId)
            assertNull(f.db.library().book(result.bookId))
            assertFalse(original.exists())
            assertFalse(f.exists(uri))
        }
    }

    @Test fun legacyDownloadedCopiesAreDeletedWithoutAnAdditionalOwnershipRecord() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Fixture().use { f ->
            val bytes = "Скачанная книга ${UUID.randomUUID()}".toByteArray()
            val original = f.files.save("source", "12", "Скачанная книга",
                RemoteTextDownload("txt", "TXT", "", "txt"), bytes)
            f.publicStorage.publish(original, "legacy-${UUID.randomUUID()}.txt", "text/plain", bytes) {}
            val uri = f.currentUri()
            val result = f.books.importDownloaded(original, "Скачанная книга.txt")
            f.books.remove(result.bookId)
            assertFalse(original.exists())
            assertFalse(f.exists(uri))
            assertNull(f.db.library().book(result.bookId))
        }
    }

    @Test fun aSharedArchiveSurvivesUntilItsLastImportedBookIsRemoved() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Fixture().use { f ->
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    listOf("first.txt", "second.txt").forEach { name ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write("Книга $name ${UUID.randomUUID()}".toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            val source = f.source("zip", bytes)
            val first = f.books.importBook(Uri.fromFile(source), "first.txt", moveOriginal = true)
            assertTrue(first.originalMoved)
            val firstUri = f.currentUri()
            // Importing from Books must not delete the only public copy while moving it.
            val second = f.books.importBook(firstUri, "second.txt", moveOriginal = true)
            assertNull(second.moveError)
            assertTrue(second.originalMoved)
            val secondUri = f.currentUri()
            assertNotEquals(firstUri, secondUri)
            val hash = f.db.library().book(first.bookId)!!.sourceSha256
            assertEquals(hash, f.db.library().book(second.bookId)!!.sourceSha256)
            val originals = f.files.originalsFor(hash)
            assertTrue(originals.isNotEmpty())
            f.books.remove(first.bookId)
            assertTrue(originals.all { it.isFile })
            assertTrue(f.exists(secondUri))
            assertNotNull(f.db.library().book(second.bookId))
            f.books.remove(second.bookId)
            assertTrue(originals.none { it.exists() })
            assertFalse(f.exists(secondUri))
        }
    }

    @Test fun rejectedImportsNeverMoveOrDeleteTheSelectedSource() = runBlocking {
        Fixture().use { f ->
            val source = f.source(bytes = byteArrayOf(0, 1, 2, 3))
            try {
                f.books.importBook(Uri.fromFile(source), moveOriginal = true)
                fail("Invalid input should be rejected before moving it")
            } catch (_: IllegalArgumentException) { }
            assertArrayEquals(byteArrayOf(0, 1, 2, 3), source.readBytes())
            assertFalse(File(f.context.filesDir, "book").exists())
        }
    }

    @Test fun aReadOnlySourceIsKeptAndTheIncompleteMoveIsReported() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Fixture().use { f ->
            val directory = File(f.root, "read-only").apply { mkdirs() }
            val source = File(directory, "book-${UUID.randomUUID()}.txt").apply { writeText("Книга для переноса") }
            try {
                assumeTrue(directory.setWritable(false, false))
                val result = f.books.importBook(Uri.fromFile(source), moveOriginal = true)
                assertFalse(result.originalMoved)
                assertNotNull(result.moveError)
                assertTrue(source.isFile)
                assertTrue(f.exists(f.currentUri()))
                f.books.remove(result.bookId)
                assertTrue(source.isFile)
            } finally { directory.setWritable(true, false) }
        }
    }

    @Test fun aDamagedPrivateSourceNeverReplacesOrDeletesTheSelectedOriginal() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Fixture().use { f ->
            val source = f.source()
            val bytes = source.readBytes()
            val first = f.books.importBook(Uri.fromFile(source))
            val row = f.db.library().book(first.bookId)!!
            File(f.context.filesDir, "text-library/" + row.sourceFileName).writeText("Повреждённая копия")
            val moved = f.books.importBook(Uri.fromFile(source), moveOriginal = true)
            assertFalse(moved.originalMoved)
            assertNotNull(moved.moveError)
            assertArrayEquals(bytes, source.readBytes())
            assertTrue(f.files.originalsFor(row.sourceSha256).isEmpty())
        }
    }
}
