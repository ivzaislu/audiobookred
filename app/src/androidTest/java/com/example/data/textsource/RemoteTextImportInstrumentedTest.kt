package com.example.data.textsource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.player.PlaybackResumeStore
import com.example.data.textbook.TextLibraryDatabase
import com.example.data.textbook.TextLibraryRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RemoteTextImportInstrumentedTest {
    @Test fun remoteImportReusesManualBookAndBothBookmarksAcrossRepositoryRecreation() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = "remote-text-${UUID.randomUUID()}"
        val folder = File(base.cacheDir, unique).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = folder
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$unique-$name", Context.MODE_PRIVATE)
        }
        val db = Room.inMemoryDatabaseBuilder(base, TextLibraryDatabase::class.java).build()
        val input = File(folder, "book.zip")
        val prefsName = "$unique-audio"
        try {
            val xml = """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Тестовая книга</book-title><author><first-name>Автор</first-name><last-name>Теста</last-name></author><lang>ru</lang></title-info></description><body><section><title><p>Глава 1</p></title><p>Первый достаточно длинный текст книги для проверки импорта и позиции чтения.</p><p>Второй абзац этой тестовой книги для независимого прогресса воспроизведения.</p></section></body></FictionBook>"""
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("book.fb2")); zip.write(xml.toByteArray()); zip.closeEntry() }
            }.toByteArray()
            input.writeBytes(bytes)
            val books = TextLibraryRepository(context, db)
            val manual = books.importDownloaded(input, "manual.zip")
            val reader = books.open(manual.bookId)
            val fragment = reader.fragments.last().id
            assertTrue(books.savePosition(manual.bookId, fragment, 21))
            val readingBefore = books.open(manual.bookId).position
            val audio = PlaybackResumeStore(base, prefsName)
            audio.saveImmediate(manual.bookId, "text_narration", fragment, 0, 1200, 1.25f)
            val audioBefore = audio.get(manual.bookId, "text_narration")
            var calls = 0
            val provider = FantasyWorldsProvider(OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bytes.toResponseBody()).build()
            }.build())
            val remote = RemoteTextBook("1465", "Тестовая книга", FantasyWorldsHtmlParser.bookUrl("1465"),
                downloadUrl = FantasyWorldsHtmlParser.ORIGIN + "/lib/id1465/download/",
                series = TextSourceLink("17", "Тестовый цикл", FantasyWorldsHtmlParser.ORIGIN + "/series/id17/"), seriesNumber = "1.5")
            val second = object : RemoteTextBookProvider by provider {
                override val code = "second"
                override val name = "Второй источник"
                override val catalogUrl = "https://second.test/books"
                override fun bookUrl(id: String) = "https://second.test/book/$id"
                override fun bookId(url: String): String? = url.removePrefix("https://second.test/book/")
                    .takeIf { it.matches(Regex("[0-9]+")) }
                override suspend fun download(book: RemoteTextBook) = ByteArrayOutputStream().also { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("book.fb2"))
                        zip.write(xml.replace("Тестовая книга", "Другая книга").toByteArray())
                        zip.closeEntry()
                    }
                }.toByteArray()
            }
            val registry = RemoteTextSourceRegistry(listOf(provider, second))
            val repository = RemoteTextBookRepository(context, registry, books)
            val fb2 = RemoteTextDownload("fb2", "FB2 ZIP", requireNotNull(remote.downloadUrl), "fb2.zip")
            val result = repository.importBook(remote.copy(downloads = listOf(fb2)), provider.code, fb2)
            assertEquals(manual.bookId, result.bookId)
            assertTrue(result.duplicate)
            assertEquals(1, calls)
            assertEquals(readingBefore, books.open(result.bookId).position)
            val other = repository.importBook(remote.copy(url = second.bookUrl("1465")), second.code)
            assertNotEquals(result.bookId, other.bookId)
            assertEquals(other.bookId, repository.localBookId("1465", second.code))
            val recreated = RemoteTextBookRepository(context, registry, TextLibraryRepository(context, db))
            assertEquals(result.bookId, recreated.localBookId("1465"))
            assertEquals(other.bookId, recreated.localBookId("1465", second.code))
            assertEquals(result.bookId, recreated.importBook(remote).bookId)
            assertEquals(1, calls)
            assertEquals(audioBefore, PlaybackResumeStore(base, prefsName).get(manual.bookId, "text_narration"))
            assertEquals(1, db.library().chapters(manual.bookId).map { it.bookId }.distinct().size)
            val originals = File(folder, "book").walkTopDown().filter { it.isFile }.toList()
            assertEquals(2, originals.size)
            assertTrue(originals.any { it.readBytes().contentEquals(bytes) })
            assertTrue(originals.all { it.name.endsWith(".fb2.zip") })
            assertEquals(readingBefore, books.open(result.bookId).position)
            assertEquals(audioBefore, audio.get(manual.bookId, "text_narration"))
            books.hideHistory(manual.bookId)
            books.hideSeries("text:${provider.code}:17")
            val restoredBooks = TextLibraryRepository(context, db)
            val activity = restoredBooks.observeActivity().value.getValue(manual.bookId)
            assertEquals("Тестовый цикл", activity.seriesName)
            assertEquals("1.5", activity.seriesNumber)
            assertFalse(activity.historyVisible)
            assertFalse(activity.seriesVisible)
            assertEquals(readingBefore, db.library().position(manual.bookId))
            assertEquals(audioBefore, audio.get(manual.bookId, "text_narration"))
            assertEquals(result.bookId, recreated.localBookId("1465"))
            val unsupported = RemoteTextDownload("mobi", "MOBI", requireNotNull(remote.downloadUrl), "mobi")
            try { recreated.importBook(remote.copy(downloads = listOf(unsupported)), provider.code, unsupported); fail("MOBI import") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, calls)

        } finally {
            db.close()
            folder.deleteRecursively()
            base.deleteSharedPreferences("$unique-abred_remote_text_origins")
            base.deleteSharedPreferences("$unique-abred_text_library_activity")
            base.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun chosenTxtIsSavedInBookAndImportedAsTxtRatherThanDefaultFb2() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = "remote-txt-${UUID.randomUUID()}"
        val folder = File(base.cacheDir, unique).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$unique-$name", Context.MODE_PRIVATE)
        }
        val db = Room.inMemoryDatabaseBuilder(base, TextLibraryDatabase::class.java).build()
        try {
            val bytes = "Первый абзац выбранной текстовой книги.\n\nВторой абзац для проверки выбранного формата и сохранения оригинала.".toByteArray()
            val provider = FantasyWorldsProvider(OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("/lib/id1465/download/txt", chain.request().url.encodedPath)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bytes.toResponseBody()).build()
            }.build())
            val txt = RemoteTextDownload("txt", "TXT", FantasyWorldsHtmlParser.ORIGIN + "/lib/id1465/download/txt", "txt")
            val book = RemoteTextBook("1465", "Выбранный TXT", FantasyWorldsHtmlParser.bookUrl("1465"),
                downloadUrl = FantasyWorldsHtmlParser.ORIGIN + "/lib/id1465/download/", downloads = listOf(txt))
            val books = TextLibraryRepository(context, db)
            val repository = RemoteTextBookRepository(context, RemoteTextSourceRegistry(listOf(provider)), books)
            val result = repository.importBook(book, provider.code, txt)
            assertEquals("Выбранный TXT", books.open(result.bookId).book.title)
            assertEquals("TXT", books.metadata(result.bookId).format)
            val original = File(folder, "book").walkTopDown().single { it.isFile }
            assertTrue(original.name.endsWith(".txt"))
            assertArrayEquals(bytes, original.readBytes())
            assertEquals(result.bookId, repository.localBookId(book.id))
        } finally {
            db.close()
            folder.deleteRecursively()
            base.deleteSharedPreferences("$unique-abred_remote_text_origins")
            base.deleteSharedPreferences("$unique-abred_text_library_activity")
        }
    }
}
