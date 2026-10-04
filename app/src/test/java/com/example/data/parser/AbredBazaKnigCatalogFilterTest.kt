package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredBazaKnigCatalogFilterTest {
    @Test
    fun catalogFiltersBiblioShopCardsBeforeDetailRequests() {
        val html = """
            <html><body>
              <article class="abook-item">
                <a class="image-abook" href="/audio-111795-dozor-04-poslednij-dozor-sergej-lukjanenko"></a>
                <h2 class="abook-title">
                  <a class="book-title" href="/audio-111795-dozor-04-poslednij-dozor-sergej-lukjanenko">
                    Последний дозор - Сергей Лукьяненко
                  </a>
                </h2>
              </article>

              <article class="abook-item">
                <a class="image-abook" href="/audio-26718-kvazi"></a>
                <h2 class="abook-title">
                  <svg class="biblio-icon-small"><use href="#biblio-icon-small"></use></svg>
                  <a class="book-title" href="/audio-26718-kvazi">КВАZИ</a>
                </h2>
              </article>
            </body></html>
        """.trimIndent()

        val rows = AbredBazaKnigHtmlParser.parseCatalog(
            html,
            "https://baza-knig.info/avtor-270-lukyanenko-sergei",
        )

        assertEquals(1, rows.size)
        assertEquals("111795-dozor-04-poslednij-dozor-sergej-lukjanenko", rows.single().externalId)
        assertEquals("Последний дозор - Сергей Лукьяненко", rows.single().title)
    }

    @Test
    fun richSearchDoesNotReAddShopCardsThroughLightweightFallback() {
        val html = """
            <html><body>
              <article class="abook-item">
                <a class="image-abook" href="/audio-26718-kvazi"></a>
                <h2 class="abook-title">
                  <svg class="biblio-icon-small"><use href="#biblio-icon-small"></use></svg>
                  <a class="book-title" href="/audio-26718-kvazi">КВАZИ</a>
                </h2>
              </article>
            </body></html>
        """.trimIndent()

        val rows = AbredBazaKnigHtmlParser.parseSearch(
            html,
            "https://baza-knig.info/search?text=квази",
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun collectionPagingDetectsRealNextPageAfterShopCardsAreFiltered() {
        val withNext = """
            <html>
              <head><link rel="next" href="?page=2"></head>
              <body>
                <article class="abook-item">
                  <h2><svg class="biblio-icon-small"></svg><a class="book-title" href="/audio-1-paid">Платная</a></h2>
                </article>
              </body>
            </html>
        """.trimIndent()
        val lastPage = """
            <html><body>
              <ul class="pagination"><li class="next disabled"><span>»</span></li></ul>
            </body></html>
        """.trimIndent()

        assertTrue(AbredBazaKnigHtmlParser.hasNextCatalogPage(withNext, "https://baza-knig.info/author"))
        assertFalse(AbredBazaKnigHtmlParser.hasNextCatalogPage(lastPage, "https://baza-knig.info/author?page=2"))
    }
}
