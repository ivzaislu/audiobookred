package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AbredKnigavuheParserV2Test {
    @Test
    fun collectionsUsePathPagination() {
        assertEquals(
            "https://knigavuhe.org/author/teodor-drajjzer/",
            AbredKnigavuheParserV2.collectionPageUrl("author", "teodor-drajjzer", 1),
        )
        assertEquals(
            "https://knigavuhe.org/author/teodor-drajjzer/2/",
            AbredKnigavuheParserV2.collectionPageUrl("author", "teodor-drajjzer", 2),
        )
        assertEquals(
            "https://knigavuhe.org/reader/aleksandr-kuznecov/2/",
            AbredKnigavuheParserV2.collectionPageUrl("reader", "aleksandr-kuznecov", 2),
        )
        assertEquals(
            "https://knigavuhe.org/genre/fantastika/2/",
            AbredKnigavuheParserV2.collectionPageUrl("genre", "fantastika", 2),
        )
    }

    @Test
    fun logicalCollectionPagesMapAcrossTenItemSitePages() {
        assertEquals(
            PhysicalPageWindow(page = 1, skip = 5),
            AbredKnigavuheParserV2.collectionPageWindow(page = 2, limit = 5),
        )
        assertEquals(
            PhysicalPageWindow(page = 4, skip = 0),
            AbredKnigavuheParserV2.collectionPageWindow(page = 2, limit = 30),
        )
    }

    @Test
    fun searchUsesQueryPaginationAndSeriesSupportsBothAliases() {
        assertEquals(
            "https://knigavuhe.org/search/?q=%D0%BB%D1%83%D0%BA%D1%8C%D1%8F%D0%BD%D0%B5%D0%BD%D0%BA%D0%BE",
            AbredKnigavuheParserV2.searchPageUrl("лукьяненко", 1),
        )
        assertEquals(
            "https://knigavuhe.org/search/?page=2&q=%D0%BB%D1%83%D0%BA%D1%8C%D1%8F%D0%BD%D0%B5%D0%BD%D0%BA%D0%BE",
            AbredKnigavuheParserV2.searchPageUrl("лукьяненко", 2),
        )
        assertEquals(
            listOf(
                "https://knigavuhe.org/serie/pograniche/",
                "https://knigavuhe.org/series/pograniche/",
            ),
            AbredKnigavuheParserV2.seriesPageUrls("pograniche"),
        )
        assertEquals(
            "https://knigavuhe.org/serie/pograniche/",
            AbredKnigavuheParserV2.seriesPageUrl("pograniche"),
        )
    }

    @Test
    fun cycleDeclaredCountKeepsUnavailableBooksInTotal() {
        val html = """
            <html><body>
              <h1>Цикл «Пограничье» авторы Сергей Лукьяненко и другие, 10 книг</h1>
              <div class="bookkitem"><a class="bookkitem_name" href="/book/renegaty/">4. Ренегаты</a></div>
            </body></html>
        """.trimIndent()

        assertEquals(
            10,
            AbredKnigavuheParserV2.seriesDeclaredCount(
                html,
                "https://knigavuhe.org/serie/pograniche/",
            ),
        )
    }

    @Test
    fun fractionalCyclePositionsArePreservedAndRemovedFromTitle() {
        val html = """
            <html><body>
              <div class="bookkitem">
                <a class="bookkitem_name" href="/book/cena-svobody/">0.1. Цена свободы</a>
                <span class="bookkitem_serie_index">0.1.</span>
              </div>
              <div class="bookkitem">
                <a class="bookkitem_name" href="/book/pozhiratel-dush/">0.2. Пожиратель душ</a>
                <span class="bookkitem_serie_index">0,2.</span>
              </div>
            </body></html>
        """.trimIndent()

        val positions = AbredKnigavuheParserV2.seriesPositions(
            html,
            "https://knigavuhe.org/serie/veter-i-iskry/",
        )

        assertEquals(0.1, positions["cena-svobody"] ?: -1.0, 0.0001)
        assertEquals(0.2, positions["pozhiratel-dush"] ?: -1.0, 0.0001)
        assertEquals("Цена свободы", AbredKnigavuheParserV2.seriesEntryTitle("0.1. Цена свободы", 0.1))
        assertEquals("Книга", AbredKnigavuheParserV2.seriesEntryTitle("1. Книга", 1.0))
    }

    @Test
    fun commentedBookPlayerDoesNotShadowActivePlaylist() {
        val html = """
            <html><body><script>
              /*
              var player = new BookPlayer(123, [
                {"title":"старый","url":"https://s1.knigavuhe.org/2/0.mp3","duration":30}
              ], []);
              */
              var player = new BookPlayer(123, [
                {"title":"Глава 1","url":"https://s11.knigavuhe.org/1/audio/123/1.mp3","duration":777},
                {"title":"Глава 2","url":"https://s11.knigavuhe.org/1/audio/123/2.mp3","duration":1243}
              ], []);
            </script></body></html>
        """.trimIndent()

        val chapters = AbredKnigavuheHtmlParser.parsePlaylist(
            AbredKnigavuheParserV2.activePlayerHtml(html),
            "https://knigavuhe.org/book/test/",
            "knigavuhe:test",
        )

        assertEquals(2, chapters.size)
        assertEquals("https://s11.knigavuhe.org/1/audio/123/1.mp3", chapters.first().streamUrl)
        assertEquals(2_020L, chapters.sumOf { it.durationSeconds })
    }

    @Test
    fun trueCrimeStyleWrongTenMinuteMetadataUsesPlayerTimeline() {
        assertEquals(
            1_797L,
            AbredKnigavuheParserV2.resolvedBookDuration(
                declaredDurationSeconds = 600L,
                chapterDurationsSeconds = listOf(1_797L),
            ),
        )
        assertEquals(
            1_796L,
            AbredKnigavuheParserV2.resolvedBookDuration(
                declaredDurationSeconds = 1_796L,
                chapterDurationsSeconds = listOf(1_797L),
            ),
        )
    }

    @Test
    fun freeCardWithSeparateLitresLinkStaysInCatalog() {
        val html = """
            <html><body>
              <div class="bookkitem">
                <a class="bookkitem_cover" href="/book/free-book/"></a>
                <a class="bookkitem_name" href="/book/free-book/">Бесплатная книга</a>
                <a href="https://www.litres.ru/book/alternate/">Другая озвучка на ЛитРес</a>
                <span class="bookkitem_meta_time">1 час</span>
              </div>
            </body></html>
        """.trimIndent()

        val item = AbredKnigavuheHtmlParser.parseCatalog(
            html,
            "https://knigavuhe.org/new/",
        ).single()

        assertEquals("knigavuhe:free-book", item.key)
        assertEquals("Бесплатная книга", item.title)
    }

    @Test
    fun singleFullLengthTrackIsNotPreview() {
        val chapters = listOf(
            com.example.data.model.ChapterDto(
                id = "c1",
                position = 0,
                title = "Полная книга",
                durationSeconds = 3_590L,
                streamUrl = "https://s11.knigavuhe.org/1/audio/123/1.mp3",
            )
        )

        assertFalse(AbredKnigavuheHtmlParser.isLikelyPreview(3_600L, chapters))
    }
}
