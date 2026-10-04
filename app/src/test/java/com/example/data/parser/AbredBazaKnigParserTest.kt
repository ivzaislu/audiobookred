package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredBazaKnigParserTest {
    @Test
    fun catalogParsesRichBazaKnigCard() {
        val html = """
            <html><body>
              <article class="abook-item">
                <a class="image-abook" href="/audio-123094-krov-vasiliska-tom-10-tajnikovskij">
                  <img class="b-showshort__cover_image"
                       src="https://cdn.redirectto.cc/s01/1/2/3/0/9/4/cover.jpg">
                </a>
                <header class="abook-item-header">
                  <h2 class="abook-title">
                    <a class="book-title" href="/audio-123094-krov-vasiliska-tom-10-tajnikovskij">
                      Кровь Василиска. Том 10 - Тайниковский
                    </a>
                    <a class="author-title" href="/avtor-8802-tainikovskii">Тайниковский</a>
                  </h2>
                  <div class="abook-genre">
                    <a href="/genre-3-fantastika-fentezi">Фантастика, фэнтези</a>
                    <a href="/genre-19-popadancy">Попаданцы</a>
                  </div>
                </header>
                <div class="abook-content">
                  Описание книги
                  <div class="content-abook-info">
                    <div class="a-info-item">Читает
                      <a href="/ispolnitel-124-keinz-oleg" rel="performer">Кейнз Олег</a>
                    </div>
                    <div class="a-info-item">Серия
                      <a href="/series-8656-krov-vasiliska" rel="series">Кровь Василиска</a> (10)
                    </div>
                    <div class="a-info-item">07:01:09</div>
                  </div>
                </div>
              </article>
            </body></html>
        """.trimIndent()

        val row = AbredBazaKnigHtmlParser.parseCatalog(html, "https://baza-knig.info/").single()

        assertEquals("bazaknig:123094-krov-vasiliska-tom-10-tajnikovskij", row.key)
        assertEquals("123094-krov-vasiliska-tom-10-tajnikovskij", row.externalId)
        assertEquals("Кровь Василиска. Том 10 - Тайниковский", row.title)
        assertEquals(listOf("Тайниковский"), row.authors)
        assertEquals(listOf("Кейнз Олег"), row.narrators)
        assertEquals(listOf("Фантастика, фэнтези", "Попаданцы"), row.genres)
        assertEquals("8656-krov-vasiliska", row.seriesExternalId)
        assertEquals("Кровь Василиска", row.seriesName)
        assertEquals(10, row.seriesPosition)
        assertEquals(7 * 3600L + 60L + 9L, row.durationSeconds)
    }

    @Test
    fun freeDetailExtractsPlaylistAndSourceQualifiedMetadata() {
        val html = """
            <html><head>
              <meta property="og:image"
                    content="https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/cover.jpg">
            </head><body>
              <div class="book_genre_pretitle">
                <a href="/genre-3-fantastika-fentezi">Фантастика, фэнтези</a>
                <a href="/genre-19-popadancy">Попаданцы</a>
              </div>
              <h1>
                <span class="book_title_elem book_title_name">Кровь Василиска. Том 10 - Тайниковский</span>
                <span class="book_title_elem">автор
                  <a href="/avtor-8802-tainikovskii">Тайниковский</a>
                </span>
                <span class="book_title_elem">читает
                  <a href="/ispolnitel-124-keinz-oleg">Кейнз Олег</a>
                </span>
              </h1>
              <script>
                var player = new Playerjs({id: "player",
                  file: "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/123094.pl.txt"});
              </script>
              <div class="book_blue_block">Время звучания: 07:01:09</div>
              <div class="book_description">
                <span class="book_series">Серия:
                  <a href="/series-8656-krov-vasiliska">Кровь Василиска</a> (10)
                </span>
                Прошлая жизнь. Тестовое описание.
              </div>
            </body></html>
        """.trimIndent()
        val pageUrl = "https://baza-knig.info/audio-123094-krov-vasiliska-tom-10-tajnikovskij"
        val bookId = "bazaknig:123094-krov-vasiliska-tom-10-tajnikovskij"

        val metadata = AbredBazaKnigHtmlParser.parseMetadata(html, pageUrl, bookId)

        assertFalse(AbredBazaKnigHtmlParser.isPreviewPage(html, pageUrl))
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/123094.pl.txt",
            AbredBazaKnigHtmlParser.parsePlaylistUrl(html, pageUrl),
        )
        assertEquals("bazaknig:author:8802-tainikovskii", metadata.authors.single().id)
        assertEquals("bazaknig:narrator:124-keinz-oleg", metadata.narrators.single().id)
        assertEquals("bazaknig:genre:3-fantastika-fentezi", metadata.genres.first().id)
        assertEquals("source:bazaknig:8656-krov-vasiliska", metadata.audioSeries.single().id)
        assertEquals(10, metadata.seriesPosition)
        assertEquals("bazaknig", metadata.selectedSource)
        assertEquals("live:bazaknig:123094-krov-vasiliska-tom-10-tajnikovskij", metadata.selectedBookSourceId)
        assertEquals("Прошлая жизнь. Тестовое описание.", metadata.description)
    }

    @Test
    fun playlistJsonBecomesDirectDownloadCompatibleChapters() {
        val playlist = """
            [
              {"title":"1","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/0.mp3"},
              {"title":"2","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/1.mp3"}
            ]
        """.trimIndent()
        val bookId = "bazaknig:123094-krov-vasiliska-tom-10-tajnikovskij"

        val chapters = AbredBazaKnigHtmlParser.parsePlaylist(playlist, bookId)

        assertEquals(2, chapters.size)
        assertEquals("1", chapters[0].title)
        assertEquals(0, chapters[0].position)
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/0.mp3",
            chapters[0].streamUrl,
        )
        assertEquals("$bookId:chapter:1", chapters[0].id)
    }

    @Test
    fun paidShopPageIsPreviewAndNeverInventsFullPlaylist() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://pub-cdn.bibliovk.ru/images/books/4596/cover.jpg">
            </head><body>
              <article class="article biblio abook-page"
                       data-target-id="26550" data-bid="26550" data-shopid="4596">
                <div class="book_genre_pretitle">
                  <a href="/genre-3-fantastika-fentezi">Фантастика, фэнтези</a>
                </div>
                <h1>
                  <span class="book_title_elem book_title_name">Не время для драконов</span>
                  <span class="book_title_elem">автор
                    <a href="/avtor-8176-perumov-nik-lukyanenko-sergei">Перумов Ник, Лукьяненко Сергей</a>
                  </span>
                  <span class="book_title_elem">читает
                    <a href="/ispolnitel-147-lazarev-yurii">Лазарев Юрий</a>
                  </span>
                </h1>
                <div class="book_blue_block">Время звучания: 16 ч. 31 м.</div>
                <div class="player--buttons player--buttons-onefile"></div>
                <div class="bookpage--chapters player--chapters">
                  <div class="chapter__default--title">Фрагмент</div>
                  <div class="shop--buttons">
                    <a class="shop--button shop--button-buy">Купить за 249 ₽</a>
                  </div>
                </div>
              </article>
            </body></html>
        """.trimIndent()
        val pageUrl = "https://baza-knig.info/audio-26550-ne-vremya-dlya-drakonov"
        val bookId = "bazaknig:26550-ne-vremya-dlya-drakonov"

        val metadata = AbredBazaKnigHtmlParser.parseMetadata(html, pageUrl, bookId)

        assertTrue(AbredBazaKnigHtmlParser.isPreviewPage(html, pageUrl))
        assertNull(AbredBazaKnigHtmlParser.parsePlaylistUrl(html, pageUrl))
        assertEquals("Не время для драконов", metadata.title)
        assertEquals("bazaknig", metadata.selectedSource)
        assertEquals(16 * 3600L + 31 * 60L, metadata.durationSeconds)
    }

    @Test
    fun searchParsesLightweightAudioLinksWithoutDetailRequests() {
        val html = """
            <html><body>
              <div class="b-statictop-search">
                <ul class="b-statictop__items">
                  <li class="b-statictop__items_item">
                    <div class="cell title">
                      <a href="/audio-37709-trinadcatyj-gorod-sergej-lukjanenko">
                        Тринадцатый город - Сергей Лукьяненко
                      </a>
                    </div>
                  </li>
                  <li class="b-statictop__items_item">
                    <div class="cell title">
                      <a href="/audio-37709-trinadcatyj-gorod-sergej-lukjanenko">
                        Тринадцатый город - Сергей Лукьяненко
                      </a>
                    </div>
                  </li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()

        val rows = AbredBazaKnigHtmlParser.parseSearch(
            html,
            "https://baza-knig.info/search?text=лукьяненко",
        )
        val enriched = BazaKnigSearchMetadata.enrich(rows)

        assertEquals(1, rows.size)
        assertEquals("bazaknig:37709-trinadcatyj-gorod-sergej-lukjanenko", rows.single().key)
        assertEquals(listOf("Сергей Лукьяненко"), enriched.single().authors)
        assertTrue(enriched.single().coverUrl.isBlank())
    }

    @Test
    fun sourceUrlsAndEntitiesFollowObservedBazaKnigRoutes() {
        assertEquals("https://baza-knig.info/", AbredBazaKnigHtmlParser.catalogPageUrl(1))
        assertEquals("https://baza-knig.info/?page=2", AbredBazaKnigHtmlParser.catalogPageUrl(2))
        assertTrue(AbredBazaKnigHtmlParser.searchUrl("Сергей Лукьяненко").startsWith("https://baza-knig.info/search?text="))
        assertEquals(
            "https://baza-knig.info/avtor-8802-tainikovskii",
            AbredBazaKnigHtmlParser.collectionPageUrl("author", "8802-tainikovskii", 1),
        )
        assertEquals(
            "https://baza-knig.info/ispolnitel-124-keinz-oleg?page=2",
            AbredBazaKnigHtmlParser.collectionPageUrl("narrator", "124-keinz-oleg", 2),
        )
        assertEquals(
            "https://baza-knig.info/series-8656-krov-vasiliska",
            AbredBazaKnigHtmlParser.collectionPageUrl("series", "8656-krov-vasiliska", 1),
        )
        assertEquals(
            "8802-tainikovskii",
            AbredBazaKnigHtmlParser.parseEntityRef("bazaknig:author:8802-tainikovskii", "author"),
        )
        assertTrue(
            AbredBazaKnigHtmlParser.isAllowedMediaHost(
                "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/0/9/4/0.mp3"
            )
        )
        assertFalse(AbredBazaKnigHtmlParser.isAllowedMediaHost("https://example.com/0.mp3"))
    }
}
