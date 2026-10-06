package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.ui.paging.SeriesPageRequest
import com.example.ui.paging.SeriesPagingViewModel
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.SeriesRoute
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PagingSeriesScreen(
    route: SeriesRoute,
    listState: LazyListState,
    onBack: () -> Unit,
    onBook: (String) -> Unit,
    onSearchOtherSources: (String, String) -> Unit,
) {
    val pagingVm: SeriesPagingViewModel = hiltViewModel()
    val initialRequest = remember(route) {
        when (route) {
            is SeriesRoute.Canonical -> route.bookId
                ?.takeIf(String::isNotBlank)
                ?.let { SeriesPageRequest.Source(it) }
                ?: SeriesPageRequest.Canonical(route.seriesId)
            is SeriesRoute.Source -> SeriesPageRequest.Source(route.bookId, route.provider)
            is SeriesRoute.Audio -> route.bookId
                ?.takeIf(String::isNotBlank)
                ?.let { SeriesPageRequest.Source(it) }
                ?: SeriesPageRequest.Audio(route.seriesId)
        }
    }
    val sourceBookId = remember(route) {
        when (route) {
            is SeriesRoute.Canonical -> route.bookId
            is SeriesRoute.Source -> route.bookId
            is SeriesRoute.Audio -> route.bookId
        }
    }
    var request by remember(route) { mutableStateOf<SeriesPageRequest>(initialRequest) }
    val uiScope = rememberCoroutineScope()

    LaunchedEffect(sourceBookId) { pagingVm.loadSwitchOptions(sourceBookId) }

    val switchOptions by pagingVm.switchOptions.collectAsStateWithLifecycle()
    val entriesFlow = remember(request) { pagingVm.entries(request) }
    val entries = entriesFlow.collectAsLazyPagingItems()
    val metadata by pagingVm.metadata.collectAsStateWithLifecycle()
    val refreshState = entries.loadState.refresh
    val contentAlpha = rememberSelectionFade(request.storageKey)
    val refreshAll = {
        entries.refresh()
        pagingVm.refreshSwitchOptions()
    }

    PullToRefreshBox(
        isRefreshing = refreshState is LoadState.Loading && entries.itemCount > 0,
        onRefresh = refreshAll,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = AbredSizes.ContentMaxWidth)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha },
                contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
                verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
            ) {
            item(key = "series-top-bar") {
                AbredBackTopBar(
                    title = metadata?.name?.takeIf { it.isNotBlank() } ?: "Цикл",
                    onBack = onBack,
                    titleColor = MaterialTheme.colorScheme.abredAccentText,
                )
            }

            item(key = "series-summary") {
                PagingSeriesSummary(
                    metadata = metadata,
                    itemCount = entries.itemCount,
                    refreshState = refreshState,
                )
            }

            if (switchOptions.size > 1) {
                item(key = "series-switcher") {
                    PagingSeriesSwitcher(
                        options = switchOptions,
                        request = request,
                        onSelect = { nextRequest ->
                            pagingVm.clearMetadata()
                            request = nextRequest
                            uiScope.launch { listState.scrollToItem(0) }
                        },
                    )
                }
            }

            metadata?.description?.takeIf { it.isNotBlank() }?.let { description ->
                item(key = "series-description") {
                    PagingSeriesDescription(description)
                }
            }

            metadata?.takeIf { it.name.isNotBlank() }?.let { detail ->
                item(key = "search-other-series") {
                    PagingSeriesSearchOtherSources(
                        name = detail.name,
                        provider = detail.provider,
                        onSearchOtherSources = onSearchOtherSources,
                    )
                }
            }

            item(key = "series-books-heading") {
                AbredSectionHeader(title = "Книги цикла")
            }

            when {
                refreshState is LoadState.Loading && entries.itemCount == 0 -> item(key = "series-loading") {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                refreshState is LoadState.Error && entries.itemCount == 0 -> item(key = "series-error") {
                    SeriesPagingError(
                        message = refreshState.error.message ?: "Не удалось загрузить цикл",
                        onRetry = entries::retry,
                    )
                }
                entries.itemCount == 0 -> item(key = "series-empty") {
                    AbredEmptyState(
                        icon = Icons.Default.CloudOff,
                        title = "В цикле пока нет доступных аудиокниг",
                    )
                }
                else -> items(
                    count = entries.itemCount,
                    key = { index -> entries[index]?.externalWorkId ?: "series-$index" },
                ) { index ->
                    val entry = entries[index]
                    if (entry == null) {
                        SeriesEntryPlaceholder()
                    } else {
                        SeriesEntryRow(entry = entry, onBook = onBook)
                    }
                }
            }

            if (entries.loadState.append is LoadState.Loading) {
                item(key = "series-append-loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(AbredSpacing.Lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            val appendError = entries.loadState.append as? LoadState.Error
                if (appendError != null) {
                    item(key = "series-append-error") {
                        SeriesPagingError(
                            message = appendError.error.message ?: "Не удалось загрузить следующую страницу",
                            onRetry = entries::retry,
                        )
                    }
                }
            }
        }
        ScrollToTopButton(listState)
    }
}
