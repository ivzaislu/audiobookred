package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBookKeyTest {
    @Test
    fun providerBookIdsFollowCatalogContracts() {
        assertEquals("audiopolka" to "7520884", parseLiveBookKey("audiopolka:7520884"))
        assertEquals("uknig" to "815724", parseLiveBookKey("uknig:815724"))
        assertEquals(
            "audioboo" to "mistic/119295-konnolli-dzhon.html",
            parseLiveBookKey("audioboo:mistic/119295-konnolli-dzhon.html"),
        )
        assertEquals("knigavuhe" to "normal-book", parseLiveBookKey("knigavuhe:normal-book"))
        assertEquals("bazaknig" to "book_slug-1", parseLiveBookKey("bazaknig:book_slug-1"))
        assertEquals(
            "myaudiobooks" to "fantastika/123458-master-trav-iv.html",
            parseLiveBookKey("myaudiobooks:fantastika/123458-master-trav-iv.html"),
        )
        assertEquals("rutracker" to "6862086", parseLiveBookKey("rutracker:6862086"))
    }

    @Test
    fun sourceSeriesCapabilityFollowsRegistryContract() {
        assertTrue(sourceSeriesCapabilityAllows("bazaknig:book_slug-1"))
        assertTrue(
            sourceSeriesCapabilityAllows(
                "myaudiobooks:detective/64061-audiokniga-smert-tam-eshhe-ne-pobyvala-reks-staut.html"
            )
        )
        assertFalse(sourceSeriesCapabilityAllows("myaudiobooks:bad-id"))
    }

    @Test
    fun malformedKnownProviderBookIdsAreRejectedBeforeUrlConstruction() {
        listOf(
            "audiopolka:not-a-number",
            "uknig:../search",
            "uknig:815724?x=1",
            "audioboo:119295-book.html",
            "audioboo:mistic/../119295-book.html",
            "audioboo:mistic/119295-book.html?x=1",
            "knigavuhe:../paid",
            "knigavuhe:book/extra",
            "knigavuhe:book?x=1",
            "bazaknig:../book",
            "bazaknig:book/extra",
            "myaudiobooks:123458-book.html",
            "myaudiobooks:fantastika/../123458-book.html",
            "myaudiobooks:fantastika/123458-book.html?x=1",
            "myaudiobooks:fantastika/123458-book.html%2fextra",
            "rutracker:not-a-number",
            "rutracker:6862086?x=1",
        ).forEach { value ->
            assertNull(value, parseLiveBookKey(value))
        }
    }
}
