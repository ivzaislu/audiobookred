package com.example.data.textsource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.storage.TextBookOriginalPublisher
import com.example.data.textbook.TextLibraryDatabase
import com.example.data.textbook.TextLibraryRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteTextImportFailureInstrumentedTest {
    private class Publisher : TextBookOriginalPublisher {
        var fail = false
        val published = mutableListOf<File>()
        override fun canPublish() = true
        override fun publish(original: File, displayName: String, mimeType: String, bytes: ByteArray, checkpoint: () -> Unit) {
            checkpoint()
            if (fail) throw IOException("Publication failed")
            assertArrayEquals(bytes, original.readBytes())
            published += original
        }
    }

    private class Fixture(val bytes: ByteArray, val extension: String) : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "import-failure-$token").apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$token-$name"
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(base, TextLibraryDatabase::class.java).build()
        val books = TextLibraryRepository(context, db)
        val files = TextBookFileStore(root)
        val publisher = Publisher()
        val provider = object : RemoteTextBookProvider {
            override val code = "fixture"
            override val name = "Тестовый источник"
            override val catalogUrl = "https://fixture.invalid/books"
            override val importExtension get() = extension
            override fun bookUrl(id: String) = "https://fixture.invalid/book/$id"
            override fun bookId(url: String) = url.removePrefix("https://fixture.invalid/book/")
                .takeIf { it.matches(Regex("[0-9]+")) }
            override fun searchUrl(query: String) = "$catalogUrl?q=$query"
            override fun isPageUrl(url: String) = url.startsWith(catalogUrl)
            override suspend fun page(url: String): RemoteTextPage = error("Not used")
            override suspend fun book(id: String): RemoteTextBook = error("Not used")
            override suspend fun download(book: RemoteTextBook) = bytes
            override suspend fun downloadFile(book: RemoteTextBook, option: RemoteTextDownload) = bytes
        }
        val option = RemoteTextDownload("selected", extension, "https://fixture.invalid/download", extension)
        val book = RemoteTextBook("1", "Книга", provider.bookUrl("1"), downloads = listOf(option))
        val repository = RemoteTextBookRepository(context, RemoteTextSourceRegistry(listOf(provider)), books, publisher)
        fun original() = files.originalFile(provider.code, book.id, book.title, option, bytes)
        override fun close() {
            db.close()
            root.deleteRecursively()
            preferenceNames.toList().forEach { base.deleteSharedPreferences(it) }
        }
    }

    @Test fun invalidDownloadIsNotPublishedAndItsNewPrivateCopyIsRemoved() = runBlocking {
        Fixture("<html>Service unavailable</html>".toByteArray(), "fb2.zip").use { f ->
            try { f.repository.importBook(f.book); fail("Broken archive") }
            catch (_: IllegalArgumentException) { }
            assertTrue(f.publisher.published.isEmpty())
            assertTrue(f.books.observeBooks().first().isEmpty())
            assertFalse(f.original().exists())
            assertFalse(File(f.root, "book").walkTopDown().any { it.isFile })
        }
    }

    @Test fun invalidImportDoesNotRemoveAPreviouslyExistingOriginal() = runBlocking {
        Fixture("<html>Service unavailable</html>".toByteArray(), "fb2.zip").use { f ->
            val original = f.files.save(f.provider.code, f.book.id, f.book.title, f.option, f.bytes)
            try { f.repository.importBook(f.book); fail("Broken archive") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(f.bytes, original.readBytes())
            assertTrue(f.publisher.published.isEmpty())
        }
    }

    @Test fun publicationFailureKeepsTheImportedBookAndCanBeRetriedWithoutDuplicatingIt() = runBlocking {
        Fixture("Текст для проверки неудачной публикации и повторной загрузки.".toByteArray(), "txt").use { f ->
            f.publisher.fail = true
            try { f.repository.importBook(f.book); fail("Publication must fail") }
            catch (error: IOException) { assertTrue(error.message.orEmpty().contains("Книга добавлена")) }
            val stored = f.books.observeBooks().first().single()
            assertTrue(f.original().isFile)
            assertTrue(File(f.root, "text-library/${stored.sourceFileName}").isFile)
            assertNull(f.repository.localBookId(f.book.id))
            f.publisher.fail = false
            val retry = f.repository.importBook(f.book)
            assertEquals(stored.id, retry.bookId)
            assertTrue(retry.duplicate)
            assertEquals(1, f.books.observeBooks().first().size)
            assertEquals(stored.id, f.repository.localBookId(f.book.id))
            assertEquals(listOf(f.original()), f.publisher.published)
        }
    }
}
