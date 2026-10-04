package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.StorageSettingsRepository
import com.example.data.repository.StorageSnapshot
import com.example.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUiState(
    val loading: Boolean = true,
    val clearing: Boolean = false,
    val roomCacheBytes: Long = 0L,
    val posterCacheBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val profileBytes: Long = 0L,
    val databaseBytes: Long = 0L,
    val favoriteBooks: Int = 0,
    val historyBooks: Int = 0,
    val progressBooks: Int = 0,
    val bookmarkBooks: Int = 0,
    val downloadedBooks: Int = 0,
    val downloadedBytes: Long = 0L,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class StorageSettingsViewModel @Inject constructor(
    private val repository: StorageSettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()
    val settings = repository.settings

    init {
        viewModelScope.launch {
            repository.observeSnapshot().collect { snapshot ->
                _state.update { current -> snapshot.toUiState(current, clearMessage = false) }
            }
        }
    }

    fun refresh() = viewModelScope.launch { refreshNow(clearMessage = true) }

    fun clearCatalogCache() {
        if (_state.value.clearing) return
        viewModelScope.launch {
            _state.update { it.copy(clearing = true, message = null, error = null) }
            runCatchingCancellable { repository.clearCatalogCache() }
                .onSuccess { compacted ->
                    refreshNow(clearMessage = false)
                    _state.update {
                        it.copy(
                            clearing = false,
                            message = if (compacted) "Кэш данных и обложек очищен, база уплотнена." else "Кэш очищен. SQLite не удалось уплотнить сразу.",
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    refreshNow(clearMessage = false)
                    _state.update { it.copy(clearing = false, error = error.message ?: "Не удалось очистить кэш") }
                }
        }
    }

    fun setCatalogCacheEnabled(value: Boolean) = viewModelScope.launch {
        if (settings.value.catalogCacheEnabled == value) return@launch
        runCatchingCancellable { repository.setCatalogCacheEnabled(value) }
            .onSuccess {
                refreshNow(clearMessage = false)
                _state.update { it.copy(message = if (value) "Локальный кэш включён" else "Локальный кэш выключен и очищен", error = null) }
            }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Не удалось изменить локальный кэш") } }
    }

    fun setDownloadWifiOnly(value: Boolean) = viewModelScope.launch {
        runCatchingCancellable { repository.setDownloadWifiOnly(value) }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Не удалось изменить сетевую политику загрузок") } }
    }

    private suspend fun refreshNow(clearMessage: Boolean) {
        runCatchingCancellable { repository.snapshot() }
            .onSuccess { snapshot -> _state.update { current -> snapshot.toUiState(current, clearMessage) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.message ?: "Не удалось прочитать локальное хранилище") } }
    }

    private fun StorageSnapshot.toUiState(current: StorageUiState, clearMessage: Boolean) = current.copy(
        loading = false,
        roomCacheBytes = roomCacheBytes,
        posterCacheBytes = posterCacheBytes,
        cacheBytes = cacheBytes,
        profileBytes = profileBytes,
        databaseBytes = databaseBytes,
        favoriteBooks = favoriteBooks,
        historyBooks = historyBooks,
        progressBooks = progressBooks,
        bookmarkBooks = bookmarkBooks,
        downloadedBooks = downloadedBooks,
        downloadedBytes = downloadedBytes,
        message = if (clearMessage) null else current.message,
        error = null,
    )
}
