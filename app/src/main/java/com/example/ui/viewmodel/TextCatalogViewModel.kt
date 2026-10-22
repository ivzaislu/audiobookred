package com.example.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.data.model.BookCardDto
import com.example.ui.paging.TextCatalogPagingSource
import com.example.ui.paging.catalogCard
import com.example.ui.paging.mergeTextCatalogCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.textsource.RemoteTextBook
import com.example.data.textsource.RemoteTextBookRepository
import com.example.data.textsource.RemoteTextDownload
import com.example.data.textsource.RemoteTextPage
import com.example.data.textsource.RemoteTextSourceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TextCatalogState(
    val page: RemoteTextPage? = null,
    val destinationKey: String = "",
    val query: String = "",
    val isBookDestination: Boolean = false,
    val isCollection: Boolean = false,
    val navigationKeys: List<String> = emptyList(),
    val book: RemoteTextBook? = null,
    val loading: Boolean = false,
    val importing: Boolean = false,
    val sourceCode: String = "",
    val sourceName: String = "",
    val sources: List<RemoteTextSourceInfo> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val localBookId: String? = null,
    val openedBookId: String? = null,
    val canGoBack: Boolean = false,
) {
    val busy: Boolean get() = importing
}

@HiltViewModel
internal class TextCatalogViewModel @Inject constructor(
    private val repository: RemoteTextBookRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val registry = repository.registry
    private val initialSource = savedState.get<String>("textSource")?.let { code ->
        registry.sources.firstOrNull { it.code == code }?.let { registry.provider(it.code) }
    } ?: registry.default
    private fun root(code: String) = TextCatalogDestination(code, registry.provider(code).catalogUrl)
    private val history = savedState.get<ArrayList<String>>("textNavigation").orEmpty()
        .mapNotNull { TextCatalogDestination.decode(it) }.filter { destination ->
            runCatching {
                val source = registry.provider(destination.sourceCode)
                if (destination.bookId != null) source.bookUrl(destination.bookId) == destination.url else source.isPageUrl(destination.url)
            }.getOrDefault(false)
        }.takeLast(32).toMutableList().apply { if (isEmpty()) add(root(initialSource.code)) }
    private data class PagingEntry(val scope: CoroutineScope, val flow: Flow<PagingData<BookCardDto>>)
    private val pagingEntries = LinkedHashMap<String, PagingEntry>(8, 0.75f, true)
    private val pageTitles = mutableMapOf<String, String>()
    private val mutableState = MutableStateFlow(TextCatalogState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var transferJob: Job? = null
    private var request = 0L
    private var transferGeneration = 0L

    init { load() }

    fun selectSource(code: String) {
        if (state.value.busy || code == state.value.sourceCode) return
        registry.provider(code)
        savedState["textSource"] = code
        history.clear()
        history.add(root(code))
        load()
    }
    fun search(input: String) {
        if (state.value.busy) return
        val value = input.trim().take(300)
        val source = registry.provider(state.value.sourceCode)
        val linkedSource = registry.forBookUrl(value)
        when {
            linkedSource != null -> {
                val id = requireNotNull(linkedSource.bookId(value))
                navigate(TextCatalogDestination(linkedSource.code, linkedSource.bookUrl(id), id))
            }
            value.isEmpty() -> { history.clear(); history.add(root(source.code)); load() }
            value.contains("://") -> mutableState.update { it.copy(error = "Ссылка не поддерживается доступными источниками. Введите название книги или автора.") }
            else -> navigate(TextCatalogDestination(source.code, source.searchUrl(value), query = value))
        }
    }
    fun openBook(id: String) {
        val source = registry.provider(state.value.sourceCode)
        navigate(TextCatalogDestination(source.code, source.bookUrl(id), id))
    }
    fun collection(url: String) {
        val source = registry.provider(state.value.sourceCode)
        if (source.isPageUrl(url)) navigate(TextCatalogDestination(source.code, url))
    }
    fun back(): Boolean {
        if (state.value.busy) { cancelImport(); return true }
        if (history.size <= 1) return false
        history.removeAt(history.lastIndex)
        load()
        return true
    }
    fun retry() { if (!state.value.busy) load() }
    fun consumeOpened() { mutableState.update { it.copy(openedBookId = null) } }
    fun catalogRootKey(sourceCode: String): String = root(sourceCode).key
    suspend fun listingDetails(card: BookCardDto): BookCardDto {
        val source = registry.provider(card.primarySource)
        return mergeTextCatalogCard(card, source.book(card.id).catalogCard(source.code))
    }

    fun importAndOpen(option: RemoteTextDownload? = null) {
        val book = state.value.book ?: return
        if (state.value.busy || state.value.loading) return
        state.value.localBookId?.let { id ->
            mutableState.update { it.copy(openedBookId = id) }
            return
        }
        val sourceCode = state.value.sourceCode
        mutableState.update { it.copy(importing = true, error = null, notice = null) }
        val generation = request
        val transfer = ++transferGeneration
        transferJob = viewModelScope.launch {
            try {
                val result = repository.importBook(book, sourceCode, option)
                if (request == generation && transferGeneration == transfer) mutableState.update {
                    it.copy(localBookId = result.bookId, notice = "Книга добавлена в библиотеку.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == generation && transferGeneration == transfer) mutableState.update { it.copy(error = error.message ?: "Не удалось добавить книгу.") }
            } finally {
                if (transferGeneration == transfer) mutableState.update { it.copy(importing = false) }
            }
        }
    }

    fun cancelImport() {
        transferGeneration++
        transferJob?.cancel()
        mutableState.update { it.copy(importing = false, openedBookId = null) }
    }
    private fun navigate(destination: TextCatalogDestination) {
        if (state.value.busy) return
        if (history.last() != destination) history.add(destination)
        while (history.size > 32) history.removeAt(1)
        savedState["textSource"] = destination.sourceCode
        load()
    }
    fun catalogBooks(): Flow<PagingData<BookCardDto>> {
        val destination = history.last()
        check(destination.bookId == null)
        pagingEntries[destination.key]?.let { return it.flow }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val source = registry.provider(destination.sourceCode)
        val flow = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 5,
            enablePlaceholders = false)) {
            TextCatalogPagingSource(source, destination.url) { page ->
                viewModelScope.launch {
                    if (destination.key !in pagingEntries) return@launch
                    pageTitles[destination.key] = page.title
                    if (state.value.destinationKey == destination.key) mutableState.update {
                        it.copy(page = page.copy(items = emptyList(), nextUrl = null))
                    }
                }
            }
        }.flow.cachedIn(scope)
        pagingEntries[destination.key] = PagingEntry(scope, flow)
        while (pagingEntries.size > 8) {
            val oldest = pagingEntries.keys.first()
            pagingEntries.remove(oldest)?.scope?.cancel()
            pageTitles.remove(oldest)
        }
        return flow
    }

    private fun load() {
        savedState["textNavigation"] = ArrayList(history.map { it.encode() })
        val destination = history.last()
        val source = registry.provider(destination.sourceCode)
        savedState["textSource"] = source.code
        val generation = ++request
        job?.cancel()
        mutableState.value = TextCatalogState(loading = destination.bookId != null, canGoBack = history.size > 1,
            destinationKey = destination.key, query = destination.query, isBookDestination = destination.bookId != null,
            navigationKeys = history.map { it.key }.distinct(),
            isCollection = destination.bookId == null && destination.query.isEmpty() && destination.url != source.catalogUrl,
            page = pageTitles[destination.key]?.let { RemoteTextPage(it, emptyList(), null) },
            sourceCode = source.code, sourceName = source.name, sources = registry.sources)
        if (destination.bookId == null) return
        job = viewModelScope.launch {
            try {
                if (destination.bookId != null) {
                    val book = source.book(destination.bookId)
                    val local = repository.localBookId(book.id, source.code)
                    if (local != null) repository.rememberCatalog(book, source.code, local)
                    if (generation == request) mutableState.update { it.copy(book = book, localBookId = local, loading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == request) mutableState.update { it.copy(loading = false, error = error.message ?: "Источник недоступен.") }
            }
        }
    }
}
