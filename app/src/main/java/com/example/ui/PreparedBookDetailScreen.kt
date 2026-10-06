package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.player.PlayerUiState
import com.example.ui.viewmodel.BookDetailViewModel

/** Nav3-scoped Book Detail host. Display state belongs only to BookDetailViewModel. */
@Composable
internal fun PreparedBookDetailScreen(
    routeId: String,
    downloaded: Boolean,
    playerState: PlayerUiState,
    showProgressPercent: Boolean,
    onResumeBook: (BookDetailDto) -> Unit,
    onTogglePlayback: () -> Unit,
    onPlayBookmark: (BookDetailDto, BookmarkDto) -> Unit,
    onBack: () -> Unit,
    onPlayer: () -> Unit,
    onSourceSeries: (String, String?) -> Unit,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
    onGenre: (GenreDto) -> Unit,
    onSimilarBook: (String) -> Unit,
    onSearchOtherSources: (String, String) -> Unit,
) {
    val viewModel: BookDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadUi by viewModel.downloadUi.collectAsStateWithLifecycle()
    val browserChallengeSession = rememberInlineBrowserChallengeSession()
    val browserChallengeUiState = browserChallengeSession
        ?.uiState
        ?.collectAsStateWithLifecycle()
        ?.value
    val browserChallengeRequestId = browserChallengeUiState
        ?.takeIf { it.required }
        ?.requestId
    val requestDownload = rememberBookDownloadRequester(
        onStart = viewModel::startDownload,
        onResume = viewModel::resumeDownload,
        onRetry = viewModel::retryDownload,
    )

    LaunchedEffect(routeId, downloaded) {
        viewModel.load(routeId, downloaded)
    }

    val book = state.book
    if (book == null) {
        if (browserChallengeSession != null && browserChallengeRequestId != null) {
            BrowserChallengeGate(
                session = browserChallengeSession,
                hostedRequestId = browserChallengeRequestId,
                inline = true,
            )
            return
        }
        BookDetailUnavailableState(
            error = state.error,
            loading = state.loading,
            alternateQuery = state.alternateSearchQuery,
            excludedSource = state.alternateSearchExcludedSource,
            onSearchOtherSources = onSearchOtherSources,
            onRetry = viewModel::retry,
        )
        return
    }

    val selectedProvider = book.selectedSource.takeIf { it.isNotBlank() }
    val canNavigateSeries = canOpenBookSeries(book)
    val loadError = state.error?.takeIf { it.isNotBlank() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            PagingBookDetailHost(
                book = book,
                browserChallengeSession = browserChallengeSession,
                browserChallengeRequestId = browserChallengeRequestId,
                audioSeries = state.audioSeries,
                bookmarks = state.bookmarks,
                selectedDownload = state.selectedDownload,
                downloadUi = downloadUi,
                playerState = playerState,
                showProgressPercent = showProgressPercent,
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onResumeBook = { onResumeBook(book) },
                onTogglePlayback = onTogglePlayback,
                onPlayBookmark = { bookmark -> onPlayBookmark(book, bookmark) },
                onToggleFavorite = viewModel::toggleFavorite,
                onSelectSource = viewModel::selectSource,
                onRemoveBookmark = viewModel::removeBookmark,
                onStartDownload = { bookSourceId ->
                    requestDownload(BookDownloadAction.Start, bookSourceId)
                },
                onResumeDownload = { bookSourceId ->
                    requestDownload(BookDownloadAction.Resume, bookSourceId)
                },
                onRetryDownload = { bookSourceId ->
                    requestDownload(BookDownloadAction.Retry, bookSourceId)
                },
                onBack = onBack,
                onPlayer = onPlayer,
                // Series metadata can be rendered even when the source-native listing
                // contract is intentionally disabled. Do not create a destination until
                // that capability is verified in StandaloneSourceRegistry.
                onSeries = {
                    if (canNavigateSeries) onSourceSeries(book.id, selectedProvider)
                },
                onSourceSeries = { id ->
                    if (canNavigateSeries) {
                        onSourceSeries(id, selectedProvider.takeIf { id == book.id })
                    }
                },
                onAuthor = onAuthor,
                onNarrator = onNarrator,
                onGenre = onGenre,
                onSimilarBook = onSimilarBook,
            )

            if (state.loading && browserChallengeRequestId == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                )
            }
        }

        BookDetailLoadFooter(
            loadError = loadError,
            alternateQuery = state.alternateSearchQuery,
            excludedSource = state.alternateSearchExcludedSource,
            onSearchOtherSources = onSearchOtherSources,
            onRetry = viewModel::retry,
        )
    }
}
