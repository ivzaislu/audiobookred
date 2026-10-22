package com.example.ui.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.data.model.BookCardDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesBriefDto
import com.example.data.textsource.RemoteTextBook
import com.example.data.textsource.RemoteTextBookProvider
import com.example.data.textsource.RemoteTextPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun RemoteTextBook.catalogCard(sourceCode: String): BookCardDto = BookCardDto(
    id = id, title = title, coverUrl = coverUrl.orEmpty(), authors = authors.map { PersonDto(it.id, it.name) },
    primarySource = sourceCode, sourceSeriesName = series?.name.orEmpty(), sourceSeriesPosition = seriesNumber?.toIntOrNull(),
    series = series?.let { value ->
        listOf(SeriesBriefDto(id = "text:$sourceCode:${value.id}", name = value.name,
            position = seriesNumber?.replace(',', '.')?.toDoubleOrNull()?.takeIf(Double::isFinite),
            isPrimary = true, provider = "text:$sourceCode", externalId = value.id))
    }.orEmpty(),
)

internal fun mergeTextCatalogCard(listing: BookCardDto, details: BookCardDto): BookCardDto {
    if (listing.id != details.id || listing.primarySource != details.primarySource) return listing
    return listing.copy(title = details.title.ifBlank { listing.title },
        authors = details.authors.ifEmpty { listing.authors }, coverUrl = details.coverUrl.ifBlank { listing.coverUrl },
        series = details.series.ifEmpty { listing.series },
        sourceSeriesName = details.sourceSeriesName.ifBlank { listing.sourceSeriesName },
        sourceSeriesPosition = details.sourceSeriesPosition ?: listing.sourceSeriesPosition)
}

/** Uses the same Paging 3 list/footer/refresh contract as the audio catalog. */
internal class TextCatalogPagingSource(
    private val source: RemoteTextBookProvider,
    private val firstUrl: String,
    private val onFirstPage: (RemoteTextPage) -> Unit = {},
) : PagingSource<String, BookCardDto>() {
    private val loadedUrls = mutableSetOf<String>()
    private val previousUrls = mutableMapOf<String, String>()
    private val nextUrls = mutableMapOf<String, String?>()
    private val bookOwners = mutableMapOf<String, String>()
    private val mutex = Mutex()
    override fun getRefreshKey(state: PagingState<String, BookCardDto>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, BookCardDto> {
        val url = params.key ?: firstUrl
        return try {
            require(source.isPageUrl(url)) { "Источник не поддерживает эту страницу каталога." }
            val page = source.page(url)
            val result = mutex.withLock {
                loadedUrls.add(url)
                val rows = page.items.distinctBy { it.id }.filter { book ->
                    val owner = bookOwners.getOrPut(book.id) { url }
                    owner == url
                }.map { it.catalogCard(source.code) }
                if (url !in nextUrls) {
                    val next = page.nextUrl?.takeIf { source.isPageUrl(it) && it !in loadedUrls }
                    nextUrls[url] = next
                    if (next != null) previousUrls[next] = url
                }
                LoadResult.Page(data = rows, prevKey = previousUrls[url], nextKey = nextUrls[url])
            }
            if (url == firstUrl) onFirstPage(page)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { LoadResult.Error(error) }
    }
}
