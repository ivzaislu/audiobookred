package com.example.data.textsource

import org.junit.Assert.*
import org.junit.Test

class RemoteTextSourceRegistryTest {
    private fun source(code: String) = object : RemoteTextBookProvider {
        override val code = code
        override val name = "Источник $code"
        override val catalogUrl = "https://$code.test/books"
        override fun bookId(url: String) = Regex("https://$code.test/book/(\\d+)").matchEntire(url)?.groupValues?.get(1)
        override fun bookUrl(id: String) = "https://$code.test/book/$id"
        override fun searchUrl(query: String) = "https://$code.test/search?q=$query"
        override fun isPageUrl(url: String) = url == catalogUrl
        override suspend fun page(url: String): RemoteTextPage = error("No network in registry test")
        override suspend fun book(id: String): RemoteTextBook = error("No network in registry test")
        override suspend fun download(book: RemoteTextBook): ByteArray = error("No network in registry test")
        override suspend fun downloadFile(book: RemoteTextBook, option: RemoteTextDownload): ByteArray = error("No network in registry test")
    }

    @Test fun sourceSelectionAndOverlappingBookIdsRemainIndependent() {
        val first = source("first")
        val second = source("second")
        val registry = RemoteTextSourceRegistry(listOf(first, second))
        assertSame(first, registry.default)
        assertEquals(listOf("first", "second"), registry.sources.map { it.code })
        assertSame(second, registry.provider("second"))
        assertSame(first, registry.forBookUrl(first.bookUrl("12")))
        assertSame(second, registry.forBookUrl(second.bookUrl("12")))
        assertNull(registry.forBookUrl("https://unregistered.test/book/12"))
        assertNull(registry.forBookUrl("file:///book/12"))
    }

    @Test fun emptyAndAmbiguousRegistriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RemoteTextSourceRegistry(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { RemoteTextSourceRegistry(listOf(source("first"), source("first"))) }
        assertThrows(IllegalArgumentException::class.java) { RemoteTextSourceRegistry(listOf(source("../bad"))) }
        assertThrows(IllegalStateException::class.java) { RemoteTextSourceRegistry(listOf(source("first"))).provider("missing") }
    }

    @Test fun downloadFileNamesKeepChosenExtensionAndRemovePathCharacters() {
        val option = RemoteTextDownload("epub", "EPUB", "https://first.test/file", "epub")
        assertEquals("application/epub+zip", option.mimeType)
        assertEquals("text/plain", option.copy(extension = "txt").mimeType)
        assertEquals("Книга_ часть _1_.epub", option.fileName("Книга: часть /1?"))
        assertEquals("book.epub", option.fileName("..."))
        assertFalse(option.fileName("a\nb").contains('\n'))
        assertTrue(option.fileName("x".repeat(200)).length <= 125)
    }
}
