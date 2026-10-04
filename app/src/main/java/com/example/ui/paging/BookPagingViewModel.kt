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
import com.example.data.model.BookCardDto
import com.example.data.model.BookListResponse
import com.example.data.repository.AudiobookRepository
import com.example.data.settings.PlayerSettingsStore
import com.example.di.PagingAudiobookRepository
import com.example.data.source.StandaloneSourceRegistry
import com.example.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** One Paging 3 stream for every large book list. */
@HiltViewModel
class BookPagingViewModel @Inject constructor(
    @PagingAudiobookRepository
    private val repositoryProvider: Provider<AudiobookRepository>,
    private val cacheStore: LocalCacheStore,
    private val settingsStore: PlayerSettingsStore,
) : ViewModel() {
    private val flows = ConcurrentHashMap<String, Flow<PagingData<BookCardDto>>>()

    fun books(request: BookPageRequest): Flow<PagingData<BookCardDto>> {
        val key = request.flowKey
        flows[key]?.let { return it }
        val created = Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                // Live HTML providers may have a site page smaller than Abred's
                // logical page (Audioboo currently has about 27 cards). A large
                // prefetch distance made Paging request the next site page while
                // the user was still well inside the current one. Keep live
                // sources conservative while still caching their loaded pages.
                prefetchDistance = if (request.isLiveParserRead) LIVE_PREFETCH_DISTANCE else DEFAULT_PREFETCH_DISTANCE,
                initialLoadSize = PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                CachedBookPagingSource(
                    request = request,
                    // A repository is scoped to one PagingSource generation. Its
                    // catalog/search buffers therefore carry physical-page surplus
                    // across append loads but are discarded on Paging refresh.
                    repository = repositoryProvider.get(),
                    cacheStore = cacheStore,
                    // selfapk has no backend catalog fallback. Every successfully
                    // parsed page may be reused offline when caching is enabled.
                    cacheEnabled = { settingsStore.state.value.catalogCacheEnabled },
                )
            },
        ).flow.cachedIn(viewModelScope)
        flows[key] = created
        if (flows.size > MAX_REMEMBERED_FLOWS) {
            flows.keys.firstOrNull { it != key }?.let(flows::remove)
        }
        return created
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val DEFAULT_PREFETCH_DISTANCE = 10
        const val LIVE_PREFETCH_DISTANCE = 2
        const val MAX_REMEMBERED_FLOWS = 24
    }
}

sealed interface BookPageRequest {
    val storageKey: String
    val flowKey: String get() = storageKey

    data class Catalog(
        val query: String = "",
        val genreId: String? = null,
        val source: String? = null,
    ) : BookPageRequest {
        override val storageKey: String = "catalog|${query.trim()}|${genreId.orEmpty()}|${source.orEmpty()}"
    }

    data class Browse(
        val kind: String,
        val id: String,
        val excludeSource: String? = null,
        val seriesName: String? = null,
    ) : BookPageRequest {
        override val storageKey: String =
            "browse|$kind|$id|${excludeSource.orEmpty()}|${seriesName.orEmpty()}"
    }

    data class Similar(val bookId: String) : BookPageRequest {
        override val storageKey: String = "similar|$bookId"
    }
}

internal val BookPageRequest.isLiveParserRead: Boolean
    get() = this is BookPageRequest.Similar ||
        this is BookPageRequest.Catalog ||
        (this is BookPageRequest.Browse && when (kind) {
            "Search", "Source" -> true
            "Author", "Narrator", "Genre" -> id.hasActiveStandaloneSourcePrefix()
            else -> false
        })

private fun String.hasActiveStandaloneSourcePrefix(): Boolean {
    val source = substringBefore(':', missingDelimiterValue = "").trim().lowercase()
    return StandaloneSourceRegistry.isActive(source)
}

private class CachedBookPagingSource(
    private val request: BookPageRequest,
    private val repository: AudiobookRepository,
    private val cacheStore: LocalCacheStore,
    private val cacheEnabled: () -> Boolean,
) : PagingSource<Int, BookCardDto>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BookCardDto> {
        val requestedPage = (params.key ?: FIRST_PAGE).coerceAtLeast(FIRST_PAGE)
        return try {
            val freshResult = runCatchingCancellable { fetch(requestedPage) }
            val fresh = freshResult.getOrNull()
            if (fresh != null) {
                if (cacheEnabled()) {
                    try {
                        writeCached(requestedPage, fresh)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                    }
                    cacheDetailShells(fresh.items)
                }
            }
            val cached = if (fresh == null && cacheEnabled()) {
                runCatchingCancellable { readCached(requestedPage) }.getOrNull()
            } else null
            val response: BookListResponse = fresh ?: cached ?: run {
                val error = freshResult.exceptionOrNull()
                if (error is CancellationException) throw error
                return LoadResult.Error(error ?: IllegalStateException("Нет данных для офлайн-просмотра"))
            }
            val safePage = response.page.takeIf { it >= FIRST_PAGE } ?: requestedPage
            val safeLimit = response.limit.takeIf { it > 0 } ?: PAGE_SIZE
            LoadResult.Page(
                data = response.items,
                prevKey = if (safePage <= FIRST_PAGE) null else safePage - 1,
                nextKey = nextBookPageKey(
                    page = safePage,
                    limit = safeLimit,
                    total = response.total,
                    itemsCount = response.items.size,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, BookCardDto>): Int? {
        val anchorPosition = state.anchorPosition ?: return FIRST_PAGE
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return FIRST_PAGE
        return anchorPage.prevKey?.plus(1) ?: anchorPage.nextKey?.minus(1) ?: FIRST_PAGE
    }

    private suspend fun cacheDetailShells(books: List<BookCardDto>) {
        try {
            cacheStore.writeBookShellsIfAbsent(books)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    private suspend fun fetch(page: Int): BookListResponse = when (val value = request) {
        is BookPageRequest.Catalog -> {
            if (value.query.isBlank()) repository.catalog(page, PAGE_SIZE, value.genreId, value.source)
            else repository.search(value.query, page, PAGE_SIZE, value.source)
        }
        is BookPageRequest.Browse -> when (value.kind) {
            "Author" -> repository.authorBooks(value.id, page, PAGE_SIZE)
            "Narrator" -> repository.narratorBooks(value.id, page, PAGE_SIZE)
            "Genre" -> repository.genreBooks(value.id, page, PAGE_SIZE)
            "Search" -> repository.search(
                query = value.id,
                page = page,
                limit = PAGE_SIZE,
                source = null,
                excludeSource = value.excludeSource,
                seriesName = value.seriesName,
            )
            "Source" -> repository.catalog(page, PAGE_SIZE, genreId = null, source = value.id)
            else -> error("Unsupported browse paging kind: ${value.kind}")
        }
        is BookPageRequest.Similar -> LocalSimilarBooks.page(value.bookId, page, PAGE_SIZE)
    }

    private suspend fun readCached(page: Int): BookListResponse? = when (val value = request) {
        is BookPageRequest.Catalog -> cacheStore.readCatalog(value.query, value.genreId, value.source, page)
        is BookPageRequest.Browse -> cacheStore.readBrowse(value.kind, value.storageKey, page)
        is BookPageRequest.Similar -> cacheStore.readSimilarPage(value.bookId, page)
    }

    private suspend fun writeCached(page: Int, response: BookListResponse) {
        when (val value = request) {
            is BookPageRequest.Catalog -> cacheStore.writeCatalog(value.query, value.genreId, value.source, page, response)
            is BookPageRequest.Browse -> cacheStore.writeBrowse(value.kind, value.storageKey, page, response)
            is BookPageRequest.Similar -> cacheStore.writeSimilarPage(value.bookId, page, response)
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val FIRST_PAGE = 1
    }
}
