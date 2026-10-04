package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookmarkUiItem
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.LibraryUiState

internal enum class LibraryTabV2(val title: String) {
    History("История"),
    Favorites("Избранное"),
    Series("Циклы"),
    Bookmarks("Закладки"),
    Device("Скачанные"),
}

internal enum class SeriesLibraryFilterV2 { Active, Completed }

@Composable
internal fun LibraryScreenV2(
    state: LibraryUiState,
    entryKey: Int,
    downloads: List<DownloadBookEntity>,
    onBook: (String) -> Unit,
    onDownloadedBook: (String) -> Unit,
    onPlayDownloaded: (String) -> Unit,
    onPlayBookmark: (BookmarkUiItem) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onSeries: (MySeriesDto) -> Unit,
    onContinueBook: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onRemoveSeries: (MySeriesDto) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRemoveDownload: (String) -> Unit,
    showProgressPercent: Boolean,
) {
    var tab by remember { mutableStateOf(LibraryTabV2.History) }
    var seriesFilter by remember { mutableStateOf(SeriesLibraryFilterV2.Active) }
    var searchExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(entryKey) {
        tab = LibraryTabV2.History
        searchExpanded = false
        query = ""
    }

    val cleanQuery = query.trim()
    val favorites = remember(state.favorites, cleanQuery) {
        state.favorites.filter { it.matchesLibraryQuery(cleanQuery) }
    }
    val history = remember(state.history, cleanQuery) {
        state.history.filter { it.matchesLibraryQuery(cleanQuery) }
    }
    val series = remember(state.series, cleanQuery) {
        state.series.filter { it.matchesLibraryQuery(cleanQuery) }
    }
    val bookmarks = remember(state.bookmarks, cleanQuery) {
        state.bookmarks.filter { it.matchesLibraryQuery(cleanQuery) }
    }
    val filteredDownloads = remember(downloads, cleanQuery) {
        downloads.filter { it.matchesLibraryQuery(cleanQuery) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
        ) {
        AbredOverlaySearchHeader(
            sectionTitle = "Библиотека",
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
            onSearch = {},
            modifier = Modifier.padding(
                start = AbredSpacing.ScreenHorizontal,
                end = AbredSpacing.ScreenHorizontal,
                top = AbredSpacing.ScreenVertical,
                bottom = 0.dp,
            ),
            placeholder = "Название, автор или чтец",
        )

        LibraryTilesV2(
            selected = tab,
            counts = mapOf(
                LibraryTabV2.History to state.history.size,
                LibraryTabV2.Favorites to state.favorites.size,
                LibraryTabV2.Series to state.series.size,
                LibraryTabV2.Bookmarks to state.bookmarks.size,
                LibraryTabV2.Device to downloads.size,
            ),
            onSelect = { tab = it },
        )

        state.error?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AbredSpacing.ScreenHorizontal,
                        vertical = AbredSpacing.Xs,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.loading && tab != LibraryTabV2.Device) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp))
            }
            return@Column
        }

        when (tab) {
            LibraryTabV2.History -> LibraryBooksListV2(
                books = history,
                emptyText = if (cleanQuery.isBlank()) "История прослушивания пока пуста" else "Ничего не найдено",
                showProgressPercent = showProgressPercent,
                onBook = onBook,
                onDelete = onRemoveHistory,
            )
            LibraryTabV2.Favorites -> LibraryBooksListV2(
                books = favorites,
                emptyText = if (cleanQuery.isBlank()) "В избранном пока пусто" else "Ничего не найдено",
                showProgressPercent = showProgressPercent,
                onBook = onBook,
            )
            LibraryTabV2.Series -> MySeriesListV2(
                series = series,
                filter = seriesFilter,
                onFilter = { seriesFilter = it },
                onSeries = onSeries,
                onContinueBook = onContinueBook,
                onDelete = onRemoveSeries,
                queryActive = cleanQuery.isNotBlank(),
            )
            LibraryTabV2.Bookmarks -> BookmarkListV2(
                items = bookmarks,
                queryActive = cleanQuery.isNotBlank(),
                onPlay = onPlayBookmark,
                onDelete = onDeleteBookmark,
            )
            LibraryTabV2.Device -> DeviceBooksListV2(
                downloads = filteredDownloads,
                queryActive = cleanQuery.isNotBlank(),
                onOpen = onDownloadedBook,
                onPlay = onPlayDownloaded,
                onPause = onPauseDownload,
                onResume = onResumeDownload,
                onRetry = onRetryDownload,
                onRemove = onRemoveDownload,
            )
        }
        }
    }
}
