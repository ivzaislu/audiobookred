package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.SeriesDetailDto
import com.example.data.parser.PreviewOnlyAudiobooBook
import com.example.data.parser.PreviewOnlyAudiopolkaBook
import com.example.data.parser.PreviewOnlyBazaKnigBook
import com.example.data.parser.PreviewOnlyKnigavuheBook
import com.example.data.parser.PreviewOnlyUknigBook
import com.example.data.parser.UnavailableMyAudiobooksBook
import com.example.data.repository.BookDetailRepository
import com.example.data.repository.BookDetailSnapshot
import com.example.data.repository.DownloadRepository
import com.example.data.torrserve.canResolveRuTrackerPlayback
import com.example.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: BookDetailDto? = null,
    val audioSeries: SeriesDetailDto? = null,
    val bookmarks: List<BookmarkDto> = emptyList(),
    val selectedDownload: DownloadBookEntity? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val alternateSearchQuery: String? = null,
    val alternateSearchExcludedSource: String? = null,
)

internal fun shouldSurfaceBookLoadFailure(
    book: BookDetailDto?,
    previewBook: BookDetailDto?,
): Boolean = previewBook != null || book == null || book.chapters.isEmpty()

internal fun canDownloadBook(book: BookDetailDto?): Boolean =
    book != null && (book.chapters.isNotEmpty() || canResolveRuTrackerPlayback(book))

data class BookDetailDownloadUiState(
    val bookSourceId: String = "",
    val busyBookSourceId: String = "",
    val message: String? = null,
    val error: String? = null,
)

/** Nav3-scoped owner for one Book Detail destination. */
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val repository: BookDetailRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BookDetailUiState())
    val state: StateFlow<BookDetailUiState> = _state.asStateFlow()

    private val _downloadUi = MutableStateFlow(BookDetailDownloadUiState())
    val downloadUi: StateFlow<BookDetailDownloadUiState> = _downloadUi.asStateFlow()

    private var routeId: String = ""
    private var downloaded: Boolean = false
    private var observeJob: Job? = null
    private var requestGeneration = 0L

    fun load(routeId: String, downloaded: Boolean) {
        if (routeId.isBlank()) return
        if (this.routeId == routeId && this.downloaded == downloaded && observeJob?.isActive == true) return

        this.routeId = routeId
        this.downloaded = downloaded
        val requestToken = ++requestGeneration
        _state.value = BookDetailUiState(loading = true)
        _downloadUi.value = BookDetailDownloadUiState()
        observeRoute(routeId, downloaded, requestToken)

        viewModelScope.launch {
            if (downloaded) {
                val result = runCatchingCancellable { repository.downloadedBook(routeId) }
                if (requestToken != requestGeneration) return@launch
                result.onFailure { error ->
                    if (shouldSurfaceBookLoadFailure(_state.value.book, previewBook = null)) {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = error.message ?: "Не удалось открыть скачанную книгу",
                            )
                        }
                    }
                }
                return@launch
            }

            val result = runCatchingCancellable { repository.refresh(routeId) }
            if (requestToken != requestGeneration) return@launch
            result
                .onSuccess { persistentBookId ->
                    if (persistentBookId.isNotBlank() && persistentBookId != this@BookDetailViewModel.routeId) {
                        this@BookDetailViewModel.routeId = persistentBookId
                        observeRoute(persistentBookId, downloaded = false, requestToken = requestToken)
                    }
                }
                .onFailure { error ->
                    val preview = previewBook(error)
                    // A lightweight detail shell may already be visible when parsing
                    // fails. Keep it visible, but explain why playback is unavailable.
                    // A complete cached book stays usable offline without a noisy error.
                    if (shouldSurfaceBookLoadFailure(_state.value.book, preview)) {
                        _state.update {
                            it.copy(
                                book = preview ?: it.book,
                                selectedDownload = if (preview != null) null else it.selectedDownload,
                                loading = false,
                                error = if (preview != null) {
                                    previewUnavailableMessage(error)
                                } else {
                                    error.message ?: "Не удалось загрузить аудио"
                                },
                                alternateSearchQuery = preview?.title?.takeIf(String::isNotBlank),
                                alternateSearchExcludedSource = preview?.selectedSource?.takeIf(String::isNotBlank)
                                    ?: preview?.primarySource?.takeIf(String::isNotBlank),
                            )
                        }
                    }
                }
        }
    }

    /** Refreshes visible data without clearing the current book from the screen. */
    fun refresh() {
        val id = routeId
        if (id.isBlank() || _state.value.refreshing) return
        val requestToken = requestGeneration
        val offlineDownload = downloaded
        _state.update {
            it.copy(
                refreshing = true,
                error = null,
                alternateSearchQuery = null,
                alternateSearchExcludedSource = null,
            )
        }

        viewModelScope.launch {
            if (offlineDownload) {
                val result = runCatchingCancellable { repository.downloadedBook(id) }
                if (requestToken != requestGeneration || id != routeId) return@launch
                result
                    .onSuccess { _state.update { it.copy(refreshing = false, error = null) } }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                refreshing = false,
                                error = error.message ?: "Не удалось обновить скачанную книгу",
                            )
                        }
                    }
                return@launch
            }

            val result = runCatchingCancellable { repository.refresh(id) }
            if (requestToken != requestGeneration || id != routeId) return@launch
            result
                .onSuccess { persistentBookId ->
                    if (persistentBookId.isNotBlank() && persistentBookId != routeId) {
                        routeId = persistentBookId
                        observeRoute(persistentBookId, downloaded = false, requestToken = requestToken)
                    }
                    _state.update { it.copy(refreshing = false) }
                }
                .onFailure { error ->
                    val preview = previewBook(error)
                    _state.update {
                        it.copy(
                            book = preview ?: it.book,
                            selectedDownload = if (preview != null) null else it.selectedDownload,
                            refreshing = false,
                            error = if (preview != null) {
                                previewUnavailableMessage(error)
                            } else {
                                error.message ?: "Не удалось обновить книгу"
                            },
                            alternateSearchQuery = preview?.title?.takeIf(String::isNotBlank),
                            alternateSearchExcludedSource = preview?.selectedSource?.takeIf(String::isNotBlank)
                                ?: preview?.primarySource?.takeIf(String::isNotBlank),
                        )
                    }
                }
        }
    }

    fun retry() {
        val id = routeId
        if (id.isBlank()) return
        val mode = downloaded
        routeId = ""
        load(id, mode)
    }

    fun selectSource(sourceCode: String) {
        val book = _state.value.book ?: return
        if (sourceCode.isBlank() || sourceCode == book.selectedSource) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    alternateSearchQuery = null,
                    alternateSearchExcludedSource = null,
                )
            }
            runCatchingCancellable { repository.selectSource(book.id, sourceCode) }
                .onSuccess { _state.update { current -> current.copy(loading = false) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Не удалось сменить источник",
                        )
                    }
                }
        }
    }

    fun toggleFavorite() {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            runCatchingCancellable { repository.toggleFavorite(book) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Не удалось обновить избранное") }
                }
        }
    }

    fun removeBookmark(bookmarkId: String) {
        val bookId = _state.value.book?.id ?: return
        viewModelScope.launch {
            runCatchingCancellable { repository.removeBookmark(bookId, bookmarkId) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Не удалось удалить закладку") }
                }
        }
    }

    fun startDownload(bookSourceId: String) {
        if (!currentBookHasAudio()) {
            rejectUnavailableDownload(bookSourceId)
            return
        }
        runDownloadAction(
            bookSourceId = bookSourceId,
            successMessage = "Книга добавлена в загрузки",
            failureMessage = "Не удалось начать загрузку",
        ) { downloadRepository.start(bookSourceId) }
    }

    fun resumeDownload(bookSourceId: String) {
        if (!currentBookHasAudio()) {
            rejectUnavailableDownload(bookSourceId)
            return
        }
        runDownloadAction(
            bookSourceId = bookSourceId,
            failureMessage = "Не удалось продолжить загрузку",
        ) { downloadRepository.resume(bookSourceId) }
    }

    fun retryDownload(bookSourceId: String) {
        if (!currentBookHasAudio()) {
            rejectUnavailableDownload(bookSourceId)
            return
        }
        runDownloadAction(
            bookSourceId = bookSourceId,
            successMessage = "Загрузка поставлена в очередь",
            failureMessage = "Не удалось повторить загрузку",
        ) { downloadRepository.retry(bookSourceId) }
    }

    private fun observeRoute(routeId: String, downloaded: Boolean, requestToken: Long) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.observe(routeId, downloaded).collect { snapshot ->
                if (requestToken != requestGeneration || snapshot == null) return@collect
                val previous = _state.value
                val next = snapshot.toUiState(refreshing = previous.refreshing)
                val keepAlternateSearch = !previous.alternateSearchQuery.isNullOrBlank() &&
                    !previous.alternateSearchExcludedSource.isNullOrBlank()
                _state.value = if (keepAlternateSearch) {
                    next.copy(
                        book = previous.book,
                        selectedDownload = previous.selectedDownload,
                        error = previous.error,
                        alternateSearchQuery = previous.alternateSearchQuery,
                        alternateSearchExcludedSource = previous.alternateSearchExcludedSource,
                    )
                } else {
                    next
                }
            }
        }
    }

    private fun currentBookHasAudio(): Boolean = canDownloadBook(_state.value.book)

    private fun rejectUnavailableDownload(bookSourceId: String) {
        _downloadUi.value = BookDetailDownloadUiState(
            bookSourceId = bookSourceId,
            error = "Аудио недоступно для скачивания в этом источнике",
        )
    }

    private fun runDownloadAction(
        bookSourceId: String,
        successMessage: String? = null,
        failureMessage: String,
        action: suspend () -> Unit,
    ) {
        if (bookSourceId.isBlank()) return
        _downloadUi.value = BookDetailDownloadUiState(
            bookSourceId = bookSourceId,
            busyBookSourceId = bookSourceId,
        )
        viewModelScope.launch {
            runCatchingCancellable { action() }
                .onSuccess {
                    _downloadUi.value = BookDetailDownloadUiState(
                        bookSourceId = bookSourceId,
                        message = successMessage,
                    )
                }
                .onFailure { error ->
                    _downloadUi.value = BookDetailDownloadUiState(
                        bookSourceId = bookSourceId,
                        error = error.message ?: failureMessage,
                    )
                }
        }
    }

    private fun previewBook(error: Throwable): BookDetailDto? = when (error) {
        is PreviewOnlyAudiopolkaBook -> error.previewBook
        is PreviewOnlyUknigBook -> error.previewBook
        is PreviewOnlyAudiobooBook -> error.previewBook
        is PreviewOnlyKnigavuheBook -> error.previewBook
        is PreviewOnlyBazaKnigBook -> error.previewBook
        is UnavailableMyAudiobooksBook -> error.unavailableBook?.copy(
            selectedBookSourceId = "",
            sourceVariants = emptyList(),
            chapters = emptyList(),
        )
        else -> null
    }

    private fun previewUnavailableMessage(error: Throwable): String = when (error) {
        is UnavailableMyAudiobooksBook -> "Аудио недоступно в этом источнике"
        else -> "Доступен только ознакомительный фрагмент"
    }

    private fun BookDetailSnapshot.toUiState(refreshing: Boolean = false): BookDetailUiState = BookDetailUiState(
        book = book,
        audioSeries = audioSeries,
        bookmarks = bookmarks,
        selectedDownload = selectedDownload,
        loading = false,
        refreshing = refreshing,
        error = null,
        alternateSearchQuery = null,
        alternateSearchExcludedSource = null,
    )
}
