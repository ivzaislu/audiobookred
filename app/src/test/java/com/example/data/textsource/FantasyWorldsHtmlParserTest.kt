package com.example.data.textsource

import org.junit.Assert.*
import org.junit.Test

class FantasyWorldsHtmlParserTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fantasy-worlds/$name.html")).readText()
    private val origin = FantasyWorldsHtmlParser.ORIGIN

    @Test fun suppliedCardHasStableIdMetadataAndObservedDownloadLink() {
        val book = FantasyWorldsHtmlParser.book(fixture("book"), "$origin/lib/id1465")
        assertEquals("1465", book.id)
        assertEquals("Лабиринт отражений", book.title)
        assertEquals("Сергей Лукьяненко", book.authors.single().name)
        assertEquals("168", book.authors.single().id)
        assertEquals("381", book.series?.id)
        assertEquals("Дайвер", book.series?.name)
        assertEquals("1", book.seriesNumber)
        assertEquals("1997", book.year)
        assertEquals("$origin/img/preview/14/1465.jpg", book.coverUrl)
        assertEquals("$origin/lib/id1465/download/", book.downloadUrl)
        assertEquals(listOf("fb2", "epub", "mobi", "txt"), book.formats)
        assertTrue(book.description.isNotBlank())
        assertFalse(book.description.contains("Рейтинг"))
    }
    @Test fun suppliedCatalogKeepsCardBoundariesAndFollowsActualNextLink() {
        val page = FantasyWorldsHtmlParser.page(fixture("catalog"), "$origin/lib/1")
        assertEquals(15, page.items.size)
        assertEquals("40539", page.items.first().id)
        assertEquals("Какао для ведьмочки", page.items.first().title)
        assertEquals("40525", page.items.last().id)
        assertEquals("$origin/lib/2", page.nextUrl)
        assertEquals(15, page.items.map { it.id }.distinct().size)
    }
    @Test fun suppliedSeriesUsesOrderedBibliographyWithoutRepeatedCoverLinks() {
        val page = FantasyWorldsHtmlParser.page(fixture("series"), "$origin/series/id381/")
        assertEquals("Дайвер", page.title)
        assertEquals(listOf("1465", "1553", "1554"), page.items.map { it.id })
        assertEquals(listOf("1", "2", "3"), page.items.map { it.seriesNumber })
        assertEquals("Сергей Лукьяненко", page.items.first().authors.single().name)
        assertNull(page.nextUrl)
    }
    @Test fun optionalMetadataIsNotRequiredAndOtherBookDownloadIsNeverAccepted() {
        val html = """<div itemscope itemtype="http://schema.org/Book"><span itemprop="name">Книга</span><a id="download_book_12" href="/lib/id13/download/">Скачать</a></div>"""
        val book = FantasyWorldsHtmlParser.book(html, "$origin/lib/id12/")
        assertTrue(book.authors.isEmpty()); assertNull(book.coverUrl); assertNull(book.series)
        assertNull(book.downloadUrl)
    }
    @Test fun urlsAreCanonicalAndQueryEscapesDoNotChangeSearchParameters() {
        assertEquals("1465", FantasyWorldsHtmlParser.bookId("https://www.fantasy-worlds.org/lib/id1465"))
        assertEquals("$origin/search/?q=a%26b", FantasyWorldsHtmlParser.safeUrl("/search/?q=a%26b"))
        listOf("https://evil.test/lib/id1465/", "file:///lib/id1465/", "https://fantasy-worlds.org@evil.test/lib/id1465/",
            "https://fantasy-worlds.org:444/lib/id1465/").forEach { assertNull(FantasyWorldsHtmlParser.bookId(it)) }
    }
    @Test fun changedMarkupAndChallengeAreErrorsRatherThanFakeEmptyCatalogs() {
        assertThrows(IllegalArgumentException::class.java) {
            FantasyWorldsHtmlParser.page("<div class=rightBlock>Проверка браузера</div>", "$origin/lib/1")
        }
        val empty = FantasyWorldsHtmlParser.page("<div class=rightBlock>Ничего не найдено</div>", "$origin/search/?q=x")
        assertTrue(empty.items.isEmpty())
    }
    @Test fun authorAndSearchBibliographiesIgnoreCommentsAndKeepUniqueBooks() {
        val html = """<div class=rightBlock><h1>Автор</h1><ul><li><a href='/lib/id12/'>1 - Первая</a></li><li><a href='/lib/id13/'>Вторая</a></li></ul><a href='/lib/id12/'>Обложка</a><div class=comm_body><a href='/lib/id99/'>Комментарий</a></div></div>"""
        val page = FantasyWorldsHtmlParser.page(html, "$origin/author/id168/")
        assertEquals(listOf("12", "13"), page.items.map { it.id })
        assertEquals("Первая", page.items.first().title)
    }
    @Test fun capturedLiveSearchAndAuthorPagesParseUsingTheSameContract() {
        val search = FantasyWorldsHtmlParser.page(fixture("search"), "$origin/search/?q=test")
        assertEquals(listOf("1465"), search.items.map { it.id })
        val author = FantasyWorldsHtmlParser.page(fixture("author"), "$origin/author/id168/")
        assertTrue(author.items.size > 60)
        assertEquals("Лабиринт отражений", author.items.single { it.id == "1465" }.title)
        assertEquals(author.items.size, author.items.map { it.id }.distinct().size)
        assertTrue(author.items.all { it.authors.single().id == "168" && it.authors.single().name == "Сергей Лукьяненко" })
    }

    @Test fun suppliedKablukovaCardContainsTheCoverAndMetadataMissingFromTheAuthorListing() {
        val book = FantasyWorldsHtmlParser.book(fixture("book-31866"), "$origin/lib/id31866/")
        assertEquals("Декан моего сердца", book.title)
        assertEquals(TextSourceLink("5886", "Екатерина Каблукова", "$origin/author/id5886/"), book.authors.single())
        assertEquals("$origin/img/preview/318/31866.jpg", book.coverUrl)
        assertEquals("6574", book.series?.id)
        assertEquals("Север и юг", book.series?.name)
        assertEquals("1", book.seriesNumber)
    }

    @Test fun authorContextDoesNotReplaceExplicitCoauthorsOrLeakIntoOtherCollections() {
        val html = """<div class=rightBlock><h1>Екатерина Каблукова</h1><ul>
            <li><a href='/lib/id31866/'>Декан моего сердца</a></li>
            <li><a href='/lib/id31867/'>Другая книга</a><a href='/author/id123/'>Соавтор</a></li></ul></div>"""
        val author = FantasyWorldsHtmlParser.page(html, "$origin/author/id5886/2/")
        assertEquals("5886", author.items.first().authors.single().id)
        assertEquals("123", author.items.last().authors.single().id)
        assertTrue(FantasyWorldsHtmlParser.page(html, "$origin/search/?q=test").items.first().authors.isEmpty())
        assertTrue(FantasyWorldsHtmlParser.page(html, "$origin/series/id6574/").items.first().authors.isEmpty())
    }

    @Test fun redirectedCardOfAnotherBookIsNotImportedUnderRequestedIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            FantasyWorldsHtmlParser.book(fixture("book"), "$origin/lib/id1553/")
        }
    }

    @Test fun formatOptionsKeepActualSizeLabelsAndProviderDownloadRoutes() {
        val book = FantasyWorldsHtmlParser.book(fixture("book"), "$origin/lib/id1465/")
        assertEquals(listOf("fb2.zip, 330 Кб", "epub, 408 Кб", "mobi, 775 Кб", "txt, 1026 Кб"), book.downloads.map { it.label })
        assertEquals(listOf("", "epub", "mobi", "txt").map { "$origin/lib/id1465/download/$it" }, book.downloads.map { it.url })
        assertEquals(listOf("fb2.zip", "epub", "mobi", "txt"), book.downloads.map { it.extension })
        val catalog = FantasyWorldsHtmlParser.page(fixture("catalog"), "$origin/lib/1")
        assertEquals(listOf("fb2", "epub", "mobi", "txt"), catalog.items.first().downloads.map { it.code })
    }

    @Test fun unknownFormatsAndUnavailableDownloadsDoNotCreateFakeChoices() {
        val html = fixture("book").replace("value=\"epub\"", "value=\"../../bad\"")
        assertEquals(listOf("fb2", "mobi", "txt"), FantasyWorldsHtmlParser.book(html, "$origin/lib/id1465/").downloads.map { it.code })
        val missing = fixture("book").replace("href=\"/lib/id1465/download/\"", "href=\"https://evil.test/file\"")
        assertTrue(FantasyWorldsHtmlParser.book(missing, "$origin/lib/id1465/").downloads.isEmpty())
        assertFalse(FantasyWorldsHtmlParser.isFileDownloadUrl("$origin/lib/id1553/download/epub", "1465"))
        assertFalse(FantasyWorldsHtmlParser.isFileDownloadUrl("$origin/lib/id1465/download/txt?redirect=x", "1465"))
        assertFalse(FantasyWorldsHtmlParser.isDownloadUrl("$origin/lib/id1465/download/epub", "1465"))
    }

}
