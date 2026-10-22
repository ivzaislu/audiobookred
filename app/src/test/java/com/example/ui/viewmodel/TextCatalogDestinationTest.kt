package com.example.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

class TextCatalogDestinationTest {
    @Test fun searchAndBookNavigationSurviveSerialization() {
        val search = TextCatalogDestination("fantasy_worlds", "https://fantasy-worlds.org/search/?q=test", query = "Книга & цикл")
        val book = TextCatalogDestination("fantasy_worlds", "https://fantasy-worlds.org/lib/id1465/", "1465")
        assertEquals(search, TextCatalogDestination.decode(search.encode()))
        assertEquals(book, TextCatalogDestination.decode(book.encode()))
        assertNotEquals(search.key, book.key)
    }
    @Test fun malformedNavigationIsIgnored() {
        assertNull(TextCatalogDestination.decode("broken"))
        assertNull(TextCatalogDestination.decode("{}"))
    }
}
