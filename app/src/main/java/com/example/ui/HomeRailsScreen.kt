package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.HomeViewModel

/**
 * Cache-first Home: local listening state stays useful offline while Knigavuhe
 * discovery shelves make a fresh install useful before the local library grows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreparedHomeScreen(
    listState: LazyListState,
    onBook: (String) -> Unit,
    onContinueBook: (String) -> Unit,
    onDownloadedBook: (String) -> Unit,
    onSeries: (MySeriesDto) -> Unit,
    onSearch: (String) -> Unit,
    showProgressPercent: Boolean,
) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val state by homeViewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(homeViewModel) {
        homeViewModel.ensureDiscoveryFresh()
    }

    val submitSearch = {
        val clean = query.trim()
        if (clean.isNotEmpty()) onSearch(clean)
    }
    val continueBook = state.continueListening.firstOrNull()
    val nextSeries = state.mySeries.firstOrNull {
        !it.isCompleted && (it.nextBook != null || it.currentBook != null)
    }
    val recentRail = state.rails.firstOrNull { it.kind == "recent" }
    val downloadedRail = state.rails.firstOrNull { it.kind == "downloaded" }
    val hasVisibleContent =
        continueBook != null ||
            state.newBooks.isNotEmpty() ||
            state.popularBooks.isNotEmpty() ||
            nextSeries != null ||
            recentRail != null ||
            downloadedRail != null

    PullToRefreshBox(
        isRefreshing = (state.newRefreshing || state.popularRefreshing) && hasVisibleContent,
        onRefresh = homeViewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
        ) {
            item(key = "home-header") {
                HomeSearchHeader(
                    query = query,
                    searchExpanded = searchExpanded,
                    onQueryChange = { query = it },
                    onToggleSearch = {
                        if (searchExpanded) {
                            query = ""
                            searchExpanded = false
                        } else {
                            searchExpanded = true
                        }
                    },
                    onSearch = submitSearch,
                )
            }

            continueBook?.let { book ->
                item(key = "home-continue") {
                    HomeContinueSection(
                        book = book,
                        showProgressPercent = showProgressPercent,
                        onOpen = { onBook(book.id) },
                        onContinue = { onContinueBook(book.id) },
                    )
                }
            }

            item(key = "home-new") {
                HomeNewSection(
                    books = state.newBooks,
                    refreshing = state.newRefreshing,
                    error = state.newError,
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }

            item(key = "home-popular") {
                HomePopularSection(
                    books = state.popularBooks,
                    refreshing = state.popularRefreshing,
                    error = state.popularError,
                    selectedPeriod = state.popularPeriod,
                    onSelectPeriod = homeViewModel::selectPopularPeriod,
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }

            nextSeries?.let { series ->
                item(key = "home-next-series-" + series.id) {
                    HomeNextSeriesSection(
                        series = series,
                        onOpen = { onSeries(series) },
                    )
                }
            }

            recentRail?.takeIf { it.items.isNotEmpty() }?.let { rail ->
                item(key = "home-recent") {
                    HomeRecentSection(
                        books = rail.items.take(10),
                        showProgressPercent = showProgressPercent,
                        onBook = onBook,
                    )
                }
            }

            downloadedRail?.takeIf { it.items.isNotEmpty() }?.let { rail ->
                item(key = "home-downloads") {
                    HomeDownloadsSection(
                        books = rail.items.take(10),
                        showProgressPercent = showProgressPercent,
                        onBook = { bookId ->
                            val offlineId = state.downloadedBookSourceIds[bookId]
                            if (offlineId != null) {
                                onDownloadedBook(offlineId)
                            } else {
                                onBook(bookId)
                            }
                        },
                    )
                }
            }

            if (state.loading && !hasVisibleContent) {
                item(key = "home-local-loading") {
                    HomeLocalLoadingState()
                }
            }

            state.error?.takeIf { !hasVisibleContent }?.let { message ->
                item(key = "home-local-error") {
                    HomeLocalErrorState(message)
                }
            }
        }
    }
}
