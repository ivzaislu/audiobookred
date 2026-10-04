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
                val total = metadata?.totalCount?.coerceAtLeast(entries.itemCount) ?: entries.itemCount
                val booksCount = metadata?.booksCount ?: 0
                val typeLabel = metadata?.let(::seriesTypeLabel).orEmpty()
                val countLabel = when {
                    metadata == null && refreshState is LoadState.Loading -> "Загрузка…"
                    total > 0 -> "Доступно аудио: $booksCount из $total"
                    metadata?.kind == "source_series" -> "Аудиосерия источника"
                    else -> "Литературный цикл"
                }
                Text(
                    if (typeLabel.isBlank() || countLabel == "Загрузка…") countLabel else "$typeLabel · $countLabel",
                    modifier = Modifier.padding(
                        horizontal = AbredSpacing.ScreenHorizontal,
                        vertical = AbredSpacing.Xxs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (switchOptions.size > 1) {
                item(key = "series-switcher") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AbredSpacing.Xxs, bottom = AbredSpacing.Xs),
                    ) {
                        Text(
                            "Варианты цикла",
                            modifier = Modifier.padding(
                                horizontal = AbredSpacing.ScreenHorizontal,
                                vertical = AbredSpacing.Xxs,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = AbredSpacing.ScreenHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                        ) {
                            items(
                                count = switchOptions.size,
                                key = { index -> switchOptions[index].request.storageKey },
                            ) { index ->
                                val option = switchOptions[index]
                                AnimatedSelectionFilterChip(
                                    selected = option.request.storageKey == request.storageKey,
                                    onClick = {
                                        if (option.request.storageKey != request.storageKey) {
                                            pagingVm.clearMetadata()
                                            request = option.request
                                            uiScope.launch { listState.scrollToItem(0) }
                                        }
                                    },
                                    label = {
                                        Text(
                                            "${option.typeLabel} · ${option.title}",
                                            modifier = Modifier.widthIn(max = 240.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            metadata?.description?.takeIf { it.isNotBlank() }?.let { description ->
                item(key = "series-description") {
                    Text(
                        description,
                        modifier = Modifier.padding(
                            horizontal = AbredSpacing.ScreenHorizontal,
                            vertical = AbredSpacing.Xs,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            metadata?.takeIf { it.name.isNotBlank() }?.let { detail ->
                item(key = "search-other-series") {
                    OutlinedButton(
                        onClick = { onSearchOtherSources(detail.name, detail.provider) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AbredSpacing.ScreenHorizontal,
                                vertical = AbredSpacing.Xs,
                            ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            "Поиск цикла в других источниках",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

@Composable
private fun SeriesPagingError(message: String, onRetry: () -> Unit) {
    AbredEmptyState(
        icon = Icons.Default.CloudOff,
        title = "Не удалось загрузить цикл",
        message = message,
        action = { Button(onClick = onRetry) { Text("Повторить") } },
    )
}

@Composable
private fun SeriesEntryPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.padding(AbredSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Text("Загрузка…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeriesEntryRow(
    entry: com.example.data.model.SeriesEntryDto,
    onBook: (String) -> Unit,
) {
    val book = entry.book
    val enabled = book != null && entry.available
    val title = entry.title.ifBlank { book?.title.orEmpty() }
    val sourceLabel = book?.sourceDisplayLabel().orEmpty()
    val progress = book?.progressPercent?.coerceIn(0.0, 100.0) ?: 0.0
    val meta = buildList {
        entry.position?.let { add("№ ${formatSeriesPosition(it)}") }
        if (entry.authors.isNotEmpty()) add(entry.authors.joinToString(", "))
        entry.publishedYear?.let { add(it.toString()) }
    }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs)
            .then(if (enabled) Modifier.clickable { onBook(book!!.id) } else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = if (enabled) {
            abredBookCardBorder()
        } else {
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            AbredBookCover(
                model = book?.coverUrl,
                modifier = Modifier
                    .width(AbredSizes.BookRowCoverWidth)
                    .height(AbredSizes.BookRowCoverHeight),
            )
            Spacer(Modifier.width(AbredSpacing.Sm))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AbredSizes.BookRowCoverHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = if (sourceLabel.isNotBlank()) {
                                AbredSizes.BookCardSourceReserve
                            } else {
                                0.dp
                            },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (enabled) {
                            Spacer(Modifier.width(AbredSpacing.Xs))
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                Modifier.size(AbredSizes.IconSmall),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    book?.narratorText?.takeIf { it.isNotBlank() }?.let { narrator ->
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            "Читает: $narrator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (!enabled) {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        Text(
                            "Нет доступного аудио",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (enabled && progress > 0.05) {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        AbredBookProgress(progress)
                    }
                }

                if (sourceLabel.isNotBlank()) {
                    AbredBookSourceLabel(
                        text = sourceLabel,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
    }
}

private fun formatSeriesPosition(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun seriesTypeLabel(detail: com.example.data.model.SeriesDetailDto): String = when {
    detail.kind == "source_series" -> when (detail.provider.lowercase()) {
        "audiopolka" -> "Audiopolka"
        "audioboo" -> "Audioboo"
        "uknig" -> "уКниг"
        "knigavuhe" -> "Книга в ухе"
        else -> detail.provider.ifBlank { "Источник" }
    }
    detail.provider.equals("fantlab", true) -> "FantLab"
    detail.provider.equals("litres", true) -> "LitRes"
    detail.provider.equals("fantlab/litres", true) || detail.provider.equals("litres/fantlab", true) -> "FantLab/LitRes"
    else -> detail.provider.ifBlank { "Литературный цикл" }
}
