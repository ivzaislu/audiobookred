package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.local.DownloadBookEntity
import com.example.data.narration.textAudioSourceId
import com.example.data.model.BookmarkUiItem
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.LibraryUiState
import com.example.ui.viewmodel.TextLibraryUiState
import com.example.ui.viewmodel.mergeLocalLibraryHistory

internal enum class LibraryTabV2(val title: String) {
    History("История"),
    Favorites("Избранное"),
    Series("Циклы"),
    Bookmarks("Закладки"),
    Device("Скачанные"),
    Texts("Книги"),
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
    selectedTab: LibraryTabV2,
    onSelectTab: (LibraryTabV2) -> Unit,
    textLibrary: TextLibraryUiState,
    onImportText: () -> Unit,
    onOpenText: (String) -> Unit,
    onRemoveText: (String) -> Unit,
    onPlayText: (String) -> Unit,
    onRemoveTextHistory: (String) -> Unit,
    onRemoveTextSeries: (String) -> Unit,
) {
    val tab = selectedTab
    val membership = textLibrary.membership
    val textIds = textLibrary.books.map { it.id }.toSet()
    var textSeriesKey by rememberSaveable(entryKey) { mutableStateOf<String?>(null) }
    val textSeries = membership.series.firstOrNull { it.id == textSeriesKey }
    if (textSeries != null) {
        BackHandler { textSeriesKey = null }
        TextLibraryCycleScreen(
            series = textSeries, books = membership.seriesBooks[textSeries.id].orEmpty(),
            onBack = { textSeriesKey = null }, onBook = onOpenText, onPlay = onPlayText,
        )
        return
    }
    val openBook: (String) -> Unit = { if (it in textIds) onOpenText(it) else onBook(it) }
    val continueBook: (String) -> Unit = { if (it in textIds) onPlayText(it) else onContinueBook(it) }
    val openSeries: (MySeriesDto) -> Unit = { if (it.provider.startsWith("text:")) textSeriesKey = it.id else onSeries(it) }
    val allHistory = mergeLocalLibraryHistory(state.history, state.historyActivityTimes, downloads, textIds, membership)
    val allSeries = (membership.series + state.series).sortedByDescending { it.lastActivityAt.toLongOrNull() ?: 0L }
    var seriesFilter by remember { mutableStateOf(SeriesLibraryFilterV2.Active) }
    var searchExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(entryKey) {
        searchExpanded = false
        query = ""
    }

    val cleanQuery = query.trim()
    val favorites = remember(state.favorites, cleanQuery) {
        state.favorites.filter { it.matchesLibraryQuery(cleanQuery) }
    }
    val history = remember(allHistory, cleanQuery) {
        allHistory.filter { it.matchesLibraryQuery(cleanQuery) }
    }
    val series = remember(allSeries, cleanQuery) {
        allSeries.filter { it.matchesLibraryQuery(cleanQuery) }
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
                bottom = AbredSpacing.None,
            ),
            placeholder = "Название, автор или чтец",
        )

        LibraryTilesV2(
            selected = tab,
            counts = mapOf(
                LibraryTabV2.History to allHistory.size,
                LibraryTabV2.Favorites to state.favorites.size,
                LibraryTabV2.Series to allSeries.size,
                LibraryTabV2.Bookmarks to state.bookmarks.size,
                LibraryTabV2.Device to downloads.size,
                LibraryTabV2.Texts to textLibrary.books.size,
            ),
            onSelect = onSelectTab,
        )

        (state.error ?: textLibrary.membershipError)?.takeIf { it.isNotBlank() }?.let { error ->
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

        if (state.loading && tab != LibraryTabV2.Device && tab != LibraryTabV2.Texts) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(AbredSizes.LoadingIndicatorProminent))
            }
            return@Column
        }

        when (tab) {
            LibraryTabV2.Texts -> TextBooksSection(
                savedAudioIds = textLibrary.books.mapNotNull { book ->
                    downloads.firstOrNull {
                        it.bookSourceId == textAudioSourceId(book.id, null) &&
                            isCompletedRuVoiceAudiobook(it)
                    }?.let { book.id to it.bookSourceId }
                }.toMap(),
                onPlaySaved = onPlayDownloaded,
                books = textLibrary.books.filter { cleanQuery.isBlank() || it.title.contains(cleanQuery, true) || it.originalName.contains(cleanQuery, true) || textLibrary.metadata[it.id]?.author?.contains(cleanQuery, true) == true },
                metadata = textLibrary.metadata,
                importing = textLibrary.importing, message = textLibrary.message, queryActive = cleanQuery.isNotBlank(),
                onImport = onImportText, onOpen = onOpenText, onRemove = onRemoveText, onPlay = onPlayText,
            )
            LibraryTabV2.History -> LibraryBooksListV2(
                books = history,
                emptyText = if (cleanQuery.isBlank()) "История чтения и прослушивания пока пуста" else "Ничего не найдено",
                showProgressPercent = showProgressPercent,
                onBook = openBook,
                onDelete = { if (it in textIds) onRemoveTextHistory(it) else onRemoveHistory(it) },
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
                onSeries = openSeries,
                onContinueBook = continueBook,
                onDelete = { if (it.provider.startsWith("text:")) onRemoveTextSeries(it.id) else onRemoveSeries(it) },
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


