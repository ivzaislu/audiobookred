package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbredAplParserTest {
    @Test
    fun catalogUsesAplSelectors() {
        val html = """
            <html><body><div id="BL">
              <div class="book-list-item">
                <div class="book-list-item-cover-img"><img data-src="/covers/book.jpg"></div>
                <a class="book-list-item-name-link" href="/7520884/">Тестовая книга 2</a>
                <a class="book-list-item-author-link" href="/author/11/">Автор Тестов</a>
                <a class="book-list-item-reader-link" href="/voice/22/">Чтец Тестов</a>
                <a class="book-list-item-genre-link" href="/genre/33/">Фантастика</a>
                <a href="/series/44/">Тестовый цикл</a>
                <a class="book-list-item-duration-link">12:34:56</a>
              </div>
            </div></body></html>
        """.trimIndent()

        val items = AbredAplHtmlParser.parseCatalog(html, "https://audiopolka.club/")

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("audiopolka:7520884", item.key)
        assertEquals("7520884", item.externalId)
        assertEquals("https://audiopolka.club/7520884/", item.externalUrl)
        assertEquals("https://audiopolka.club/covers/book.jpg", item.coverUrl)
        assertEquals(listOf("Автор Тестов"), item.authors)
        assertEquals(listOf("Чтец Тестов"), item.narrators)
        assertEquals(listOf("Фантастика"), item.genres)
        assertEquals("Тестовый цикл", item.seriesName)
        assertEquals("44", item.seriesExternalId)
        assertEquals(2, item.seriesPosition)
        assertEquals(12 * 3600L + 34 * 60L + 56L, item.durationSeconds)
    }

    @Test
    fun collectionCatalogDoesNotRequireBlRoot() {
        val html = """
            <html><body>
              <h1>Тестовый цикл</h1>
              <div>Цикл • 2 книги</div>
              <div class="book-list-item">
                <div class="book-list-item-cover-img"><img src="/covers/one.jpg"></div>
                <a class="book-list-item-name-link" href="/1001/">Первая книга. Том 1</a>
                <a class="book-list-item-author-link" href="/author/11/">Автор Тестов</a>
                <a href="/series/44/">Тестовый цикл</a>
              </div>
              <div class="book-list-item">
                <div class="book-list-item-cover-img"><img src="/covers/two.jpg"></div>
                <a class="book-list-item-name-link" href="/1002/">Вторая книга. Том 2</a>
                <a class="book-list-item-author-link" href="/author/11/">Автор Тестов</a>
                <a href="/series/44/">Тестовый цикл</a>
              </div>
            </body></html>
        """.trimIndent()

        val config = AbredAplConfig.defaults()
        val rows = AbredAplHtmlParser.parseCollectionCatalog(
            html,
            "https://audiopolka.club/series/44/",
            config,
        )
        val metadata = AbredAplHtmlParser.parseCollectionMetadata(
            html,
            "https://audiopolka.club/series/44/",
        )

        assertEquals(listOf("1001", "1002"), rows.map { it.externalId })
        assertEquals(listOf("Первая книга. Том 1", "Вторая книга. Том 2"), rows.map { it.title })
        assertEquals(2, metadata.totalCount)
        assertEquals("Тестовый цикл", metadata.name)
    }

    @Test
    fun detailKeepsSourceQualifiedPeopleGenresAndSeriesIds() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Книга тест — Аудиополка">
                <meta property="og:image" content="https://cdn.example/cover.jpg">
                <meta property="og:description" content="Описание книги">
              </head>
              <body>
                <div class="book-page-main">
                  <div class="book-page-meta-line"><span itemprop="author"><a href="/author/1/">Иван Авторов</a></span></div>
                  <div class="book-page-meta-line"><a href="/voice/2/">Пётр Чтецов</a></div>
                  <div class="book-page-meta-line">01:02:03</div>
                  <a href="/series/77/">Большой цикл</a>
                  <a href="/genre/3/">Фантастика</a>
                </div>
                <script>
                  KB.playerInit({"playlist":[{"fileId":101,"title":"Глава &amp; 1","src":"https:\/\/cdn.example\/audio\/1.mp3","duration":120}]});
                </script>
              </body>
            </html>
        """.trimIndent()

        val detail = AbredAplHtmlParser.parseBook(
            html,
            "https://audiopolka.club/7520884/",
            "audiopolka:7520884",
        )

        assertEquals("audiopolka:7520884", detail.id)
        assertEquals("audiopolka", detail.selectedSource)
        assertEquals("live:audiopolka:7520884", detail.selectedBookSourceId)
        assertEquals("audiopolka:author:1", detail.authors.single().id)
        assertEquals("Иван Авторов", detail.authors.single().name)
        assertEquals("audiopolka:voice:2", detail.narrators.single().id)
        assertEquals("Пётр Чтецов", detail.narrators.single().name)
        assertEquals("audiopolka:genre:3", detail.genres.single().id)
        assertEquals("Фантастика", detail.genres.single().name)
        assertEquals("source:audiopolka:77", detail.audioSeries.single().id)
        assertEquals("77", detail.audioSeries.single().externalId)
        assertEquals("audiopolka:7520884:chapter:101", detail.chapters.single().id)
        assertEquals("Глава & 1", detail.chapters.single().title)
        assertEquals("https://cdn.example/audio/1.mp3", detail.chapters.single().streamUrl)
        assertEquals(120L, detail.chapters.single().durationSeconds)
        assertEquals("https://cdn.example/cover.jpg", detail.coverUrl)
        assertEquals("Описание книги", detail.description)
    }

    @Test
    fun unsafePlaylistMediaUrlsAreIgnored() {
        val html = """
            <html><head><meta property="og:title" content="Безопасный плейлист — Аудиополка"></head><body>
              <div class="book-page-main"></div>
              <script>
                KB.playerInit({"playlist":[
                  {"fileId":1,"title":"Local file","src":"file:\/\/\/sdcard\/secret.mp3","duration":1},
                  {"fileId":2,"title":"Loopback","src":"http:\/\/127.0.0.1:8080\/private.mp3","duration":1},
                  {"fileId":3,"title":"LAN","src":"http:\/\/192.168.1.5\/private.mp3","duration":1},
                  {"fileId":4,"title":"Safe CDN","src":"https:\/\/cdn.example\/audio\/safe.mp3","duration":1}
                ]});
              </script>
            </body></html>
        """.trimIndent()

        val detail = AbredAplHtmlParser.parseBook(
            html,
            "https://audiopolka.club/1000/",
            "audiopolka:1000",
        )

        assertEquals(1, detail.chapters.size)
        assertEquals("Safe CDN", detail.chapters.single().title)
        assertEquals("https://cdn.example/audio/safe.mp3", detail.chapters.single().streamUrl)
    }

    @Test
    fun collectionRoutesUseAudiopolkaPagePathContract() {
        assertEquals(
            "https://audiopolka.club/author/11/",
            AbredAplHtmlParser.collectionPageUrl("https://audiopolka.club", "author", "11", 1),
        )
        assertEquals(
            "https://audiopolka.club/author/11/p1/",
            AbredAplHtmlParser.collectionPageUrl("https://audiopolka.club", "author", "11", 2),
        )
        assertEquals(
            "https://audiopolka.club/voice/22/p2/",
            AbredAplHtmlParser.collectionPageUrl("https://audiopolka.club", "voice", "22", 3),
        )
        assertEquals(
            "https://audiopolka.club/genre/33/",
            AbredAplHtmlParser.collectionPageUrl("https://audiopolka.club", "genre", "33", 1),
        )
        assertEquals(
            "https://audiopolka.club/genre/33/p1/",
            AbredAplHtmlParser.collectionPageUrl("https://audiopolka.club", "genre", "33", 2),
        )
        assertEquals(
            "https://audiopolka.club/series/77/p1/",
            AbredAplHtmlParser.collectionPageUrl("https://audiopolka.club", "series", "77", 2),
        )
        assertEquals("11", AplParserSupport.parseEntityRef("audiopolka:author:11", "author"))
        assertEquals("22", AplParserSupport.parseEntityRef("audiopolka:voice:22", "voice"))
        assertEquals("33", AplParserSupport.parseEntityRef("audiopolka:genre:33", "genre"))
    }

    @Test
    fun collectionMetadataReadsNameAndBookCount() {
        val html = """
            <html><body>
              <h1>Большой цикл</h1>
              <div>Цикл • 42 книги</div>
              <div id="BL"></div>
            </body></html>
        """.trimIndent()

        val metadata = AbredAplHtmlParser.parseCollectionMetadata(
            html,
            "https://audiopolka.club/series/77/",
        )

        assertEquals("Большой цикл", metadata.name)
        assertEquals(42, metadata.totalCount)
    }

    @Test
    fun litresAudiotrialIsRejectedAsPreview() {
        val html = """
            <html><head><meta property="og:title" content="Только фрагмент — Аудиополка"></head><body>
              <div class="book-page-main"></div>
              <script>
                KB.playerInit({"playlist":[{"fileId":1,"title":"Начало","src":"https:\/\/www.litres.ru\/audiotrial\/demo.mp3","duration":0}]});
              </script>
            </body></html>
        """.trimIndent()

        try {
            AbredAplHtmlParser.parseBook(
                html,
                "https://audiopolka.club/999/",
                "audiopolka:999",
            )
            fail("Preview-only book must be rejected")
        } catch (_: PreviewOnlyAudiopolkaBook) {
            // expected
        }
    }

    @Test
    fun urlsFollowAplProviderContract() {
        assertEquals(
            "https://audiopolka.club/",
            AbredAplHtmlParser.catalogPageUrl("https://audiopolka.club", 1),
        )
        assertEquals(
            "https://audiopolka.club/p1/",
            AbredAplHtmlParser.catalogPageUrl("https://audiopolka.club", 2),
        )
        val search = AbredAplHtmlParser.searchUrl("https://audiopolka.club", "Лукьяненко тест")
        assertTrue(search.startsWith("https://audiopolka.club/search/?q="))
        assertTrue(search.contains("%D0%9B"))
        assertTrue(search.endsWith("+%D1%82%D0%B5%D1%81%D1%82"))
    }
}
