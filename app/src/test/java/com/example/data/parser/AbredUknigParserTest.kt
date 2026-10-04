package com.example.data.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbredUknigParserTest {
    @Test
    fun catalogExtractsUniqueBooksWithoutDetailPreflight() {
        val html = """
            <html><body>
              <div class="book-item"><a class="book-title" href="/books/815724">Больше, чем ничего</a></div>
              <div class="book-item"><a href="/books/815724">Слушать аудиокнигу</a></div>
              <div class="book-item"><a class="book-title" href="/index.php/books/703125">Наполеон</a></div>
            </body></html>
        """.trimIndent()

        val rows = AbredUknigHtmlParser.parseCatalog(html, "https://uknig.com/")

        assertEquals(listOf("815724", "703125"), rows.map { it.externalId })
        assertEquals(listOf("Больше, чем ничего", "Наполеон"), rows.map { it.title })
        assertEquals(listOf("uknig:815724", "uknig:703125"), rows.map { it.key })
    }

    @Test
    fun catalogUsesLazyLoadedBookCoverAndMetadata() {
        val html = """
            <html><body>
              <img src="/images/ll_logo.svg">
              <div class="book-item panel panel-default">
                <div class="panel-body"><div class="media"><div class="media-left">
                  <div style="background: url(https://uknig.com/covers/846324.jpg?t=1788004579)"></div>
                  <a class="download" id="link-846324" href="https://uknig.com/books/846324">
                    <img class="cover" data-original="https://uknig.com/covers/846324_200x300.jpg?t=1788004579" src="/images/placeholder.jpg">
                  </a>
                </div><div class="media-body">
                  <a class="book-title" href="https://uknig.com/books/846324">Хранитель Замка</a>
                  <div class="book-author"><a href="/authors/viktor-molotov">Виктор Молотов</a></div>
                  <a href="/readers/ivan-ivanov">Иван Иванов</a>
                  <a href="/genres/fentezi">Фэнтези</a>
                  <a href="/series/77">Хранитель</a>
                </div></div></div>
              </div>
            </body></html>
        """.trimIndent()

        val row = AbredUknigHtmlParser.parseCatalog(html, "https://uknig.com/").single()

        assertEquals("uknig:846324", row.key)
        assertEquals("Хранитель Замка", row.title)
        assertEquals("https://uknig.com/covers/846324_200x300.jpg?t=1788004579", row.coverUrl)
        assertEquals(listOf("Виктор Молотов"), row.authors)
        assertEquals(listOf("Иван Иванов"), row.narrators)
        assertEquals(listOf("Фэнтези"), row.genres)
        assertEquals("77", row.seriesExternalId)
    }

    @Test
    fun fullDetailKeepsSourceQualifiedPeopleGenreAndSeriesIds() {
        val html = """
            <html><head><meta property="og:image" content="/covers/815724.jpg"></head><body>
              <h1>Больше, чем ничего</h1>
              <div>Описание книги</div><p>Тестовое описание.</p><div>Подробная информация</div>
              <div>Автор: <a href="/authors/ekaterina-yudina">Екатерина Юдина</a></div>
              <div><a href="/genres/lyubovnoe-fentezi">Любовное фэнтези</a></div>
              <div>Читает <a href="/readers/marina-vysotskaya">Марина Высоцкая</a></div>
              <div>Входит в серию <a href="/series/9006">Ничего</a> (#2)</div>
              <div>13 часов 12 минут</div>
            </body></html>
        """.trimIndent()
        val playlist = """
            [
              {"title":"Глава 1","file":"https://uknig.com/index.php/files/10?h=a","id":"10"},
              {"title":"Глава 2","file":"https://uknig.com/files/11?h=b or https://uknig.com/files/11?d=1&h=b","id":"11"}
            ]
        """.trimIndent()

        val metadata = AbredUknigHtmlParser.parseMetadata(html, "https://uknig.com/books/815724", "uknig:815724")
        val chapters = AbredUknigHtmlParser.parsePlaylist(playlist, "uknig:815724")
        val book = AbredUknigHtmlParser.attachChapters(metadata, chapters)

        assertEquals("uknig:author:ekaterina-yudina", book.authors.single().id)
        assertEquals("uknig:reader:marina-vysotskaya", book.narrators.single().id)
        assertEquals("uknig:genre:lyubovnoe-fentezi", book.genres.single().id)
        assertEquals("source:uknig:9006", book.audioSeries.single().id)
        assertEquals("9006", book.audioSeries.single().externalId)
        assertEquals(2, book.seriesPosition)
        assertEquals(2, book.chapters.size)
        assertEquals("https://uknig.com/files/11?h=b", book.chapters[1].streamUrl)
    }

    @Test
    fun browseAndSearchUrlsFollowUknigContract() {
        assertTrue(AbredUknigHtmlParser.searchUrl("Книжный клуб").startsWith("https://uknig.com/?q="))
        assertEquals("https://uknig.com/authors/viktor-molotov", AbredUknigHtmlParser.collectionPageUrl("authors", "viktor-molotov", 1))
        assertEquals("https://uknig.com/authors/viktor-molotov?p=2", AbredUknigHtmlParser.collectionPageUrl("authors", "viktor-molotov", 2))
        assertEquals("https://uknig.com/readers/ivan-ivanov", AbredUknigHtmlParser.collectionPageUrl("readers", "ivan-ivanov", 1))
        assertEquals("https://uknig.com/genres/fentezi", AbredUknigHtmlParser.collectionPageUrl("genres", "fentezi", 1))
        assertEquals("https://uknig.com/series/77", AbredUknigHtmlParser.collectionPageUrl("series", "77", 1))
        assertEquals("viktor-molotov", AbredUknigHtmlParser.parseEntityRef("uknig:author:viktor-molotov", "author"))
        assertEquals("ivan-ivanov", AbredUknigHtmlParser.parseEntityRef("uknig:reader:ivan-ivanov", "reader"))
    }

    @Test
    fun logicalCollectionPagingUsesCurrentEighteenItemSitePages() {
        assertEquals(
            PhysicalPageWindow(page = 1, skip = 5),
            physicalPageWindow(page = 2, limit = 5, sitePageSize = UKNIG_COLLECTION_PAGE_SIZE),
        )
        assertEquals(
            PhysicalPageWindow(page = 2, skip = 12),
            physicalPageWindow(page = 2, limit = 30, sitePageSize = UKNIG_COLLECTION_PAGE_SIZE),
        )
    }

    @Test
    fun genreDirectoryUsesSourceQualifiedIds() {
        val html = """
            <html><body>
              <a href="/genres/fentezi">Фэнтези</a>
              <a href="/genres/detektivy">Детективы</a>
              <a href="/genres/fentezi">Фэнтези</a>
            </body></html>
        """.trimIndent()

        val genres = AbredUknigHtmlParser.parseGenres(html, "https://uknig.com/genres")

        assertEquals(listOf("uknig:genre:detektivy", "uknig:genre:fentezi"), genres.map { it.id })
    }

    @Test
    fun previewMarkerPreservesMetadataForAlternativeSearch() {
        val html = """
            <html><body><h1>Черный Обелиск</h1>
              <div class="alert-info">Ознакомительный фрагмент</div>
              <a href="/authors/erih-mariya-remark">Эрих Мария Ремарк</a>
              <a href="https://www.litres.ru/5957154/">Полная версия аудиокниги</a>
            </body></html>
        """.trimIndent()

        val metadata = AbredUknigHtmlParser.parseMetadata(
            html,
            "https://uknig.com/books/677966",
            "uknig:677966",
        )

        assertEquals("Черный Обелиск", metadata.title)
        assertEquals("uknig", metadata.selectedSource)
        assertTrue(AbredUknigHtmlParser.isPreviewPage(html, "https://uknig.com/books/677966"))
    }

    @Test
    fun rightsHolderBlockIsUnavailable() {
        val html = """
            <html><body><h1>Закрытая книга</h1>
              <div>Прослушивание заблокировано правообладателем</div>
            </body></html>
        """.trimIndent()

        try {
            AbredUknigHtmlParser.parseMetadata(html, "https://uknig.com/books/804464", "uknig:804464")
            fail("Rights-holder blocked book must be rejected")
        } catch (_: UnavailableUknigBook) {
        }
    }

    @Test
    fun emptyOrInvalidPlaylistIsADataFailureNotPreview() {
        listOf("[]", "not-json", "[{\"title\":\"Фрагмент\",\"file\":\"not-a-url\",\"id\":\"1\"}]").forEach { payload ->
            try {
                AbredUknigHtmlParser.parsePlaylist(payload, "uknig:724452")
                fail("Invalid full playlist must be rejected: $payload")
            } catch (error: IOException) {
                assertFalse(error is PreviewOnlyUknigBook)
            }
        }
    }

    @Test
    fun playlistHttpFailuresDoNotInventPreviewAvailability() {
        val bookUrl = "https://uknig.com/books/724452"
        assertNull(uknigPlaylistHttpFailure(200, bookUrl))
        assertTrue(uknigPlaylistHttpFailure(404, bookUrl) is UnavailableUknigBook)
        assertTrue(uknigPlaylistHttpFailure(410, bookUrl) is UnavailableUknigBook)

        val forbidden = uknigPlaylistHttpFailure(403, bookUrl)
        assertTrue(forbidden is IOException)
        assertFalse(forbidden is PreviewOnlyUknigBook)

        val serverFailure = uknigPlaylistHttpFailure(503, bookUrl)
        assertTrue(serverFailure is IOException)
        assertFalse(serverFailure is PreviewOnlyUknigBook)
    }

    @Test
    fun detailRejectsSvgLogoAndFallsBackToBookCover() {
        val html = """
            <html><head><meta property="og:image" content="/images/ll_logo.svg"></head><body>
              <h1>Хранитель Замка</h1>
              <div class="book-item">
                <img class="cover" data-original="https://uknig.com/covers/846324_200x300.jpg?t=1788004579" src="/images/placeholder.jpg">
              </div>
            </body></html>
        """.trimIndent()
        val book = AbredUknigHtmlParser.parseMetadata(html, "https://uknig.com/books/846324", "uknig:846324")
        assertEquals("https://uknig.com/covers/846324_200x300.jpg?t=1788004579", book.coverUrl)
        assertFalse(book.coverUrl.endsWith(".svg"))
    }
}
