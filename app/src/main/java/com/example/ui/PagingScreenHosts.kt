package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesDetailDto
import com.example.data.player.PlayerUiState
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.paging.BookPageRequest
import com.example.ui.paging.BookPagingViewModel
import com.example.ui.viewmodel.BookDetailDownloadUiState
import com.example.ui.viewmodel.BrowseUiState
import com.example.ui.viewmodel.CatalogUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PagingCatalogHost(
    state: CatalogUiState,
    listState: LazyListState,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchAllSources: (Boolean) -> Unit,
    onGenre: (String?) -> Unit,
    onSource: (String) -> Unit,
    onBook: (String) -> Unit,
    onRefreshMetadata: () -> Unit,
    showProgressPercent: Boolean,
    browserChallengeSession: BrowserChallengeSession?,
) {
    val pagingVm: BookPagingViewModel = hiltViewModel()
    val sourceAvailabilityKey = state.enabledSources.sorted().joinToString("|")
    val request = remember(
        state.query,
        state.selectedGenreId,
        state.requestSource,
        sourceAvailabilityKey,
    ) {
        BookPageRequest.Catalog(
            query = state.query,
            genreId = state.selectedGenreId,
            source = state.requestSource,
            sourceAvailabilityKey = sourceAvailabilityKey,
        )
    }
    val booksFlow = remember(request, sourceAvailabilityKey) { pagingVm.books(request) }
    val books = booksFlow.collectAsLazyPagingItems()
    val refreshAll = {
        if (state.genres.isEmpty()) onRefreshMetadata()
        books.refresh()
    }

    PullToRefreshBox(
        isRefreshing = books.loadState.refresh is LoadState.Loading && books.itemCount > 0,
        onRefresh = refreshAll,
        modifier = Modifier.fillMaxSize(),
    ) {
        PagingCatalogScreen(
            state = state,
            books = books,
            listState = listState,
            onSearch = onSearch,
            onClearSearch = onClearSearch,
            onSearchAllSources = onSearchAllSources,
            onGenre = onGenre,
            onSource = onSource,
            onBook = onBook,
            onRefreshMetadata = onRefreshMetadata,
            showProgressPercent = showProgressPercent,
            browserChallengeSession = browserChallengeSession,
        )
        if (browserChallengeSession == null) {
            ScrollToTopButton(listState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PagingBrowseBooksHost(
    state: BrowseUiState,
    listState: LazyListState,
    onBack: () -> Unit,
    onBook: (String) -> Unit,
    showProgressPercent: Boolean,
) {
    val pagingVm: BookPagingViewModel = hiltViewModel()
    val request = remember(
        state.target.kind,
        state.target.id,
        state.target.excludeSource,
        state.target.seriesName,
    ) {
        BookPageRequest.Browse(
            kind = state.target.kind.name,
            id = state.target.id,
            excludeSource = state.target.excludeSource,
            seriesName = state.target.seriesName,
        )
    }
    val booksFlow = remember(request) { pagingVm.books(request) }
    val books = booksFlow.collectAsLazyPagingItems()

    PullToRefreshBox(
        isRefreshing = books.loadState.refresh is LoadState.Loading && books.itemCount > 0,
        onRefresh = books::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        PagingBrowseBooksScreen(
            state = state,
            books = books,
            listState = listState,
            onBack = onBack,
            onBook = onBook,
            showProgressPercent = showProgressPercent,
        )
        ScrollToTopButton(listState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PagingBookDetailHost(
    book: BookDetailDto,
    browserChallengeSession: BrowserChallengeSession?,
    browserChallengeRequestId: Long?,
    audioSeries: SeriesDetailDto?,
    bookmarks: List<BookmarkDto>,
    selectedDownload: DownloadBookEntity?,
    downloadUi: BookDetailDownloadUiState,
    playerState: PlayerUiState,
    showProgressPercent: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onResumeBook: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPlayBookmark: (BookmarkDto) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectSource: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onStartDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onBack: () -> Unit,
    onPlayer: () -> Unit,
    onSeries: (String) -> Unit,
    onSourceSeries: (String) -> Unit,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
    onGenre: (GenreDto) -> Unit,
    onSimilarBook: (String) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        BookDetailScreenV2(
            book = book,
            browserChallengeSession = browserChallengeSession,
            browserChallengeRequestId = browserChallengeRequestId,
            audioSeries = audioSeries,
            bookmarks = bookmarks,
            selectedDownload = selectedDownload,
            downloadUi = downloadUi,
            playerState = playerState,
            showProgressPercent = showProgressPercent,
            onResumeBook = onResumeBook,
            onTogglePlayback = onTogglePlayback,
            onPlayBookmark = onPlayBookmark,
            onToggleFavorite = onToggleFavorite,
            onSelectSource = onSelectSource,
            onRemoveBookmark = onRemoveBookmark,
            onStartDownload = onStartDownload,
            onResumeDownload = onResumeDownload,
            onRetryDownload = onRetryDownload,
            onBack = onBack,
            onPlayer = onPlayer,
            onSeries = onSeries,
            onSourceSeries = onSourceSeries,
            onAuthor = onAuthor,
            onNarrator = onNarrator,
            onGenre = onGenre,
            onSimilarBook = onSimilarBook,
        )
    }
}
