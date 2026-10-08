package com.example.data.parser

import android.content.Context
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.SeriesDetailDto
import com.example.data.settings.ExternalServiceCredentialsStore
import com.example.data.source.StandaloneSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidLiveParserHub internal constructor(
    context: Context,
    private val externalServiceCredentialsStore: () -> ExternalServiceCredentialsStore,
) {
    private val appContext = context.applicationContext

    // Existing parsers remain lazy. Registering a provider is cheap; the real
    // parser is constructed only after the registry routes an operation to it.
    private val audiopolka by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredAplParser(appContext)
    }
    private val uknig by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredUknigParser()
    }
    private val audioboo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredAudiobooParser(appContext)
    }
    private val knigavuhe by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredKnigavuheParserV2()
    }
    private val bazaknig by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredBazaKnigSingleFlightParser()
    }
    private val myaudiobooks by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredMyAudiobooksParser()
    }
    private val audioknigalife by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredAudioknigaLifeParser()
    }
    private val rutracker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredRuTrackerParser(
            context = appContext,
            credentialsStore = externalServiceCredentialsStore(),
        )
    }

    private val providers = LiveProviderRegistry(
        listOf(
            LiveProviderAdapter(
                sourceCode = AUDIOPOLKA_SOURCE,
                genresBlock = { audiopolka.genres() },
                catalogBlock = { page -> audiopolka.catalog(page) },
                searchBlock = { query, _, _ -> audiopolka.search(query) },
                bookBlock = { bookId -> audiopolka.book(bookId) },
                canBrowseAuthorBlock = { id -> audiopolka.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> audiopolka.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> audiopolka.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> audiopolka.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> audiopolka.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> audiopolka.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> audiopolka.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit -> audiopolka.sourceSeries(bookId, provider, page, limit) },
            ),
            LiveProviderAdapter(
                sourceCode = UKNIG_SOURCE,
                genresBlock = { uknig.genres() },
                catalogBlock = { page -> uknig.catalog(page) },
                searchBlock = { query, _, _ -> uknig.search(query) },
                bookBlock = { bookId -> uknig.book(bookId) },
                canBrowseAuthorBlock = { id -> uknig.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> uknig.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> uknig.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> uknig.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> uknig.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> uknig.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> uknig.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit -> uknig.sourceSeries(bookId, provider, page, limit) },
            ),
            LiveProviderAdapter(
                sourceCode = AUDIOBOO_SOURCE,
                genresBlock = { audioboo.genres() },
                catalogBlock = { page -> audioboo.catalog(page) },
                searchBlock = { query, _, _ -> audioboo.search(query) },
                bookBlock = { bookId -> audioboo.book(bookId) },
                canBrowseAuthorBlock = { id -> audioboo.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> audioboo.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> audioboo.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> audioboo.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> audioboo.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> audioboo.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> audioboo.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit -> audioboo.sourceSeries(bookId, provider, page, limit) },
            ),
            LiveProviderAdapter(
                sourceCode = KNIGAVUHE_SOURCE,
                genresBlock = { knigavuhe.genres() },
                catalogBlock = { page -> knigavuhe.catalog(page) },
                searchBlock = { query, page, _ -> knigavuhe.search(query, page) },
                bookBlock = { bookId -> knigavuhe.book(bookId) },
                canBrowseAuthorBlock = { id -> knigavuhe.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> knigavuhe.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> knigavuhe.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> knigavuhe.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> knigavuhe.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> knigavuhe.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> knigavuhe.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit -> knigavuhe.sourceSeries(bookId, provider, page, limit) },
                homeSectionBlock = { section -> knigavuhe.homeSection(section) },
            ),
            LiveProviderAdapter(
                sourceCode = BAZAKNIG_SOURCE,
                genresBlock = { bazaknig.genres() },
                catalogBlock = { page -> bazaknig.catalog(page) },
                searchBlock = { query, _, _ -> bazaknig.search(query) },
                bookBlock = { bookId -> bazaknig.book(bookId) },
                canBrowseAuthorBlock = { id -> bazaknig.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> bazaknig.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> bazaknig.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> bazaknig.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> bazaknig.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> bazaknig.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> bazaknig.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit -> bazaknig.sourceSeries(bookId, provider, page, limit) },
            ),
            LiveProviderAdapter(
                sourceCode = MYAUDIOBOOKS_SOURCE,
                genresBlock = { myaudiobooks.genres() },
                catalogBlock = { page -> myaudiobooks.catalog(page) },
                searchBlock = { query, _, _ -> myaudiobooks.search(query) },
                bookBlock = { bookId -> myaudiobooks.book(bookId) },
                canBrowseAuthorBlock = { id -> myaudiobooks.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> myaudiobooks.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> myaudiobooks.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> myaudiobooks.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> myaudiobooks.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> myaudiobooks.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> myaudiobooks.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit -> myaudiobooks.sourceSeries(bookId, provider, page, limit) },
            ),
            LiveProviderAdapter(
                sourceCode = AUDIOKNIGA_LIFE_SOURCE,
                genresBlock = { audioknigalife.genres() },
                catalogBlock = { page -> audioknigalife.catalog(page) },
                searchBlock = { query, _, _ -> audioknigalife.search(query) },
                bookBlock = { bookId -> audioknigalife.book(bookId) },
                canBrowseAuthorBlock = { id -> audioknigalife.canBrowseAuthor(id) },
                canBrowseNarratorBlock = { id -> audioknigalife.canBrowseNarrator(id) },
                canBrowseGenreBlock = { id -> audioknigalife.canBrowseGenre(id) },
                authorBooksBlock = { id, page, limit -> audioknigalife.authorBooks(id, page, limit) },
                narratorBooksBlock = { id, page, limit -> audioknigalife.narratorBooks(id, page, limit) },
                genreBooksBlock = { id, page, limit -> audioknigalife.genreBooks(id, page, limit) },
                canLoadSourceSeriesBlock = { bookId, provider -> audioknigalife.canLoadSourceSeries(bookId, provider) },
                sourceSeriesBlock = { bookId, provider, page, limit ->
                    audioknigalife.sourceSeries(bookId, provider, page, limit)
                },
            ),
            LiveProviderAdapter(
                sourceCode = RUTRACKER_SOURCE,
                genresBlock = { rutracker.genres() },
                searchBlock = { query, page, limit ->
                    rutracker.search(query, page, limit)
                },
                bookBlock = { bookId -> rutracker.book(bookId) },
                canBrowseGenreBlock = { id -> rutracker.canBrowseGenre(id) },
                genreBooksBlock = { id, page, limit -> rutracker.genreBooks(id, page, limit) },
            ),
        )
    )

    init {
        val configuredSources = StandaloneSourceRegistry.activeSources.map { it.code }
        check(providers.sources == configuredSources) {
            "Live provider registry does not match active source metadata: " +
                "providers=${providers.sources}, configured=$configuredSources"
        }
    }

    val sources: List<String>
        get() = providers.sources

    fun supportsSource(source: String?): Boolean = providers.supports(source)

    fun canParseBook(bookId: String, source: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        if (!providers.supports(parsed.first)) return false
        val requested = source?.trim()?.lowercase().orEmpty()
        return requested.isBlank() || requested == parsed.first
    }

    suspend fun genres(source: String = AUDIOPOLKA_SOURCE): List<GenreDto> =
        providers.provider(source)?.genres().orEmpty()

    fun canBrowseAuthor(authorId: String): Boolean {
        val provider = providers.providerForEntityRef(authorId) ?: return false
        return provider.canBrowseAuthor(authorId)
    }

    fun canBrowseNarrator(narratorId: String): Boolean {
        val provider = providers.providerForEntityRef(narratorId) ?: return false
        return provider.canBrowseNarrator(narratorId)
    }

    fun canBrowseGenre(genreId: String): Boolean {
        val provider = providers.providerForEntityRef(genreId) ?: return false
        return provider.canBrowseGenre(genreId)
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        if (!sourceSeriesCapabilityAllows(bookId)) return false
        val selected = providers.providerForBook(bookId) ?: return false
        return selected.canLoadSourceSeries(bookId, provider)
    }

    suspend fun catalog(source: String, page: Int = 1): List<LiveCatalogItemDto> {
        val provider = providers.requireProvider(source)
        return provider.catalog(page.coerceAtLeast(1))
    }

    /**
     * Knigavuhe-only discovery feeds used by Home. Provider lookup itself is
     * cheap; the real parser remains lazy until this method is called.
     */
    suspend fun knigavuheHome(section: String): List<LiveCatalogItemDto> =
        withContext(Dispatchers.IO) {
            providers.requireProvider(KNIGAVUHE_SOURCE).homeSection(section)
        }

    suspend fun search(
        source: String,
        query: String,
        page: Int = 1,
        limit: Int = 50,
    ): List<LiveCatalogItemDto> {
        val provider = providers.requireProvider(source)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        return provider.search(
            normalizedQuery,
            page.coerceAtLeast(1),
            limit.coerceAtLeast(1),
        )
    }

    suspend fun authorBooks(
        authorId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> {
        val provider = providers.providerForEntityRef(authorId)
        require(provider != null && provider.canBrowseAuthor(authorId)) {
            "Android live parser cannot browse author $authorId"
        }
        return provider.authorBooks(authorId, page.coerceAtLeast(1), limit)
    }

    suspend fun narratorBooks(
        narratorId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> {
        val provider = providers.providerForEntityRef(narratorId)
        require(provider != null && provider.canBrowseNarrator(narratorId)) {
            "Android live parser cannot browse narrator $narratorId"
        }
        return provider.narratorBooks(narratorId, page.coerceAtLeast(1), limit)
    }

    suspend fun genreBooks(
        genreId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> {
        val provider = providers.providerForEntityRef(genreId)
        require(provider != null && provider.canBrowseGenre(genreId)) {
            "Android live parser cannot browse genre $genreId"
        }
        return provider.genreBooks(genreId, page.coerceAtLeast(1), limit)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(sourceSeriesCapabilityAllows(bookId)) {
            "Android live parser cannot load source series for $bookId"
        }
        val selected = providers.providerForBook(bookId)
        require(selected != null && selected.canLoadSourceSeries(bookId, provider)) {
            "Android live parser cannot load source series for $bookId"
        }
        return selected.sourceSeries(bookId, provider, page, limit)
    }

    suspend fun book(bookId: String): BookDetailDto {
        val provider = providers.providerForBook(bookId)
            ?: error("Android live parser cannot parse $bookId")
        return provider.book(bookId)
    }
}
