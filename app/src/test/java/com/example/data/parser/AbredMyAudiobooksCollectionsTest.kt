package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksCollectionsTest {
    @Test
    fun genreCollectionUsesObservedDlePagingContract() {
        val html = """
            <html><body>
              <div class="navigation">
                <div class="navigation-center">
                  <span>1</span>
                  <a href="https://my-audiobooks.com/litrpg/page/2/">2</a>
                  <a href="https://my-audiobooks.com/litrpg/page/3/">3</a>
                </div>
                <div class="nav-load" style="display:none">
                  <a href="https://my-audiobooks.com/litrpg/page/2/"></a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        assertTrue(
            AbredMyAudiobooksHtmlParser.hasNextPage(
                html,
                "https://my-audiobooks.com/litrpg/",
            ),
        )
        assertEquals(
            "https://my-audiobooks.com/litrpg/page/2/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("genre", "litrpg", 2),
        )
    }

    @Test
    fun lastGenrePageDoesNotInventAnotherPage() {
        val html = """
            <html><body>
              <div class="navigation">
                <div class="navigation-center">
                  <a href="https://my-audiobooks.com/litrpg/page/104/">104</a>
                  <span>105</span>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        assertFalse(
            AbredMyAudiobooksHtmlParser.hasNextPage(
                html,
                "https://my-audiobooks.com/litrpg/page/105/",
            ),
        )
    }

    @Test
    fun authorRefFromDetailBuildsSourceCollectionUrl() {
        val ref = "myaudiobooks:author:%D0%BB%D1%83%D0%BA%D1%8C%D1%8F%D0%BD%D0%B5%D0%BD%D0%BA%D0%BE%20%D1%81%D0%B5%D1%80%D0%B3%D0%B5%D0%B9"
        val token = AbredMyAudiobooksHtmlParser.parseEntityRef(ref, "author")

        assertEquals(
            "%D0%BB%D1%83%D0%BA%D1%8C%D1%8F%D0%BD%D0%B5%D0%BD%D0%BA%D0%BE%20%D1%81%D0%B5%D1%80%D0%B3%D0%B5%D0%B9",
            token,
        )
        assertEquals(
            "https://my-audiobooks.com/tags/$token/page/2/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("author", token!!, 2),
        )
    }

    @Test
    fun narratorRefFromDetailBuildsSourceCollectionUrl() {
        val ref = "myaudiobooks:narrator:%D0%BF%D0%B5%D1%82%D1%80%D0%BE%D0%B2%20%D0%BA%D0%B8%D1%80%D0%B8%D0%BB%D0%BB"
        val token = AbredMyAudiobooksHtmlParser.parseEntityRef(ref, "narrator")

        assertEquals(
            "%D0%BF%D0%B5%D1%82%D1%80%D0%BE%D0%B2%20%D0%BA%D0%B8%D1%80%D0%B8%D0%BB%D0%BB",
            token,
        )
        assertEquals(
            "https://my-audiobooks.com/xfsearch/chtec/$token/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("narrator", token!!, 1),
        )
    }
}
