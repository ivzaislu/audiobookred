package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSizes
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
    val nextSeries = state.mySeries.firstOrNull { !it.isCompleted && (it.nextBook != null || it.currentBook != null) }
    val recentRail = state.rails.firstOrNull { it.kind == "recent" }
    val downloadedRail = state.rails.firstOrNull { it.kind == "downloaded" }
    val hasVisibleContent = continueBook != null || state.newBooks.isNotEmpty() || state.popularBooks.isNotEmpty() ||
        nextSeries != null || recentRail != null || downloadedRail != null

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
                AbredOverlaySearchHeader(
                    sectionTitle = "Главная",
                    searchExpanded = searchExpanded,
                    query = query,
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
                    modifier = Modifier.padding(
                        start = AbredSpacing.ScreenHorizontal,
                        end = AbredSpacing.ScreenHorizontal,
                        top = AbredSpacing.ScreenVertical,
                        bottom = 0.dp,
                    ),
                    placeholder = "Поиск",
                )
            }

            continueBook?.let { book ->
                item(key = "home-continue") {
                    AbredSectionHeader(
                        title = "Продолжить",
                        icon = Icons.Default.PlayArrow,
                        topPadding = 0.dp,
                    )
                    ContinueHeroCard(
                        book = book,
                        showProgressPercent = showProgressPercent,
                        onOpen = { onBook(book.id) },
                        onContinue = { onContinueBook(book.id) },
                    )
                }
            }

            item(key = "home-new") {
                AbredSectionHeader(title = "Новинки")
                DiscoveryShelfState(
                    books = state.newBooks,
                    refreshing = state.newRefreshing,
                    error = state.newError,
                    keyPrefix = "new",
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }

            item(key = "home-popular") {
                AbredSectionHeader(
                    title = "Популярное",
                    trailing = {
                        PopularPeriodSelector(
                            selected = state.popularPeriod,
                            onSelect = homeViewModel::selectPopularPeriod,
                        )
                    },
                )
                DiscoveryShelfState(
                    books = state.popularBooks,
                    refreshing = state.popularRefreshing,
                    error = state.popularError,
                    keyPrefix = "popular-${state.popularPeriod.name}",
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }

            nextSeries?.let { series ->
                item(key = "home-next-series-${series.id}") {
                    AbredSectionHeader(
                        title = "Следующая в цикле",
                        icon = Icons.Default.CollectionsBookmark,
                    )
                    NextSeriesCard(series = series, onClick = { onSeries(series) })
                }
            }

            recentRail?.takeIf { it.items.isNotEmpty() }?.let { rail ->
                item(key = "home-recent") {
                    AbredSectionHeader(
                        title = "Недавно слушали",
                        icon = Icons.Default.History,
                    )
                    HomePosterShelf(
                        books = rail.items.take(10),
                        keyPrefix = "recent",
                        showProgressPercent = showProgressPercent,
                        onBook = onBook,
                    )
                }
            }

            downloadedRail?.takeIf { it.items.isNotEmpty() }?.let { rail ->
                item(key = "home-downloads") {
                    AbredSectionHeader(
                        title = "Скачанные",
                        icon = Icons.Default.DownloadForOffline,
                    )
                    HomePosterShelf(
                        books = rail.items.take(10),
                        keyPrefix = "downloaded",
                        showProgressPercent = showProgressPercent,
                        onBook = { bookId ->
                            val offlineId = state.downloadedBookSourceIds[bookId]
                            if (offlineId != null) onDownloadedBook(offlineId) else onBook(bookId)
                        },
                    )
                }
            }

            if (state.loading && !hasVisibleContent) {
                item(key = "home-local-loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AbredSpacing.Xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                }
            }

            state.error?.takeIf { !hasVisibleContent }?.let { message ->
                item(key = "home-local-error") {
                    Text(
                        message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AbredSpacing.Lg, vertical = AbredSpacing.Md),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = if (abredLargeFontScale()) 5 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
