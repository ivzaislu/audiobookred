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
    LaunchedEffect(
        book.id,
        book.selectedSource,
        browserChallengeSession,
        browserChallengeRequestId,
    ) {
        if (browserChallengeSession != null && browserChallengeRequestId != null) {
            listState.animateScrollToItem(1)
        } else {
            listState.scrollToItem(0)
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
                displayedProgress = presentation.displayedProgress,
                browserChallengeSession = browserChallengeSession,
                browserChallengeRequestId = browserChallengeRequestId,
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
                onResumeBook = onResumeBook,
                onTogglePlayback = onTogglePlayback,
                onPlayer = onPlayer,
                onStartDownload = onStartDownload,
                onResumeDownload = onResumeDownload,
                onRetryDownload = onRetryDownload,
            )
        }

        item(key = "book-detail-metadata") {
            BookDetailMetadataSection(
                book = book,
                ruTrackerBook = presentation.ruTrackerBook,
                onAuthor = onAuthor,
                onNarrator = onNarrator,
                onGenre = onGenre,
                seriesName = presentation.seriesName,
                seriesPosition = presentation.seriesPosition,
                seriesTotal = presentation.seriesTotal,
                onOpenSeries = {
                    if (presentation.primarySeriesId != null) {
                        onSeries(presentation.primarySeriesId)
                    } else {
                        onSourceSeries(book.id)
                    }
                },
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
