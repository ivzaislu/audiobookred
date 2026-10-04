package com.example.ui.paging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookPageRequestLiveParserTest {
    @Test
    fun bazaEntityCollectionsUseLiveParserPrefetchPolicy() {
        assertTrue(BookPageRequest.Browse("Author", "bazaknig:author:ivan-ivanov").isLiveParserRead)
        assertTrue(BookPageRequest.Browse("Narrator", "bazaknig:narrator:petr-petrov").isLiveParserRead)
        assertTrue(BookPageRequest.Browse("Genre", "bazaknig:genre:true-crime").isLiveParserRead)
    }

    @Test
    fun unrelatedBrowseRouteDoesNotBecomeLiveParserRead() {
        assertFalse(BookPageRequest.Browse("Author", "legacy:author:42").isLiveParserRead)
    }
}
