package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesDetailDto
import com.example.data.player.PlayerUiState
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.BookDetailDownloadUiState

/**
 * Book detail is intentionally quieter than the player: the cover and primary playback
 * action carry the hierarchy while source, genres, series and metadata use neutral surfaces.
 * Chapters stay in the player and are not duplicated here.
 */
@Composable
internal fun BookDetailScreenV2(
    book: BookDetailDto,
    browserChallengeSession: BrowserChallengeSession?,
    browserChallengeRequestId: Long?,
    audioSeries: SeriesDetailDto?,
    bookmarks: List<BookmarkDto>,
    selectedDownload: DownloadBookEntity?,
    downloadUi: BookDetailDownloadUiState,
    playerState: PlayerUiState,
    showProgressPercent: Boolean,
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
    val presentation = bookDetailPresentationState(
        book = book,
        audioSeries = audioSeries,
        playerState = playerState,
    )

    val listState = rememberLazyListState()
    LaunchedEffect(book.id, book.selectedSource) { listState.scrollToItem(0) }
    LaunchedEffect(browserChallengeRequestId) {
        if (browserChallengeRequestId != null) {
            listState.animateScrollToItem(2)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
        ) {
        item(key = "book-detail-top") {
            BookDetailTopBar(
                book = book,
                onBack = onBack,
                onToggleFavorite = onToggleFavorite,
            )
        }

        item(key = "book-detail-hero") {
            BookDetailHeroSection(
                book = book,
                ruTrackerBook = presentation.ruTrackerBook,
                onAuthor = onAuthor,
                onNarrator = onNarrator,
            )
        }

        item(key = "book-detail-actions") {
            BookDetailActionsSection(
                book = book,
                browserChallengeSession = browserChallengeSession,
                browserChallengeRequestId = browserChallengeRequestId,
                selectedDownload = selectedDownload,
                downloadUi = downloadUi,
                displayedProgress = presentation.displayedProgress,
                showProgressPercent = showProgressPercent,
                activeBookId = presentation.activeBookId,
                playerIsPlaying = playerState.isPlaying,
                ruTrackerBook = presentation.ruTrackerBook,
                onResumeBook = onResumeBook,
                onTogglePlayback = onTogglePlayback,
                onPlayer = onPlayer,
                onStartDownload = onStartDownload,
                onResumeDownload = onResumeDownload,
                onRetryDownload = onRetryDownload,
                onGenre = onGenre,
            )
        }

        if (book.sourceVariants.size > 1) {
            item(key = "book-detail-source") {
                BookDetailSourceSection(
                    book = book,
                    onSelectSource = onSelectSource,
                )
            }
        }

        if (!presentation.seriesName.isNullOrBlank()) {
            item(key = "book-detail-series") {
                BookDetailSeriesSection(
                    name = presentation.seriesName,
                    position = presentation.seriesPosition,
                    total = presentation.seriesTotal,
                    onOpen = {
                        if (presentation.primarySeriesId != null) {
                            onSeries(presentation.primarySeriesId)
                        } else {
                            onSourceSeries(book.id)
                        }
                    },
                )
            }
        }

        if (book.description.isNotBlank()) {
            item(key = "book-detail-description") {
                DescriptionSection(
                    description = book.description,
                    stateKey = "${book.id}|${book.selectedSource}",
                )
            }
        }

        if (bookmarks.isNotEmpty()) {
            item(key = "book-detail-bookmarks") {
                BookmarksSection(
                    book = book,
                    bookmarks = bookmarks,
                    onPlayBookmark = onPlayBookmark,
                    onRemoveBookmark = onRemoveBookmark,
                )
            }
        }

            item(key = "book-detail-similar") {
                SimilarBooksSection(
                    bookId = book.id,
                    showProgressPercent = showProgressPercent,
                    onSimilarBook = onSimilarBook,
                )
            }
        }
    }
}
