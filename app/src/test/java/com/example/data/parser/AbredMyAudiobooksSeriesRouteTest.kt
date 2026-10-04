package com.example.data.parser

import com.example.data.source.StandaloneSourceRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksSeriesRouteTest {
    @Test
    fun detailSeriesPreservesCanonicalHrefToken() {
        val pageUrl = "https://my-audiobooks.com/litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"
        val externalPath = "litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"
        val bookId = "myaudiobooks:$externalPath"
        val html = """
            <html><body>
              <div class="full-news">
                <div class="full-news-title"><h1>Мастер Трав IV - Мордорский Ваня</h1></div>
                <div class="full-news-stats-col"><div class="fnsc-left">
                  <div><i>Серия:</i><a href="https://my-audiobooks.com/xfsearch/series/%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80%20%D1%82%D1%80%D0%B0%D0%B2/">Мастер Трав</a></div>
                </div></div>
              </div>
            </body></html>
        """.trimIndent()

        val detail = AbredMyAudiobooksHtmlParser.parseMetadata(html, pageUrl, bookId, externalPath)
        val token = detail.audioSeries.single().externalId

        assertEquals(
            "%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80%20%D1%82%D1%80%D0%B0%D0%B2",
            token,
        )
        assertEquals(
            "https://my-audiobooks.com/xfsearch/series/%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80%20%D1%82%D1%80%D0%B0%D0%B2/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("series", token, 1),
        )
        assertEquals(
            "https://my-audiobooks.com/xfsearch/series/%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80%20%D1%82%D1%80%D0%B0%D0%B2/page/2/",
            AbredMyAudiobooksHtmlParser.collectionPageUrl("series", token, 2),
        )
    }

    @Test
    fun visibleSeriesNameIsFallbackWhenHrefCannotProvideSeriesToken() {
        val pageUrl = "https://my-audiobooks.com/book/1.html"
        val html = """
            <html><body>
              <div class="full-news">
                <div class="full-news-title"><h1>Тест</h1></div>
                <div class="full-news-stats-col"><div class="fnsc-left">
                  <div><i>Серия:</i><a href="https://my-audiobooks.com/broken-series-link/">«Табуретная» кавалерия</a></div>
                </div></div>
              </div>
            </body></html>
        """.trimIndent()

        val detail = AbredMyAudiobooksHtmlParser.parseMetadata(
            html,
            pageUrl,
            "myaudiobooks:test/1.html",
            "test/1.html",
        )

        assertEquals(
            "%C2%AB%D0%A2%D0%B0%D0%B1%D1%83%D1%80%D0%B5%D1%82%D0%BD%D0%B0%D1%8F%C2%BB%20%D0%BA%D0%B0%D0%B2%D0%B0%D0%BB%D0%B5%D1%80%D0%B8%D1%8F",
            detail.audioSeries.single().externalId,
        )
    }

    @Test
    fun provider404IsRecognizedOnlyForMyAudiobooksHttp404() {
        assertTrue(
            isMyAudiobooksSeriesHttp404(
                IOException("MY-AUDIOBOOKS HTTP 404 for https://my-audiobooks.com/xfsearch/series/test/")
            )
        )
        assertFalse(
            isMyAudiobooksSeriesHttp404(
                IOException("MY-AUDIOBOOKS HTTP 500 for https://my-audiobooks.com/xfsearch/series/test/")
            )
        )
        assertFalse(isMyAudiobooksSeriesHttp404(IllegalStateException("HTTP 404")))
    }

    @Test
    fun sourceSeriesCapabilityIsEnabledForObservedRouteContract() {
        assertTrue(StandaloneSourceRegistry.supportsSeries("myaudiobooks"))
    }
}
