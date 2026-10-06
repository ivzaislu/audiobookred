package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksParserTest {
    @Test
    fun homepageCardAndPagingFollowObservedDleMarkup() {
        val html = """
            <html><body>
              <div id="dle-content">
                <div class="main-news ajax-news">
                  <div class="main-news-l">
                    <a href="https://my-audiobooks.com/litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"
                       class="main-news-image">
                      <img data-src="/uploads/posts/books/75037/75037.jpg"
                           alt="Мастер Трав IV - Мордорский Ваня">
                    </a>
                  </div>
                  <div class="main-news-r">
                    <div class="main-news-title">
                      <a href="https://my-audiobooks.com/litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html">
                        Мастер Трав IV - Мордорский Ваня
                      </a>
                    </div>
                    <div class="main-news-c">
                      <a href="https://my-audiobooks.com/litrpg/">LitRPG</a>
                    </div>
                    <div class="fnsc-left">
                      <div><i>Автор:</i><a href="https://my-audiobooks.com/tags/-/">-</a></div>
                      <div><i>Серия:</i><a href="https://my-audiobooks.com/xfsearch/series/%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80%20%D1%82%D1%80%D0%B0%D0%B2/">Мастер Трав</a></div>
                      <div><i>Исполнитель:</i><a href="https://my-audiobooks.com/xfsearch/chtec/%D1%87%D0%B5%D1%80%D1%81%D0%BA%D0%BE%D0%B2%20%D1%81%D1%82%D0%B0%D0%BD%D0%B8%D1%81%D0%BB%D0%B0%D0%B2/">Черсков Станислав</a></div>
                    </div>
                    <div class="main-news-play"><i>Слушать онлайн (11:01:11)</i></div>
                  </div>
                </div>
              </div>
              <div class="navigation">
                <div class="navigation-center">
                  <span>1</span><a href="https://my-audiobooks.com/page/2/">2</a>
                </div>
                <div class="nav-load" style="display:none">
                  <a href="https://my-audiobooks.com/page/2/"></a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val row = AbredMyAudiobooksHtmlParser.parseCatalog(html, "https://my-audiobooks.com/").single()

        assertEquals("myaudiobooks:litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html", row.key)
        assertEquals("litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html", row.externalId)
        assertEquals("Мастер Трав IV - Мордорский Ваня", row.title)
        assertTrue(row.authors.isEmpty())
        assertEquals(listOf("Черсков Станислав"), row.narrators)
        assertEquals(listOf("LitRPG"), row.genres)
        assertEquals("Мастер Трав", row.seriesName)
        assertTrue(row.seriesExternalId.isNotBlank())
        assertEquals(4, row.seriesPosition)
        assertEquals(11 * 3600L + 60L + 11L, row.durationSeconds)
        assertEquals("https://my-audiobooks.com/uploads/posts/books/75037/75037.jpg", row.coverUrl)
        assertTrue(AbredMyAudiobooksHtmlParser.hasNextPage(html, "https://my-audiobooks.com/"))
        assertEquals("https://my-audiobooks.com/", AbredMyAudiobooksHtmlParser.catalogPageUrl(1))
        assertEquals("https://my-audiobooks.com/page/2/", AbredMyAudiobooksHtmlParser.catalogPageUrl(2))
    }

    @Test
    fun detailWithSeriesPreservesObservedMetadataAndPlaylistUrl() {
        val pageUrl = "https://my-audiobooks.com/detektivy-trillery/64061-audiokniga-smert-tam-esche-ne-pobyvala-reks-staut.html"
        val externalPath = "detektivy-trillery/64061-audiokniga-smert-tam-esche-ne-pobyvala-reks-staut.html"
        val bookId = "myaudiobooks:$externalPath"
        val html = """
            <html><head>
              <meta property="og:image" content="https://my-audiobooks.com/uploads/posts/books/64061/64061.jpg">
            </head><body>
              <div class="full-news" rel="64061">
                <div class="full-news-tb">
                  <div class="full-news-title"><h1>Смерть там ещё не побывала - Рекс Стаут</h1></div>
                  <div class="main-news-c">
                    <a href="https://my-audiobooks.com/detektivy-trillery/">Детективы, триллеры</a>
                  </div>
                </div>
                <div class="full-news-cols">
                  <div class="full-news-left">
                    <div class="full-news-image"><img data-src="/uploads/posts/books/64061/64061.jpg"></div>
                  </div>
                  <div class="full-news-right">
                    <div class="full-news-stats-col"><div class="fnsc-left">
                      <div><i>Автор:</i><span class="gjty"><a href="https://my-audiobooks.com/tags/%D1%81%D1%82%D0%B0%D1%83%D1%82%20%D1%80%D0%B5%D0%BA%D1%81/">Стаут Рекс</a></span></div>
                      <div><i>Исполнитель:</i><a href="https://my-audiobooks.com/xfsearch/chtec/%D1%8F%D0%BA%D0%BE%D0%B2%D0%BB%D0%B5%D0%B2-%D1%81%D1%83%D1%85%D0%B0%D0%BD%D0%BE%D0%B2%20%D1%8E%D1%80%D0%B8%D0%B9/">Яковлев-Суханов Юрий</a></div>
                      <div><i>Серия:</i><a href="https://my-audiobooks.com/xfsearch/series/%D0%BD%D0%B8%D1%80%D0%BE%20%D0%B2%D1%83%D0%BB%D1%8C%D1%84/">Ниро Вульф</a></div>
                      <div><i>Время:</i>02:59:32</div>
                      <div><i>Добавлено:</i>13-10-2025, 18:04</div>
                    </div></div>
                    <div class="full-news-text mjjr">
                      Во время Второй Мировой войны, майор Арчи Гудвин остается за пределами дома Ниро Вульфа.
                      <p class="age-restrictions">Возрастные ограничения: 18+</p>
                    </div>
                  </div>
                </div>
              </div>
              <div class="gnth">
                <script>var playerjs1 = new Playerjs({id:"playerjs1",file:"https://9giiu0g54k8c.redirectto.cc/s01/1/0/7/4/9/9/107499.pl.txt"});</script>
              </div>
            </body></html>
        """.trimIndent()

        val metadata = AbredMyAudiobooksHtmlParser.parseMetadata(html, pageUrl, bookId, externalPath)

        assertFalse(AbredMyAudiobooksHtmlParser.isUnavailablePage(html, pageUrl))
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/0/7/4/9/9/107499.pl.txt",
            AbredMyAudiobooksHtmlParser.parsePlaylistUrl(html, pageUrl),
        )
        assertEquals("Смерть там ещё не побывала - Рекс Стаут", metadata.title)
        assertEquals("Стаут Рекс", metadata.authors.single().name)
        assertTrue(metadata.authors.single().id.startsWith("myaudiobooks:author:"))
        assertEquals("Яковлев-Суханов Юрий", metadata.narrators.single().name)
        assertTrue(metadata.narrators.single().id.startsWith("myaudiobooks:narrator:"))
        assertEquals(listOf("Детективы, триллеры"), metadata.genres.map { it.name })
        assertEquals("Ниро Вульф", metadata.seriesName)
        assertNull(metadata.seriesPosition)
        assertEquals("Ниро Вульф", metadata.sourceSeriesName)
        assertEquals("Ниро Вульф", metadata.audioSeries.single().name)
        assertEquals("myaudiobooks", metadata.audioSeries.single().provider)
        assertTrue(metadata.audioSeries.single().externalId.isNotBlank())
        assertEquals(2 * 3600L + 59 * 60L + 32L, metadata.durationSeconds)
        assertEquals("https://my-audiobooks.com/uploads/posts/books/64061/64061.jpg", metadata.coverUrl)
        assertEquals("myaudiobooks", metadata.selectedSource)
        assertEquals("live:myaudiobooks:$externalPath", metadata.selectedBookSourceId)
        assertTrue(metadata.description.contains("Во время Второй Мировой войны"))
        assertFalse(metadata.description.contains("Возрастные ограничения"))
    }

    @Test
    fun detailWithoutSeriesKeepsSeriesFieldsEmpty() {
        val pageUrl = "https://my-audiobooks.com/fantastika-fentezi/72416-audiokniga-rasskazy-s-predislovijami-avtora-lukjanenko-sergej.html"
        val externalPath = "fantastika-fentezi/72416-audiokniga-rasskazy-s-predislovijami-avtora-lukjanenko-sergej.html"
        val bookId = "myaudiobooks:$externalPath"
        val html = """
            <html><body>
              <div class="full-news" rel="72416">
                <div class="full-news-tb">
                  <div class="full-news-title"><h1>Рассказы с предисловиями Автора - Лукьяненко Сергей</h1></div>
                  <div class="main-news-c"><a href="https://my-audiobooks.com/fantastika-fentezi/">Фантастика, фэнтези</a></div>
                </div>
                <div class="full-news-cols">
                  <div class="full-news-left"><div class="full-news-image"><img data-src="/uploads/posts/books/72416/72416.jpg"></div></div>
                  <div class="full-news-right">
                    <div class="full-news-stats-col"><div class="fnsc-left">
                      <div><i>Автор:</i><span class="gjty"><a href="https://my-audiobooks.com/tags/%D0%BB%D1%83%D0%BA%D1%8C%D1%8F%D0%BD%D0%B5%D0%BD%D0%BA%D0%BE%20%D1%81%D0%B5%D1%80%D0%B3%D0%B5%D0%B9/">Лукьяненко Сергей</a></span></div>
                      <div><i>Исполнитель:</i><a href="https://my-audiobooks.com/xfsearch/chtec/%D0%BF%D0%B5%D1%82%D1%80%D0%BE%D0%B2%20%D0%BA%D0%B8%D1%80%D0%B8%D0%BB%D0%BB/">Петров Кирилл</a></div>
                      <div><i>Время:</i>08:53:02</div>
                      <div><i>Добавлено:</i>3-07-2026, 15:02</div>
                    </div></div>
                    <div class="full-news-text mjjr">
                      Открывать для себя новую книгу всегда увлекательно. Каждый рассказ предваряет предисловие, прочитанное самим автором.
                      <p class="age-restrictions">Возрастные ограничения: 18+</p>
                    </div>
                  </div>
                </div>
              </div>
              <div class="gnth">
                <script>var playerjs1 = new Playerjs({id:"playerjs1",file:"https://9giiu0g54k8c.redirectto.cc/s01/1/1/8/3/6/8/118368.pl.txt"});</script>
              </div>
            </body></html>
        """.trimIndent()

        val metadata = AbredMyAudiobooksHtmlParser.parseMetadata(html, pageUrl, bookId, externalPath)

        assertEquals("Рассказы с предисловиями Автора - Лукьяненко Сергей", metadata.title)
        assertEquals("Лукьяненко Сергей", metadata.authors.single().name)
        assertEquals("Петров Кирилл", metadata.narrators.single().name)
        assertEquals(8 * 3600L + 53 * 60L + 2L, metadata.durationSeconds)
        assertTrue(metadata.seriesName.isBlank())
        assertNull(metadata.seriesPosition)
        assertTrue(metadata.sourceSeriesName.isBlank())
        assertNull(metadata.sourceSeriesPosition)
        assertTrue(metadata.audioSeries.isEmpty())
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/1/8/3/6/8/118368.pl.txt",
            AbredMyAudiobooksHtmlParser.parsePlaylistUrl(html, pageUrl),
        )
        assertFalse(metadata.description.contains("Возрастные ограничения"))
    }

    @Test
    fun blockedDetailPreservesMetadataButHasNoPlaylist() {
        val pageUrl = "https://my-audiobooks.com/fantastika-fentezi/66591-audiokniga-zvezdnaja-krov-izgoj-ix-aleksej-eliseev.html"
        val externalPath = "fantastika-fentezi/66591-audiokniga-zvezdnaja-krov-izgoj-ix-aleksej-eliseev.html"
        val bookId = "myaudiobooks:$externalPath"
        val html = """
            <html><body>
              <div class="full-news" rel="66591">
                <div class="full-news-tb">
                  <div class="full-news-title"><h1>Звёздная Кровь. Изгой IX - Алексей Елисеев</h1></div>
                  <div class="main-news-c">
                    <a href="https://my-audiobooks.com/fantastika-fentezi/">Фантастика, фэнтези</a>
                    <a href="https://my-audiobooks.com/popadancy/">Попаданцы</a>
                  </div>
                </div>
                <div class="full-news-cols">
                  <div class="full-news-left"><div class="full-news-image"><img data-src="/uploads/posts/books/66591/66591.jpg"></div></div>
                  <div class="full-news-right">
                    <div class="full-news-stats-col"><div class="fnsc-left">
                      <div><i>Доступ:</i><span>Книга заблокирована</span></div>
                      <div><i>Автор:</i><span class="gjty"><a href="https://my-audiobooks.com/tags/%D0%B5%D0%BB%D0%B8%D1%81%D0%B5%D0%B5%D0%B2%20%D0%B0%D0%BB%D0%B5%D0%BA%D1%81%D0%B5%D0%B9/">Елисеев Алексей</a></span></div>
                      <div><i>Исполнитель:</i><a href="https://my-audiobooks.com/xfsearch/chtec/aztech/">Aztech</a></div>
                      <div><i>Серия:</i><a href="https://my-audiobooks.com/xfsearch/series/%D0%B7%D0%B2%D1%91%D0%B7%D0%B4%D0%BD%D0%B0%D1%8F%20%D0%BA%D1%80%D0%BE%D0%B2%D1%8C.%20%D0%B8%D0%B7%D0%B3%D0%BE%D0%B9/">Звёздная Кровь. Изгой</a></div>
                      <div><i>Время:</i>04:31:31</div>
                      <div><i>Добавлено:</i>28-12-2025, 09:04</div>
                    </div></div>
                    <div class="full-news-text mjjr">Заблокированная книга сохраняет описание и метаданные.</div>
                  </div>
                </div>
              </div>
              <div class="gnth"><p>К сожалению, произведение удалено по требованию правообладателя</p></div>
            </body></html>
        """.trimIndent()

        val metadata = AbredMyAudiobooksHtmlParser.parseMetadata(html, pageUrl, bookId, externalPath)

        assertTrue(AbredMyAudiobooksHtmlParser.isUnavailablePage(html, pageUrl))
        assertNull(AbredMyAudiobooksHtmlParser.parsePlaylistUrl(html, pageUrl))
        assertEquals("Звёздная Кровь. Изгой IX - Алексей Елисеев", metadata.title)
        assertEquals("Елисеев Алексей", metadata.authors.single().name)
        assertEquals("Aztech", metadata.narrators.single().name)
        assertEquals(listOf("Фантастика, фэнтези", "Попаданцы"), metadata.genres.map { it.name })
        assertEquals("Звёздная Кровь. Изгой", metadata.seriesName)
        assertEquals(9, metadata.seriesPosition)
        assertEquals(4 * 3600L + 31 * 60L + 31L, metadata.durationSeconds)
        assertEquals("https://my-audiobooks.com/uploads/posts/books/66591/66591.jpg", metadata.coverUrl)
        assertEquals("myaudiobooks", metadata.selectedSource)
        assertEquals("live:myaudiobooks:$externalPath", metadata.selectedBookSourceId)
    }

    @Test
    fun playlistJsonBecomesDirectDownloadCompatibleChapters() {
        val playlist = """
            [
              {"title":"master-trav-iv-01","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/0.mp3"},
              {"title":"master-trav-iv-02","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/1.mp3"},
              {"title":"foreign","file":"https://example.com/2.mp3"},
              {"title":"not-audio","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/cover.jpg"}
            ]
        """.trimIndent()
        val bookId = "myaudiobooks:litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"

        val chapters = AbredMyAudiobooksHtmlParser.parsePlaylist(playlist, bookId)

        assertEquals(2, chapters.size)
        assertEquals("master-trav-iv-01", chapters[0].title)
        assertEquals(0, chapters[0].position)
        assertEquals("$bookId:chapter:1", chapters[0].id)
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/0.mp3",
            chapters[0].streamUrl,
        )
        assertEquals("master-trav-iv-02", chapters[1].title)
        assertEquals(1, chapters[1].position)
        assertTrue(AbredMyAudiobooksHtmlParser.isAllowedCdnUrl(chapters[1].streamUrl))
        assertFalse(AbredMyAudiobooksHtmlParser.isAllowedCdnUrl("https://example.com/2.mp3"))
    }

    @Test
    fun attachingPlaylistCreatesOneSourceVariantWithoutInventingUrls() {
        val pageUrl = "https://my-audiobooks.com/litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"
        val externalPath = "litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"
        val bookId = "myaudiobooks:$externalPath"
        val metadataHtml = """
            <html><body>
              <div class="full-news">
                <div class="full-news-tb">
                  <div class="full-news-title"><h1>Мастер Трав IV - Мордорский Ваня</h1></div>
                  <div class="main-news-c"><a href="https://my-audiobooks.com/litrpg/">LitRPG</a></div>
                </div>
                <div class="full-news-stats-col"><div class="fnsc-left">
                  <div><i>Серия:</i><a href="https://my-audiobooks.com/xfsearch/series/%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80%20%D1%82%D1%80%D0%B0%D0%B2/">Мастер Трав</a></div>
                  <div><i>Исполнитель:</i><a href="https://my-audiobooks.com/xfsearch/chtec/%D1%87%D0%B5%D1%80%D1%81%D0%BA%D0%BE%D0%B2%20%D1%81%D1%82%D0%B0%D0%BD%D0%B8%D1%81%D0%BB%D0%B0%D0%B2/">Черсков Станислав</a></div>
                  <div><i>Время:</i>11:01:11</div>
                </div></div>
              </div>
            </body></html>
        """.trimIndent()
        val playlist = """
            [
              {"title":"master-trav-iv-01","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/0.mp3"},
              {"title":"master-trav-iv-02","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/1.mp3"}
            ]
        """.trimIndent()
        val metadata = AbredMyAudiobooksHtmlParser.parseMetadata(metadataHtml, pageUrl, bookId, externalPath)
        val chapters = AbredMyAudiobooksHtmlParser.parsePlaylist(playlist, bookId)

        val detail = AbredMyAudiobooksHtmlParser.attachChapters(metadata, chapters, externalPath, pageUrl)

        assertEquals(chapters, detail.chapters)
        val variant = detail.sourceVariants.single()
        assertEquals("live:myaudiobooks:$externalPath", variant.bookSourceId)
        assertEquals("myaudiobooks", variant.sourceCode)
        assertEquals("MY-AUDIOBOOKS", variant.sourceName)
        assertEquals("Мастер Трав", variant.seriesName)
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/1.mp3",
            detail.chapters[1].streamUrl,
        )
    }
}
