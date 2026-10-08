package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DownloadBookEntity
import com.example.data.local.HomeDiscoveryCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.HomeRailDto
import com.example.data.model.MySeriesDto
import com.example.data.repository.AudiobookRepository
import com.example.data.repository.LibraryRepository
import com.example.data.repository.LibrarySnapshot
import com.example.data.settings.HomePopularDefaultPeriod
import com.example.data.settings.PlayerSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOME_NEW_SECTION = "new"
private const val HOME_POPULAR_TODAY_SECTION = "popular:today"
private const val HOME_POPULAR_WEEK_SECTION = "popular:week"
private const val HOME_POPULAR_MONTH_SECTION = "popular:month"

internal const val HOME_CACHE_DAY_MS = 24L * 60L * 60L * 1_000L

internal fun homeDiscoveryTtlMs(days: Int): Long =
    days.coerceAtLeast(1).toLong() * HOME_CACHE_DAY_MS

internal fun shouldRefreshHomeDiscovery(
    savedAtMs: Long?,
    nowMs: Long,
    ttlMs: Long,
    force: Boolean = false,
): Boolean {
    if (force || savedAtMs == null) return true
    val age = (nowMs - savedAtMs).coerceAtLeast(0L)
    return age >= ttlMs
}

private fun HomePopularDefaultPeriod.toHomePopularPeriod(): HomePopularPeriod = when (this) {
    HomePopularDefaultPeriod.TODAY -> HomePopularPeriod.Today
    HomePopularDefaultPeriod.WEEK -> HomePopularPeriod.Week
    HomePopularDefaultPeriod.MONTH -> HomePopularPeriod.Month
}

enum class HomePopularPeriod(
    val label: String,
    internal val section: String,
) {
    Today("Сегодня", HOME_POPULAR_TODAY_SECTION),
    Week("Неделя", HOME_POPULAR_WEEK_SECTION),
    Month("Месяц", HOME_POPULAR_MONTH_SECTION),
}

/**
 * Home combines durable local listening state with two cache-first Knigavuhe
 * discovery shelves. Discovery never invalidates stale data just because its TTL
 * elapsed: TTL only decides when a background refresh may be attempted.
 */
data class HomeUiState(
    val loading: Boolean = false,
    val continueListening: List<BookCardDto> = emptyList(),
    val rails: List<HomeRailDto> = emptyList(),
    val mySeries: List<MySeriesDto> = emptyList(),
    val downloadedBookSourceIds: Map<String, String> = emptyMap(),
    val error: String? = null,
    val newBooks: List<BookCardDto> = emptyList(),
    val popularBooks: List<BookCardDto> = emptyList(),
    val popularPeriod: HomePopularPeriod = HomePopularPeriod.Week,
    val showNew: Boolean = true,
    val showPopular: Boolean = true,
    val showContinue: Boolean = true,
    val showDownloads: Boolean = true,
    val newRefreshing: Boolean = false,
    val popularRefreshing: Boolean = false,
    val newError: String? = null,
    val popularError: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val settingsStore: PlayerSettingsStore,
    application: Application,
) : ViewModel() {
    private val discoveryRepository = AudiobookRepository()
    private val discoveryCache = HomeDiscoveryCacheStore(application.applicationContext)
    private val initialSettings = settingsStore.state.value
    private var appliedDefaultPopularPeriod = initialSettings.homePopularDefaultPeriod
    private var appliedCacheDays = initialSettings.homeCacheDays

    private val _state = MutableStateFlow(
        HomeUiState(
            loading = true,
            popularPeriod = initialSettings.homePopularDefaultPeriod.toHomePopularPeriod(),
            showNew = initialSettings.homeShowNew,
            showPopular = initialSettings.homeShowPopular,
            showContinue = initialSettings.homeShowContinue,
            showDownloads = initialSettings.homeShowDownloads,
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var latestSnapshot: LibrarySnapshot? = null
    private var newJob: Job? = null
    private var popularJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.state.collect { settings ->
                val defaultPeriodChanged =
                    settings.homePopularDefaultPeriod != appliedDefaultPopularPeriod
                if (defaultPeriodChanged) {
                    appliedDefaultPopularPeriod = settings.homePopularDefaultPeriod
                }

                val previous = _state.value
                val nextPeriod = if (defaultPeriodChanged) {
                    settings.homePopularDefaultPeriod.toHomePopularPeriod()
                } else {
                    previous.popularPeriod
                }
                val showNewChanged = previous.showNew != settings.homeShowNew
                val showPopularChanged = previous.showPopular != settings.homeShowPopular
                val cacheDaysChanged = settings.homeCacheDays != appliedCacheDays
                if (cacheDaysChanged) appliedCacheDays = settings.homeCacheDays

                _state.update {
                    it.copy(
                        popularPeriod = nextPeriod,
                        showNew = settings.homeShowNew,
                        showPopular = settings.homeShowPopular,
                        showContinue = settings.homeShowContinue,
                        showDownloads = settings.homeShowDownloads,
                        newBooks = if (settings.homeShowNew) it.newBooks else emptyList(),
                        newRefreshing = if (settings.homeShowNew) it.newRefreshing else false,
                        newError = if (settings.homeShowNew) it.newError else null,
                        popularBooks = if (settings.homeShowPopular && !defaultPeriodChanged) {
                            it.popularBooks
                        } else {
                            emptyList()
                        },
                        popularRefreshing = if (settings.homeShowPopular) {
                            it.popularRefreshing
                        } else {
                            false
                        },
                        popularError = if (settings.homeShowPopular && !defaultPeriodChanged) {
                            it.popularError
                        } else {
                            null
                        },
                    )
                }

                if (!settings.homeShowNew) {
                    newJob?.cancel()
                } else if (showNewChanged || cacheDaysChanged) {
                    startNewRefresh(force = false)
                }

                if (!settings.homeShowPopular) {
                    popularJob?.cancel()
                } else if (showPopularChanged || defaultPeriodChanged || cacheDaysChanged) {
                    popularJob?.cancel()
                    startPopularRefresh(nextPeriod, force = false)
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.observe().collect { snapshot ->
                    latestSnapshot = snapshot
                    applyLocalSnapshot(snapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { current ->
                    current.copy(
                        loading = false,
                        error = error.message ?: "Не удалось прочитать локальную библиотеку",
                    )
                }
            }
        }
    }

    /** Called when Home becomes visible again. Fresh cache means no network call. */
    fun ensureDiscoveryFresh() {
        if (_state.value.showNew) startNewRefresh(force = false)
        if (_state.value.showPopular) {
            startPopularRefresh(_state.value.popularPeriod, force = false)
        }
    }

    /** Pull-to-refresh keeps current content visible while refreshing in place. */
    fun refresh() {
        latestSnapshot?.let(::applyLocalSnapshot)
        if (_state.value.showNew) startNewRefresh(force = true)
        if (_state.value.showPopular) {
            startPopularRefresh(_state.value.popularPeriod, force = true)
        }
    }

    fun selectPopularPeriod(period: HomePopularPeriod) {
        if (_state.value.popularPeriod == period) return
        _state.update {
            it.copy(
                popularPeriod = period,
                popularBooks = emptyList(),
                popularError = null,
            )
        }
        popularJob?.cancel()
        popularJob = viewModelScope.launch { loadPopular(period, force = false) }
    }

    private fun startNewRefresh(force: Boolean) {
        if (newJob?.isActive == true) {
            if (!force) return
            newJob?.cancel()
        }
        newJob = viewModelScope.launch { loadNew(force) }
    }

    private fun startPopularRefresh(period: HomePopularPeriod, force: Boolean) {
        if (popularJob?.isActive == true) {
            if (!force) return
            popularJob?.cancel()
        }
        popularJob = viewModelScope.launch { loadPopular(period, force) }
    }

    private suspend fun loadNew(force: Boolean) {
        if (!_state.value.showNew) {
            _state.update {
                it.copy(
                    newBooks = emptyList(),
                    newRefreshing = false,
                    newError = null,
                )
            }
            return
        }
        val cached = try {
            discoveryCache.read(HOME_NEW_SECTION)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (cached != null && cached.response.items.isNotEmpty()) {
            _state.update {
                it.copy(
                    newBooks = cached.response.items.take(HOME_DISCOVERY_LIMIT),
                    newError = null,
                )
            }
        }

        val ttlMs = homeDiscoveryTtlMs(settingsStore.state.value.homeCacheDays)
        if (!shouldRefreshHomeDiscovery(cached?.savedAtMs, System.currentTimeMillis(), ttlMs, force)) {
            return
        }

        _state.update { it.copy(newRefreshing = true, newError = null) }
        try {
            val fresh = withContext(Dispatchers.IO) {
                discoveryRepository.homeKnigavuhe(HOME_NEW_SECTION, HOME_DISCOVERY_LIMIT)
            }
            if (fresh.items.isNotEmpty()) {
                discoveryCache.write(HOME_NEW_SECTION, fresh)
                _state.update { it.copy(newBooks = fresh.items, newError = null) }
            } else if (cached == null) {
                _state.update { it.copy(newError = "Новинки пока недоступны") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Stale-while-revalidate: a network/parser failure never clears cached books.
            if (cached == null) {
                _state.update { it.copy(newError = "Не удалось загрузить новинки") }
            }
        } finally {
            _state.update { it.copy(newRefreshing = false) }
        }
    }

    private suspend fun loadPopular(period: HomePopularPeriod, force: Boolean) {
        if (!_state.value.showPopular) {
            _state.update { current ->
                if (current.popularPeriod != period) current else current.copy(
                    popularBooks = emptyList(),
                    popularRefreshing = false,
                    popularError = null,
                )
            }
            return
        }
        val section = period.section
        val cached = try {
            discoveryCache.read(section)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (_state.value.popularPeriod != period) return

        if (cached != null && cached.response.items.isNotEmpty()) {
            _state.update { current ->
                if (current.popularPeriod != period) current else current.copy(
                    popularBooks = cached.response.items.take(HOME_DISCOVERY_LIMIT),
                    popularError = null,
                )
            }
        }

        val ttlMs = homeDiscoveryTtlMs(settingsStore.state.value.homeCacheDays)
        if (!shouldRefreshHomeDiscovery(cached?.savedAtMs, System.currentTimeMillis(), ttlMs, force)) {
            return
        }

        _state.update { current ->
            if (current.popularPeriod != period) current else current.copy(popularRefreshing = true, popularError = null)
        }
        try {
            val fresh = withContext(Dispatchers.IO) {
                discoveryRepository.homeKnigavuhe(section, HOME_DISCOVERY_LIMIT)
            }
            if (fresh.items.isNotEmpty()) {
                discoveryCache.write(section, fresh)
                _state.update { current ->
                    if (current.popularPeriod != period) current else current.copy(
                        popularBooks = fresh.items,
                        popularError = null,
                    )
                }
            } else if (cached == null) {
                _state.update { current ->
                    if (current.popularPeriod != period) current else current.copy(popularError = "Популярное пока недоступно")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep stale cache indefinitely when offline or the provider is unavailable.
            if (cached == null) {
                _state.update { current ->
                    if (current.popularPeriod != period) current else current.copy(popularError = "Не удалось загрузить популярное")
                }
            }
        } finally {
            _state.update { current ->
                if (current.popularPeriod != period) current else current.copy(popularRefreshing = false)
            }
        }
    }

    private fun applyLocalSnapshot(snapshot: LibrarySnapshot) {
        val local = snapshot.toLocalHomeUiState()
        _state.update { current ->
            current.copy(
                loading = false,
                continueListening = local.continueListening,
                rails = local.rails,
                mySeries = local.mySeries,
                downloadedBookSourceIds = local.downloadedBookSourceIds,
                error = null,
            )
        }
    }

    private fun LibrarySnapshot.toLocalHomeUiState(): HomeUiState {
        val recent = history
            .distinctBy(BookCardDto::id)
            .take(HOME_RAIL_LIMIT)

        val continueListening = recent
            .filter { it.progressPercent < COMPLETED_PROGRESS_PERCENT }
            .take(HOME_RAIL_LIMIT)

        val visibleSeries = series
            .asSequence()
            .sortedWith(
                compareBy<MySeriesDto> { it.isCompleted }
                    .thenByDescending { it.lastActivityAt.toLongOrNull() ?: 0L }
            )
            .take(HOME_RAIL_LIMIT)
            .toList()

        val seriesFocusBooks = visibleSeries.mapNotNull { cycle ->
            (cycle.currentBook ?: cycle.nextBook)?.book
        }
        val knownCards = (history + favorites + seriesFocusBooks)
            .distinctBy(BookCardDto::id)
            .associateBy(BookCardDto::id)

        val completedDownloads = downloads
            .asSequence()
            .filter { it.state == "completed" && it.deletedAtMs == null }
            .toList()
        val downloadedBookSourceIds = linkedMapOf<String, String>().apply {
            completedDownloads.forEach { download -> putIfAbsent(download.bookId, download.bookSourceId) }
        }
        val downloadedBooks = completedDownloads
            .asSequence()
            .map { it.toHomeDownloadCard(knownCards[it.bookId]) }
            .distinctBy(BookCardDto::id)
            .take(HOME_RAIL_LIMIT)
            .toList()

        val rails = buildList {
            if (recent.isNotEmpty()) {
                add(localRail("recent", recent))
            }
            if (downloadedBooks.isNotEmpty()) {
                add(localRail("downloaded", downloadedBooks))
            }
        }

        return HomeUiState(
            loading = false,
            continueListening = continueListening,
            rails = rails,
            mySeries = visibleSeries,
            downloadedBookSourceIds = downloadedBookSourceIds,
            error = null,
        )
    }

    private fun DownloadBookEntity.toHomeDownloadCard(known: BookCardDto?): BookCardDto {
        val base = known ?: BookCardDto(
            id = bookId,
            title = title,
            coverUrl = coverUrl,
            sourceCodes = listOf(sourceCode),
            primarySource = sourceCode,
        )
        return base.copy(
            id = bookId,
            title = title.ifBlank { base.title },
            coverUrl = coverUrl.ifBlank { base.coverUrl },
            sourceCodes = listOf(sourceCode),
            primarySource = sourceCode,
        )
    }

    private fun localRail(
        kind: String,
        items: List<BookCardDto>,
    ) = HomeRailDto(
        kind = kind,
        items = items,
    )

    private companion object {
        const val HOME_RAIL_LIMIT = 10
        const val HOME_DISCOVERY_LIMIT = 12
        const val COMPLETED_PROGRESS_PERCENT = 99.5
    }
}