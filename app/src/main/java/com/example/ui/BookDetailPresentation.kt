package com.example.ui

import com.example.data.model.BookDetailDto
import com.example.data.model.SeriesDetailDto
import com.example.data.player.PlayerUiState
import com.example.data.player.estimateOverallProgressPercent
import kotlin.math.roundToInt

internal data class BookDetailPresentationState(
    val activeBookId: String?,
    val displayedProgress: Double,
    val ruTrackerBook: Boolean,
    val primarySeriesId: String?,
    val seriesName: String?,
    val seriesPosition: Int?,
    val seriesTotal: Int?,
)

internal fun bookDetailPresentationState(
    book: BookDetailDto,
    audioSeries: SeriesDetailDto?,
    playerState: PlayerUiState,
): BookDetailPresentationState {
    val activeBook = playerState.book
    val isActivePlaybackSource = activeBook != null && isSamePlaybackSource(
        activeBookId = activeBook.id,
        activeBookSourceId = activeBook.selectedBookSourceId,
        activeSourceCode = activeBook.selectedSource,
        targetBookId = book.id,
        targetBookSourceId = book.selectedBookSourceId,
        targetSourceCode = book.selectedSource,
    )
    val displayedProgress = if (isActivePlaybackSource && activeBook != null) {
        estimateOverallProgressPercent(
            book = activeBook,
            chapterIndex = playerState.chapterIndex,
            positionMs = playerState.positionMs,
            currentDurationMs = playerState.durationMs,
        )
    } else {
        book.progressPercent
    }.coerceIn(0.0, 100.0)

    val ruTrackerBook = isRuTrackerBook(book)
    val primarySeries = if (ruTrackerBook) {
        null
    } else {
        book.series.firstOrNull { it.isPrimary } ?: book.series.firstOrNull()
    }
    val sourceSeriesName = if (ruTrackerBook) {
        ""
    } else {
        book.sourceSeriesName.ifBlank { book.seriesName }
    }
    val seriesName = if (ruTrackerBook) {
        null
    } else {
        audioSeries?.name?.takeIf { it.isNotBlank() }
            ?: primarySeries?.name?.takeIf { it.isNotBlank() }
            ?: sourceSeriesName.takeIf { it.isNotBlank() }
    }
    val seriesCurrentEntry = if (ruTrackerBook) {
        null
    } else {
        audioSeries?.entries?.firstOrNull { it.book?.id == book.id }
    }
    val seriesPosition = if (ruTrackerBook) {
        null
    } else {
        seriesCurrentEntry?.position?.roundToInt()
            ?: primarySeries?.position?.roundToInt()
            ?: book.sourceSeriesPosition
            ?: book.seriesPosition
    }
    val seriesTotal = if (ruTrackerBook) {
        null
    } else {
        audioSeries?.totalCount
            ?.coerceAtLeast(audioSeries.entries.size)
            ?.coerceAtLeast(audioSeries.booksCount)
            ?.takeIf { it > 0 }
    }

    return BookDetailPresentationState(
        activeBookId = activeBook?.id,
        displayedProgress = displayedProgress,
        ruTrackerBook = ruTrackerBook,
        primarySeriesId = primarySeries?.id,
        seriesName = seriesName,
        seriesPosition = seriesPosition,
        seriesTotal = seriesTotal,
    )
}
