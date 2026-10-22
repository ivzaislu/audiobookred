package com.example.ui.paging

import androidx.paging.PagingSource
import com.example.data.model.BookCardDto
import com.example.data.textsource.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TextCatalogPagingSourceTest {
    private val first = "https://books.test/list/1"
    private val second = "https://books.test/list/2"
    private fun book(id: String) = RemoteTextBook(id, "Книга $id", "https://books.test/book/$id")
    private fun source(pages: Map<String, RemoteTextPage>, fetch: suspend (String) -> RemoteTextPage = { pages.getValue(it) }) =
        object : RemoteTextBookProvider {
            override val code = "test"
            override val name = "Источник"
            override val catalogUrl = first
            override fun bookId(url: String): String? = null
            override fun bookUrl(id: String) = "https://books.test/book/$id"
            override fun searchUrl(query: String) = first
            override fun isPageUrl(url: String) = url.matches(Regex("https://books\\.test/list/[1-9][0-9]*"))
            override suspend fun page(url: String) = fetch(url)
            override suspend fun book(id: String): RemoteTextBook = error("Not used")
            override suspend fun download(book: RemoteTextBook): ByteArray = error("Not used")
            override suspend fun downloadFile(book: RemoteTextBook, option: RemoteTextDownload): ByteArray = error("Not used")
        }
    private suspend fun firstPage(source: TextCatalogPagingSource) = source.load(
        PagingSource.LoadParams.Refresh<String>(key = null, loadSize = 20, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page<String, BookCardDto>
    private suspend fun append(source: TextCatalogPagingSource, key: String) = source.load(
        PagingSource.LoadParams.Append(key = key, loadSize = 20, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page<String, BookCardDto>

    @Test fun appendKeepsFirstPageAndDeduplicatesBookIds() = runBlocking {
        val pages = mapOf(first to RemoteTextPage("Каталог", listOf(book("1")), second),
            second to RemoteTextPage("Каталог", listOf(book("1"), book("2"), book("2")), null))
        var title = ""
        val source = TextCatalogPagingSource(source(pages), first) { title = it.title }
        val one = firstPage(source)
        val two = append(source, requireNotNull(one.nextKey))
        assertEquals(listOf("1", "2"), (one.data + two.data).map { it.id })
        assertEquals("Каталог", title)
        assertEquals(first, two.prevKey)
        assertNull(two.nextKey)
    }

    @Test fun pageEvictionCanReloadBothDirectionsWithoutLosingRows() = runBlocking {
        val source = TextCatalogPagingSource(source(mapOf(first to RemoteTextPage("Каталог", listOf(book("1")), second),
            second to RemoteTextPage("Каталог", listOf(book("2")), null))), first)
        firstPage(source)
        append(source, second)
        val reloaded = source.load(PagingSource.LoadParams.Prepend(key = first, loadSize = 20, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page<String, BookCardDto>
        assertEquals(listOf("1"), reloaded.data.map { it.id })
        assertEquals(second, reloaded.nextKey)
        assertEquals(listOf("2"), append(source, second).data.map { it.id })
    }

    @Test fun cyclicAndUnsupportedNextLinksEndPagination() = runBlocking {
        val cyclic = TextCatalogPagingSource(source(mapOf(first to RemoteTextPage("Каталог", listOf(book("1")), second),
            second to RemoteTextPage("Каталог", listOf(book("2")), first))), first)
        firstPage(cyclic)
        assertNull(append(cyclic, second).nextKey)
        val external = TextCatalogPagingSource(source(mapOf(first to RemoteTextPage("Каталог", listOf(book("1")), "https://other.test/"))), first)
        assertNull(firstPage(external).nextKey)
    }

    @Test fun failedPageCanBeRetriedWithoutLosingEarlierResults() = runBlocking {
        var failures = 1
        val provider = source(emptyMap()) { url ->
            if (url == first) RemoteTextPage("Каталог", listOf(book("1")), second)
            else { if (failures-- > 0) error("Нет сети"); RemoteTextPage("Каталог", listOf(book("2")), null) }
        }
        val source = TextCatalogPagingSource(provider, first)
        assertEquals(1, firstPage(source).data.size)
        assertTrue(source.load(PagingSource.LoadParams.Append(key = second, loadSize = 20, placeholdersEnabled = false)) is PagingSource.LoadResult.Error)
        assertEquals(listOf("2"), append(source, second).data.map { it.id })
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        val source = TextCatalogPagingSource(source(emptyMap()) { throw CancellationException("Cancelled") }, first)
        try { firstPage(source); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }

    @Test fun cardKeepsProviderIdentityAndFractionalSeriesNumber() {
        val card = book("1465").copy(authors = listOf(TextSourceLink("a", "Автор", "https://books.test/author")),
            series = TextSourceLink("cycle", "Цикл", "https://books.test/cycle"), seriesNumber = "1,5").catalogCard("test")
        assertEquals("1465", card.id)
        assertEquals("Автор", card.authorText)
        assertEquals("test", card.primarySource)
        assertEquals(1.5, card.series.single().position ?: 0.0, 0.0)
        assertEquals("", card.sourceMeta)
    }

    @Test fun detailsFillCoverAndCoauthorsWithoutChangingTheListingIdentity() {
        val listing = book("31866").copy(authors = listOf(TextSourceLink("5886", "Екатерина Каблукова", ""))).catalogCard("test")
        val details = listing.copy(coverUrl = "https://books.test/cover.jpg",
            authors = listing.authors + com.example.data.model.PersonDto("123", "Соавтор"))
        val merged = mergeTextCatalogCard(listing, details)
        assertEquals(listing.id, merged.id)
        assertEquals(details.coverUrl, merged.coverUrl)
        assertEquals(2, merged.authors.size)
        assertEquals(listing, mergeTextCatalogCard(listing, details.copy(id = "other")))
        assertEquals(listing, mergeTextCatalogCard(listing, details.copy(primarySource = "other")))
    }

    @Test fun incompleteDetailsDoNotEraseKnownAuthorOrCover() {
        val listing = book("31866").copy(authors = listOf(TextSourceLink("5886", "Екатерина Каблукова", "")),
            coverUrl = "https://books.test/cover.jpg").catalogCard("test")
        val merged = mergeTextCatalogCard(listing, book("31866").catalogCard("test"))
        assertEquals(listing.authors, merged.authors)
        assertEquals(listing.coverUrl, merged.coverUrl)
    }
}
