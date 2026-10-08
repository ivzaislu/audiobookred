package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.GenreDto
import com.example.data.parser.AndroidLiveParserLocator
import com.example.data.parser.RUTRACKER_SOURCE
import com.example.data.settings.SourceAvailabilityStore
import com.example.data.source.StandaloneSourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun allCatalogSourceCodes(): Set<String> =
    StandaloneSourceRegistry.activeSources.mapTo(linkedSetOf()) { it.code }

private fun firstEnabledCatalogSource(enabledSources: Set<String>): String =
    StandaloneSourceRegistry.activeSources
        .firstOrNull { it.code in enabledSources }
        ?.code
        ?: StandaloneSourceRegistry.DEFAULT_SOURCE_CODE

/** Catalog request state backed by on-device Abred providers. */
data class CatalogUiState(
    val genres: List<GenreDto> = emptyList(),
    val selectedGenreId: String? = null,
    val selectedSource: String = StandaloneSourceRegistry.DEFAULT_SOURCE_CODE,
    val enabledSources: Set<String> = allCatalogSourceCodes(),
    val query: String = "",
    val searchAllSources: Boolean = true,
) {
    /**
     * "Все источники" affects search only. With an empty query the catalog still
     * belongs to the selected provider.
     */
    val requestSource: String?
        get() = selectedSource.takeUnless { query.isNotBlank() && searchAllSources }

    val selectedSourceIsApplied: Boolean
        get() = requestSource != null
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val sourceAvailabilityStore: SourceAvailabilityStore,
) : ViewModel() {
    private val initialEnabledSources = sourceAvailabilityStore.enabled.value
    private val _state = MutableStateFlow(
        CatalogUiState(
            selectedSource = StandaloneSourceRegistry.DEFAULT_SOURCE_CODE
                .takeIf { it in initialEnabledSources }
                ?: firstEnabledCatalogSource(initialEnabledSources),
            enabledSources = initialEnabledSources,
        )
    )
    private var ruTrackerGenreBeforeSearch: String? = null
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sourceAvailabilityStore.enabled.collect { enabledSources ->
                var sourceChanged = false
                _state.update { current ->
                    val selectedSource = current.selectedSource
                        .takeIf { it in enabledSources }
                        ?: firstEnabledCatalogSource(enabledSources)
                    sourceChanged = selectedSource != current.selectedSource
                    current.copy(
                        selectedSource = selectedSource,
                        enabledSources = enabledSources,
                        selectedGenreId = if (sourceChanged) null else current.selectedGenreId,
                        genres = if (sourceChanged) emptyList() else current.genres,
                    )
                }
                if (sourceChanged) {
                    ruTrackerGenreBeforeSearch = null
                    refreshMetadata(
                        selectFirstGenre = _state.value.selectedSource == RUTRACKER_SOURCE,
                    )
                }
            }
        }
        refreshMetadata()
    }

    fun refreshMetadata() {
        refreshMetadata(selectFirstGenre = false)
    }

    private fun refreshMetadata(selectFirstGenre: Boolean) {
        val source = _state.value.selectedSource
            .takeIf { it in _state.value.enabledSources }
            ?.takeIf(StandaloneSourceRegistry::supportsGenres)
            ?: return
        viewModelScope.launch {
            val genres = try {
                AndroidLiveParserLocator.instanceOrNull()?.genres(source).orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { current ->
                if (current.selectedSource != source || source !in current.enabledSources) {
                    return@update current
                }

                val firstGenreId = if (
                    selectFirstGenre &&
                    source == RUTRACKER_SOURCE &&
                    current.selectedGenreId == null &&
                    current.query.isBlank()
                ) {
                    genres.firstOrNull()?.id
                } else {
                    current.selectedGenreId
                }

                current.copy(
                    genres = genres,
                    selectedGenreId = firstGenreId,
                )
            }
        }
    }

    fun search(query: String) {
        val clean = query.trim()
        val current = _state.value
        if (
            clean.isNotBlank() &&
            current.selectedSource == RUTRACKER_SOURCE &&
            current.query.isBlank()
        ) {
            ruTrackerGenreBeforeSearch = current.selectedGenreId
        }
        _state.update { it.copy(query = clean, selectedGenreId = null) }
    }

    fun clearSearch() {
        _state.update { current ->
            val restoredGenre = if (
                current.selectedSource == RUTRACKER_SOURCE &&
                current.selectedGenreId == null
            ) {
                ruTrackerGenreBeforeSearch
                    ?.takeIf { saved -> current.genres.any { it.id == saved } }
                    ?: current.genres.firstOrNull()?.id
            } else {
                current.selectedGenreId
            }
            current.copy(
                query = "",
                selectedGenreId = restoredGenre,
            )
        }
        ruTrackerGenreBeforeSearch = null
        if (
            _state.value.selectedSource == RUTRACKER_SOURCE &&
            _state.value.selectedGenreId == null &&
            _state.value.genres.isEmpty()
        ) {
            refreshMetadata(selectFirstGenre = true)
        }
    }

    fun selectGenre(genreId: String?) {
        ruTrackerGenreBeforeSearch = null
        if (genreId == null) {
            _state.update { it.copy(selectedGenreId = null, query = "") }
            return
        }
        val source = StandaloneSourceRegistry
            .normalize(genreId.substringBefore(':'))
            .takeIf(StandaloneSourceRegistry::supportsGenres)
            ?.takeIf { it in _state.value.enabledSources }
            ?: return
        _state.update {
            it.copy(
                selectedGenreId = genreId,
                selectedSource = source,
                query = "",
            )
        }
        if (_state.value.genres.isEmpty()) refreshMetadata()
    }

    fun setSearchAllSources(enabled: Boolean) {
        _state.update { it.copy(searchAllSources = enabled) }
    }

    fun selectSource(source: String) {
        ruTrackerGenreBeforeSearch = null
        val normalized = StandaloneSourceRegistry.normalize(source)
            .takeIf(String::isNotBlank)
            ?.takeIf(StandaloneSourceRegistry::isActive)
            ?.takeIf { it in _state.value.enabledSources }
            ?: return
        _state.update {
            val sourceChanged = normalized != it.selectedSource
            it.copy(
                selectedSource = normalized,
                selectedGenreId = if (sourceChanged) null else it.selectedGenreId,
                genres = if (sourceChanged) emptyList() else it.genres,
            )
        }
        if (StandaloneSourceRegistry.supportsGenres(normalized) && _state.value.genres.isEmpty()) {
            refreshMetadata(selectFirstGenre = normalized == RUTRACKER_SOURCE)
        }
    }
}
