package com.example.ui.paging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import com.example.data.local.LocalCacheStore
import com.example.data.local.SeriesPageCacheStore
import com.example.data.model.BookDetailDto
import com.example.data.model.SeriesDetailDto
import com.example.data.model.SeriesEntryDto
import com.example.data.repository.AudiobookRepository
import com.example.data.settings.PlayerSettingsStore
import com.example.di.PagingAudiobookRepository
import com.example.data.source.StandaloneSourceRegistry
import com.example.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class SeriesSwitchOption(
    val request: SeriesPageRequest,
    val typeLabel: String,
    val title: String,
)

@HiltViewModel
class SeriesPagingViewModel @Inject constructor(
    @PagingAudiobookRepository repositoryProvider: Provider<AudiobookRepository>,
    private val legacyCache: LocalCacheStore,
    private val pageCache: SeriesPageCacheStore,
    private val settingsStore: PlayerSettingsStore,
) : ViewModel() {
    // Preserve the old VM-scoped repository lifetime. Unlike Book paging, Series
    // shares one repository across its PagingSource refreshes and switch-option reads.
    private val repository = repositoryProvider.get()
    private val _metadata = MutableStateFlow<SeriesDetailDto?>(null)
    private val _switchOptions = MutableStateFlow<List<SeriesSwitchOption>>(emptyList())
    private var switchOptionsBookId: String? = null

    val metadata: StateFlow<SeriesDetailDto?> = _metadata
    internal val switchOptions: StateFlow<List<SeriesSwitchOption>> = _switchOptions

    fun loadSwitchOptions(bookId: String?, force: Boolean = false) {
        val cleanBookId = bookId?.trim().orEmpty()
        if (cleanBookId.isBlank()) {
            switchOptionsBookId = null
            _switchOptions.value = emptyList()
            return
        }
        if (!force && switchOptionsBookId == cleanBookId && _switchOptions.value.isNotEmpty()) return

        switchOptionsBookId = cleanBookId
        viewModelScope.launch {
            val cachedBook = if (force) {
                null
            } else {
                try {
                    legacyCache.readBook(cleanBookId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }

            val book = if (cachedBook != null) {
                cachedBook
            } else {
                try {
                    repository.book(cleanBookId).also { fresh ->
                        runCatchingCancellable { legacyCache.writeBook(fresh) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }

            if (switchOptionsBookId != cleanBookId) return@launch
            val options = book?.let(::seriesSwitchOptions).orEmpty()
            if (force) {
                _switchOptions.value = options
            } else {
                mergeSwitchOptions(options)
            }
        }
    }

    fun refreshSwitchOptions() {
        val bookId = switchOptionsBookId ?: return
        loadSwitchOptions(bookId, force = true)
    }

    fun clearMetadata() {
        _metadata.value = null
    }

    fun entries(request: SeriesPageRequest): Flow<PagingData<SeriesEntryDto>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            initialLoadSize = PAGE_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            CachedSeriesPagingSource(
                request = request,
                repository = repository,
                legacyCache = legacyCache,
                pageCache = pageCache,
                cacheEnabled = { settingsStore.state.value.catalogCacheEnabled },
                onMetadata = { detail ->
                    _metadata.value = detail.copy(books = emptyList(), entries = emptyList())
                    mergeSwitchOptions(listOf(currentSeriesSwitchOption(request, detail)))

                    if (switchOptionsBookId == null) {
                        val inferredBookId = detail.entries
                            .firstNotNullOfOrNull { it.book?.id?.takeIf(String::isNotBlank) }
                            ?: detail.books.firstOrNull()?.id?.takeIf(String::isNotBlank)
                        if (inferredBookId != null) loadSwitchOptions(inferredBookId)
                    }
                },
            )
        },
    ).flow.cachedIn(viewModelScope)

    private fun mergeSwitchOptions(options: List<SeriesSwitchOption>) {
        if (options.isEmpty()) return
        _switchOptions.value = (_switchOptions.value + options)
            // Canonical/Audio routes are retained only so an old saved back
            // stack can be restored. The standalone UI must never surface them
            // as a new navigation option because only Source routes are parser-backed.
            .filter { it.request is SeriesPageRequest.Source && it.title.isNotBlank() }
            .distinctBy { it.request.storageKey }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val PREFETCH_DISTANCE = 8
    }
}

/**
 * Build only series routes that the on-device parsers can actually load.
 * Legacy canonical/audio memberships can remain in cached DTOs after upgrade,
 * but exposing them would create a dead backend-era destination.
 */
internal fun seriesSwitchOptions(book: BookDetailDto): List<SeriesSwitchOption> {
    val sourceAudio = book.audioSeries
        .asSequence()
        .filter { series ->
            series.provider.isNotBlank() &&
                series.name.isNotBlank() &&
                isStandaloneSourceProvider(series.provider)
        }
        .sortedWith(
            compareByDescending<com.example.data.model.AudioSeriesBriefDto> {
                it.provider.equals(book.selectedSource, ignoreCase = true)
            }.thenBy { it.sourceName.ifBlank { it.provider }.lowercase() }
                .thenBy { it.name.lowercase() }
        )
        .mapNotNull { series ->
            val provider = series.provider.lowercase()
            val seedBookId = seriesSeedBookId(book, provider) ?: return@mapNotNull null
            SeriesSwitchOption(
                request = SeriesPageRequest.Source(seedBookId, provider),
                typeLabel = series.sourceName.ifBlank { sourceProviderLabel(series.provider) },
                title = series.name,
            )
        }
        .toList()

    val explicitProviders = sourceAudio.mapNotNull { option ->
        (option.request as? SeriesPageRequest.Source)?.provider?.lowercase()
    }.toSet()

    val sourceVariants = book.sourceVariants
        .asSequence()
        .filter { variant ->
            variant.sourceCode.isNotBlank() &&
                variant.seriesName.isNotBlank() &&
                isStandaloneSourceProvider(variant.sourceCode) &&
                variant.sourceCode.lowercase() !in explicitProviders
        }
        .sortedByDescending { it.sourceCode.equals(book.selectedSource, ignoreCase = true) }
        .mapNotNull { variant ->
            val provider = variant.sourceCode.lowercase()
            val seedBookId = seriesSeedBookIdFromVariant(variant.bookSourceId, provider)
                ?: return@mapNotNull null
            SeriesSwitchOption(
                request = SeriesPageRequest.Source(seedBookId, provider),
                typeLabel = variant.sourceName.ifBlank { sourceProviderLabel(variant.sourceCode) },
                title = variant.seriesName,
            )
        }
        .toMutableList()

    if (
        book.selectedSource.isNotBlank() &&
        isStandaloneSourceProvider(book.selectedSource) &&
        book.selectedSource.lowercase() !in explicitProviders &&
        book.sourceSeriesName.isNotBlank() &&
        sourceVariants.none { option ->
            val sourceRequest = option.request as? SeriesPageRequest.Source
            sourceRequest?.provider.equals(book.selectedSource, ignoreCase = true)
        }
    ) {
        val provider = book.selectedSource.lowercase()
        seriesSeedBookId(book, provider)?.let { seedBookId ->
            sourceVariants += SeriesSwitchOption(
                request = SeriesPageRequest.Source(seedBookId, provider),
                typeLabel = sourceProviderLabel(book.selectedSource),
                title = book.sourceSeriesName,
            )
        }
    }

    return (sourceAudio + sourceVariants)
        .filter { it.title.isNotBlank() }
        .distinctBy { it.request.storageKey }
}


internal fun seriesSeedBookId(
    book: BookDetailDto,
    provider: String,
): String? {
    val normalizedProvider = provider.trim().lowercase()
    if (normalizedProvider.isBlank()) return null

    val directId = book.id.trim()
    if (directId.substringBefore(':').equals(normalizedProvider, ignoreCase = true)) {
        return directId
    }

    book.sourceVariants
        .firstOrNull { it.sourceCode.equals(normalizedProvider, ignoreCase = true) }
        ?.bookSourceId
        ?.let { seriesSeedBookIdFromVariant(it, normalizedProvider) }
        ?.let { return it }

    if (book.selectedSource.equals(normalizedProvider, ignoreCase = true)) {
        seriesSeedBookIdFromVariant(book.selectedBookSourceId, normalizedProvider)?.let { return it }
    }

    return null
}

internal fun seriesSeedBookIdFromVariant(
    bookSourceId: String,
    provider: String,
): String? {
    val normalizedProvider = provider.trim().lowercase()
    val clean = bookSourceId.trim()
    if (normalizedProvider.isBlank() || clean.isBlank()) return null

    val livePrefix = "live:$normalizedProvider:"
    if (clean.startsWith(livePrefix, ignoreCase = true)) {
        val externalId = clean.substring(livePrefix.length)
        return externalId.takeIf(String::isNotBlank)?.let { "$normalizedProvider:$it" }
    }

    val directPrefix = "$normalizedProvider:"
    return clean
        .takeIf { it.startsWith(directPrefix, ignoreCase = true) && it.length > directPrefix.length }
        ?.let { "$normalizedProvider:${it.substring(directPrefix.length)}" }
}

private fun currentSeriesSwitchOption(
    request: SeriesPageRequest,
    detail: SeriesDetailDto,
): SeriesSwitchOption = SeriesSwitchOption(
    request = request,
    typeLabel = when (request) {
        is SeriesPageRequest.Canonical -> canonicalProviderLabel(detail.provider)
        is SeriesPageRequest.Source,
        is SeriesPageRequest.Audio -> sourceProviderLabel(detail.provider)
    },
    title = detail.name,
)

private fun canonicalProviderLabel(provider: String): String = when (provider.lowercase()) {
    "fantlab" -> "FantLab"
    "litres" -> "LitRes"
    "fantlab/litres", "litres/fantlab" -> "FantLab/LitRes"
    "abred" -> "Литературный цикл"
    else -> provider.ifBlank { "Литературный цикл" }
}

private fun sourceProviderLabel(provider: String): String =
    StandaloneSourceRegistry.displayNameOrNull(provider) ?: provider.ifBlank { "Источник" }

private fun isStandaloneSourceProvider(provider: String): Boolean =
    StandaloneSourceRegistry.supportsSeries(provider)

sealed interface SeriesPageRequest {
    val storageKey: String

    /** Restored legacy route only; never emitted by standalone switch options. */
    data class Canonical(val seriesId: String) : SeriesPageRequest {
        override val storageKey: String = LocalCacheStore.canonicalSeriesKey(seriesId)
    }

    data class Source(
        val bookId: String,
        val provider: String? = null,
    ) : SeriesPageRequest {
        override val storageKey: String = LocalCacheStore.sourceSeriesKey(bookId, provider)
    }

    /** Restored legacy route only; never emitted by standalone switch options. */
    data class Audio(val seriesId: String) : SeriesPageRequest {
        override val storageKey: String = LocalCacheStore.audioSeriesKey(seriesId)
    }
}

private class CachedSeriesPagingSource(
    private val request: SeriesPageRequest,
    private val repository: AudiobookRepository,
    private val legacyCache: LocalCacheStore,
    private val pageCache: SeriesPageCacheStore,
    private val cacheEnabled: () -> Boolean,
    private val onMetadata: (SeriesDetailDto) -> Unit,
) : PagingSource<Int, SeriesEntryDto>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SeriesEntryDto> {
        val page = (params.key ?: FIRST_PAGE).coerceAtLeast(FIRST_PAGE)
        val limit = params.loadSize.coerceIn(1, MAX_PAGE_SIZE)
        return try {
            var fetchError: Exception? = null
            val fresh = try {
                fetch(page, limit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fetchError = error
                null
            }
            if (fresh != null && cacheEnabled()) {
                runCatchingCancellable { pageCache.write(request.storageKey, page, fresh) }
            }

            val cached = if (fresh == null && cacheEnabled()) {
                runCatchingCancellable { pageCache.read(request.storageKey, page) }.getOrNull()
                    ?: legacyPage(page, limit)
            } else {
                null
            }
            val detail = fresh ?: cached
                ?: return LoadResult.Error(fetchError ?: IllegalStateException("Не удалось загрузить цикл"))
            val entries = normalizedEntries(detail)

            onMetadata(detail)

            val nextKey = nextSeriesPageKey(
                page = page,
                requestedLimit = limit,
                provider = detail.provider,
                totalCount = detail.totalCount,
                entriesCount = entries.size,
            )
            LoadResult.Page(
                data = entries,
                prevKey = if (page == FIRST_PAGE) null else page - 1,
                nextKey = nextKey,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SeriesEntryDto>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
    }

    private suspend fun fetch(page: Int, limit: Int): SeriesDetailDto = when (request) {
        is SeriesPageRequest.Source -> repository.sourceSeriesPage(request.bookId, request.provider, page, limit)
        is SeriesPageRequest.Canonical -> error(
            "Старый литературный цикл доступен только из локального кэша. Откройте цикл из карточки книги."
        )
        is SeriesPageRequest.Audio -> error(
            "Старая аудиосерия доступна только из локального кэша. Откройте цикл из карточки книги."
        )
    }

    private suspend fun legacyPage(page: Int, limit: Int): SeriesDetailDto? {
        val legacy = legacyCache.readSeries(request.storageKey) ?: return null
        val allEntries = normalizedEntries(legacy)
        val from = ((page - 1) * limit).coerceAtMost(allEntries.size)
        val to = (from + limit).coerceAtMost(allEntries.size)
        val slice = allEntries.subList(from, to)
        return legacy.copy(
            totalCount = legacy.totalCount.coerceAtLeast(allEntries.size),
            books = slice.mapNotNull(SeriesEntryDto::book),
            entries = slice,
        )
    }

    private fun normalizedEntries(detail: SeriesDetailDto): List<SeriesEntryDto> {
        if (detail.entries.isNotEmpty()) return detail.entries
        return detail.books.map { book ->
            SeriesEntryDto(
                externalWorkId = book.id,
                title = book.title,
                authors = book.authors.map { it.name },
                position = book.sourceSeriesPosition?.toDouble(),
                available = true,
                book = book,
            )
        }
    }

    private companion object {
        const val FIRST_PAGE = 1
        const val MAX_PAGE_SIZE = 100
    }
}
