package com.example.data.parser

import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.SeriesDetailDto

/**
 * Keeps Baza-Knig detail loading single-flight without changing the provider's
 * public behavior. Concurrent requests for the same book share one HTML +
 * playlist load; source-series loading also goes through the same detail gate.
 */
internal class AbredBazaKnigSingleFlightParser(
    private val delegate: AbredBazaKnigParser = AbredBazaKnigParser(),
) {
    private val detailSingleFlight = BazaKnigSingleFlight<BookDetailDto>()

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> =
        BazaKnigSearchMetadata.observe(delegate.catalog(page))

    suspend fun search(query: String): List<LiveCatalogItemDto> =
        BazaKnigSearchMetadata.enrich(delegate.search(query))

    suspend fun genres(): List<GenreDto> = delegate.genres()

    fun canBrowseAuthor(id: String): Boolean = delegate.canBrowseAuthor(id)

    fun canBrowseNarrator(id: String): Boolean = delegate.canBrowseNarrator(id)

    fun canBrowseGenre(id: String): Boolean = delegate.canBrowseGenre(id)

    suspend fun authorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        BazaKnigSearchMetadata.observe(delegate.authorBooks(id, page, limit))

    suspend fun narratorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        BazaKnigSearchMetadata.observe(delegate.narratorBooks(id, page, limit))

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        BazaKnigSearchMetadata.observe(delegate.genreBooks(id, page, limit))

    suspend fun book(bookId: String): BookDetailDto =
        detailSingleFlight.run(bookId) { delegate.book(bookId) }
            .also { detail -> BazaKnigSearchMetadata.observeCoverUrl(detail.coverUrl) }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean =
        delegate.canLoadSourceSeries(bookId, provider)

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        // Prime/wait for the shared detail request first. delegate.sourceSeries()
        // then reuses the provider's detail cache instead of issuing a second
        // HTML + playlist request while the detail screen is still loading.
        book(bookId)
        return delegate.sourceSeries(bookId, provider, page, limit)
    }
}
