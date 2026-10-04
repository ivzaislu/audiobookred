package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksGenresTest {
    @Test
    fun homepageSidebarGenresAreExposedAsSourceGenreRefs() {
        val html = """
            <html><body>
              <div class="left-menu">
                <div>
                  <a href="https://my-audiobooks.com/fantastika-fentezi/" rel="5">Фантастика, фэнтези<span>19194 книг(и)</span></a>
                  <a href="https://my-audiobooks.com/psihologija-filosofija/" rel="5">Психология, философия<span>2013 книг(и)</span></a>
                  <a href="https://my-audiobooks.com/detektivy-i-trillery/" rel="5">Детективы и триллеры<span>149 книг(и)</span></a>
                  <a href="https://my-audiobooks.com/boevik/" rel="5">Боевик<span>2281 книг(и)</span></a>
                  <a href="https://my-audiobooks.com/litrpg/" rel="5">LitRPG<span>1608 книг(и)</span></a>
                  <a href="https://my-audiobooks.com/eve-online/" rel="5">EVE online<span>116 книг(и)</span></a>
                  <a href="https://my-audiobooks.com/ljubovnyj-roman/" rel="5">Любовный роман<span>7054 книг(и)</span></a>
                </div>
                <a href="/knigi-po-zhanram.html" class="lmall">все жанры</a>
              </div>
            </body></html>
        """.trimIndent()

        val genres = AbredMyAudiobooksHtmlParser.parseHomepageGenres(
            html,
            "https://my-audiobooks.com/",
        )

        assertEquals(
            listOf(
                "Фантастика, фэнтези",
                "Психология, философия",
                "Детективы и триллеры",
                "Боевик",
                "LitRPG",
                "EVE online",
                "Любовный роман",
            ),
            genres.map { it.name },
        )
        assertEquals(
            listOf(
                "myaudiobooks:genre:fantastika-fentezi",
                "myaudiobooks:genre:psihologija-filosofija",
                "myaudiobooks:genre:detektivy-i-trillery",
                "myaudiobooks:genre:boevik",
                "myaudiobooks:genre:litrpg",
                "myaudiobooks:genre:eve-online",
                "myaudiobooks:genre:ljubovnyj-roman",
            ),
            genres.map { it.id },
        )
        assertTrue(genres.none { it.name.contains("все жанры", ignoreCase = true) })
    }

    @Test
    fun observedGenreRefsBuildPagedCollectionUrls() {
        assertEquals(
            "https://my-audiobooks.com/litrpg/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("genre", "litrpg", 1),
        )
        assertEquals(
            "https://my-audiobooks.com/litrpg/page/2/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("genre", "litrpg", 2),
        )
    }
}
