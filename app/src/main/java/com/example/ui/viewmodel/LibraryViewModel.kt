package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookCardDto
import com.example.data.model.BookmarkUiItem
import com.example.data.model.MySeriesDto
import com.example.data.repository.DownloadRepository
import com.example.data.repository.LibraryRepository
import com.example.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val favorites: List<BookCardDto> = emptyList(),
    val bookmarks: List<BookmarkUiItem> = emptyList(),
    val history: List<BookCardDto> = emptyList(),
    val series: List<MySeriesDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Screen-level owner for the local Library read state.
 *
 * Room/DownloadStore are authoritative in selfapk. Reading this screen never
 * requests the legacy backend; Room invalidation owns visible updates.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState(loading = true))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadBookEntity>>(emptyList())
    val downloads: StateFlow<List<DownloadBookEntity>> = _downloads.asStateFlow()

    init {
        observeRoom()
    }

    fun removeBookmark(bookmarkId: String) = mutate {
        repository.removeBookmark(bookmarkId)
    }

    fun removeHistory(bookId: String) = mutate {
        repository.removeHistory(bookId)
    }

    fun removeSeries(series: MySeriesDto) = mutate {
        repository.removeSeries(series)
    }

    fun pauseDownload(bookSourceId: String) = mutate {
        downloadRepository.pause(bookSourceId)
    }

    fun resumeDownload(bookSourceId: String) = mutate {
        downloadRepository.resume(bookSourceId)
    }

    fun retryDownload(bookSourceId: String) = mutate {
        downloadRepository.retry(bookSourceId)
    }

    fun removeDownload(bookSourceId: String) = mutate {
        downloadRepository.remove(bookSourceId)
    }

    private fun observeRoom() {
        viewModelScope.launch {
            repository.observe().collect { snapshot ->
                _state.value = LibraryUiState(
                    favorites = snapshot.favorites,
                    bookmarks = snapshot.bookmarks,
                    history = snapshot.history,
                    series = snapshot.series,
                    loading = false,
                    error = null,
                )
                _downloads.value = snapshot.downloads
            }
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { block() }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.message ?: "Не удалось обновить локальную библиотеку")
                    }
                }
        }
    }
}
