package com.example.data.textbook

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TextBookMetadataStoreInstrumentedTest {
    @Test fun sourceDescriptionSurvivesReopeningAndPreservesImportedMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "metadata-${UUID.randomUUID()}")
        val id = "txt_" + "a".repeat(64)
        try {
            val store = TextBookMetadataStore(folder)
            val ebook = EbookImportParser.parse("Текст книги".toByteArray(), "book.txt")
                .copy(author = "Автор", entry = "archive/book.txt")
            store.save(id, ebook)
            assertEquals("", store.read(id).description)
            assertTrue(store.saveDescription(id, "Описание из источника"))
            assertFalse(store.saveDescription(id, "Описание из источника"))
            assertFalse(store.saveDescription(id, ""))
            val reopened = TextBookMetadataStore(folder).read(id)
            assertEquals("Описание из источника", reopened.description)
            assertEquals("Автор", reopened.author)
            assertEquals("TXT", reopened.format)
            val json = JSONObject(File(folder, "$id.json").readText())
            assertEquals("archive/book.txt", json.getString("entry"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun sourceAuthorsFillTxtMetadataAndRemainSeparateAfterReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "source-authors-${UUID.randomUUID()}")
        val id = "txt_" + "b".repeat(64)
        try {
            val store = TextBookMetadataStore(folder)
            store.save(id, EbookImportParser.parse("Текст без имени автора".toByteArray(), "book.txt"))
            assertEquals("", store.read(id).author)
            assertTrue(store.saveSourceDetails(id, "Описание", listOf(" Первый автор ", "Второй автор", "Первый автор")))
            val reopened = TextBookMetadataStore(folder).read(id)
            assertEquals("Первый автор, Второй автор", reopened.author)
            assertEquals(listOf("Первый автор", "Второй автор"), reopened.sourceAuthors)
            assertEquals("Описание", reopened.description)
            assertFalse(store.saveSourceDetails(id, "Описание", reopened.sourceAuthors))
        } finally { folder.deleteRecursively() }
    }

    @Test fun sourceAuthorsDoNotReplaceTheAuthorStoredInsideAnImportedBook() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "imported-author-${UUID.randomUUID()}")
        val id = "txt_" + "c".repeat(64)
        try {
            val store = TextBookMetadataStore(folder)
            store.save(id, EbookImportParser.parse("Исходный текст".toByteArray(), "book.txt").copy(author = "Автор файла"))
            store.saveSourceDetails(id, "", listOf("Автор источника"))
            assertEquals("Автор файла", store.read(id).author)
            assertEquals(listOf("Автор источника"), store.read(id).sourceAuthors)
        } finally { folder.deleteRecursively() }
    }
}
