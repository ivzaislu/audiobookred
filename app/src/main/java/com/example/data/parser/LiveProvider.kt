package com.example.data.parser

import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.SeriesDetailDto

/**
 * Source-neutral contract used by [AndroidLiveParserHub].
 *
 * Provider implementations adapt the existing source parsers without forcing
 * them to share inheritance or transport details. The registry routes by the
 * source encoded in book/entity ids, so capability checks never probe unrelated
 * parsers.
 */
internal interface LiveProvider {
    val sourceCode: String

    suspend fun genres(): List<GenreDto>
    suspend fun catalog(page: Int): List<LiveCatalogItemDto>
    suspend fun search(query: String, page: Int, limit: Int): List<LiveCatalogItemDto>
    suspend fun book(bookId: String): BookDetailDto

    fun canBrowseAuthor(authorId: String): Boolean = false
    fun canBrowseNarrator(narratorId: String): Boolean = false
    fun canBrowseGenre(genreId: String): Boolean = false

    suspend fun authorBooks(
        authorId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> = unsupported("author")

    suspend fun narratorBooks(
        narratorId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> = unsupported("narrator")

    suspend fun genreBooks(
        genreId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> = unsupported("genre")

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean = false

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto = unsupported("series")

    suspend fun homeSection(section: String): List<LiveCatalogItemDto> =
        unsupported("home section")

    private fun unsupported(capability: String): Nothing =
        error("$sourceCode does not support $capability")
}

/**
 * Functional adapter keeps every existing parser lazy and provider-specific.
 * Only the selected source's parser is touched when one of these lambdas runs.
 */
internal class LiveProviderAdapter(
    override val sourceCode: String,
    private val genresBlock: suspend () -> List<GenreDto>,
    private val catalogBlock: suspend (Int) -> List<LiveCatalogItemDto> = { emptyList() },
    private val searchBlock: suspend (String, Int, Int) -> List<LiveCatalogItemDto> =
        { _, _, _ -> emptyList() },
    private val bookBlock: suspend (String) -> BookDetailDto,
    private val canBrowseAuthorBlock: (String) -> Boolean = { false },
    private val canBrowseNarratorBlock: (String) -> Boolean = { false },
    private val canBrowseGenreBlock: (String) -> Boolean = { false },
    private val authorBooksBlock: (suspend (String, Int, Int) -> List<LiveCatalogItemDto>)? = null,
    private val narratorBooksBlock: (suspend (String, Int, Int) -> List<LiveCatalogItemDto>)? = null,
    private val genreBooksBlock: (suspend (String, Int, Int) -> List<LiveCatalogItemDto>)? = null,
    private val canLoadSourceSeriesBlock: (String, String?) -> Boolean = { _, _ -> false },
    private val sourceSeriesBlock:
        (suspend (String, String?, Int, Int) -> SeriesDetailDto)? = null,
    private val homeSectionBlock: (suspend (String) -> List<LiveCatalogItemDto>)? = null,
) : LiveProvider {
    override suspend fun genres(): List<GenreDto> = genresBlock()

    override suspend fun catalog(page: Int): List<LiveCatalogItemDto> =
        catalogBlock(page)

    override suspend fun search(
        query: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> =
        searchBlock(query, page, limit)

    override suspend fun book(bookId: String): BookDetailDto = bookBlock(bookId)

    override fun canBrowseAuthor(authorId: String): Boolean =
        canBrowseAuthorBlock(authorId)

    override fun canBrowseNarrator(narratorId: String): Boolean =
        canBrowseNarratorBlock(narratorId)

    override fun canBrowseGenre(genreId: String): Boolean =
        canBrowseGenreBlock(genreId)

    override suspend fun authorBooks(
        authorId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> =
        requireNotNull(authorBooksBlock) { "$sourceCode does not support author browse" }(
            authorId,
            page,
            limit,
        )

    override suspend fun narratorBooks(
        narratorId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> =
        requireNotNull(narratorBooksBlock) { "$sourceCode does not support narrator browse" }(
            narratorId,
            page,
            limit,
        )

    override suspend fun genreBooks(
        genreId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> =
        requireNotNull(genreBooksBlock) { "$sourceCode does not support genre browse" }(
            genreId,
            page,
            limit,
        )

    override fun canLoadSourceSeries(bookId: String, provider: String?): Boolean =
        canLoadSourceSeriesBlock(bookId, provider)

    override suspend fun sourceSeries(
        bookId: String,
        provider: String?,
        page: Int,
        limit: Int,
    ): SeriesDetailDto =
        requireNotNull(sourceSeriesBlock) { "$sourceCode does not support series" }(
            bookId,
            provider,
            page,
            limit,
        )

    override suspend fun homeSection(section: String): List<LiveCatalogItemDto> =
        requireNotNull(homeSectionBlock) { "$sourceCode does not support home sections" }(section)
}

internal class LiveProviderRegistry(
    providers: List<LiveProvider>,
) {
    private val ordered = providers.toList()
    private val bySource = ordered.associateBy { it.sourceCode }

    init {
        require(ordered.size == bySource.size) { "Duplicate live provider source code" }
        require(ordered.all { it.sourceCode.isNotBlank() }) { "Blank live provider source code" }
    }

    val sources: List<String> = ordered.map(LiveProvider::sourceCode)

    fun supports(source: String?): Boolean =
        normalizeLiveSource(source) in bySource

    fun provider(source: String?): LiveProvider? =
        bySource[normalizeLiveSource(source)]

    fun requireProvider(source: String): LiveProvider =
        provider(source) ?: error("Android live parser does not support $source")

    fun providerForBook(bookId: String): LiveProvider? =
        parseLiveBookKey(bookId)?.first?.let(::provider)

    fun providerForEntityRef(value: String): LiveProvider? =
        liveEntitySource(value)?.let(::provider)
}

private fun liveEntitySource(value: String): String? {
    if (!isSafeLiveEntityRef(value)) return null
    return normalizeLiveSource(value.substringBefore(':'))
}

private fun normalizeLiveSource(source: String?): String =
    source?.trim()?.lowercase().orEmpty()
