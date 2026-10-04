package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesDetailDto
import com.example.data.player.PlayerUiState
import com.example.data.player.estimateOverallProgressPercent
import com.example.data.parser.BrowserChallengeSession
import com.example.data.torrserve.canResolveRuTrackerPlayback
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.BookDetailDownloadUiState
import kotlin.math.roundToInt

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
    val sourceSeriesName = if (ruTrackerBook) "" else book.sourceSeriesName.ifBlank { book.seriesName }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = AbredSpacing.Xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
                ) {
                    Icon(Icons.Default.ArrowBack, "Назад")
                }
                Text(
                    "О книге",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (book.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Icon(
                        if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (book.isFavorite) "Убрать из избранного" else "В избранное",
                    )
                }
            }
        }

        item(key = "book-detail-hero") {
            BookDetailSectionCard(
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AbredSpacing.Md),
                    verticalAlignment = Alignment.Top,
                ) {
                    BookCoverImage(
                        model = book.coverUrl,
                        contentDescription = book.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(124.dp)
                            .height(186.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                    Spacer(Modifier.width(AbredSpacing.Md))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 186.dp),
                    ) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        CompactPeopleLine(
                            prefix = "Автор",
                            people = book.authors,
                            emptyText = "Автор не указан",
                            interactive = !ruTrackerBook,
                            onPerson = onAuthor,
                        )
                        if (book.narrators.isNotEmpty()) {
                            Spacer(Modifier.height(AbredSpacing.Xxs))
                            CompactPeopleLine(
                                prefix = "Читает",
                                people = book.narrators,
                                interactive = !ruTrackerBook,
                                onPerson = onNarrator,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        detailSourceCode(book).takeIf { it.isNotBlank() }?.let { code ->
                            Surface(
                                modifier = Modifier.align(Alignment.End).widthIn(max = 160.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text(
                                    text = sourceDisplayLabel(code),
                                    modifier = Modifier.padding(
                                        horizontal = AbredSpacing.Xs,
                                        vertical = AbredSpacing.Xxs,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "book-detail-actions") {
            BookDetailSectionCard(
                modifier = Modifier.padding(top = AbredSpacing.Sm),
            ) {
                if (browserChallengeSession != null && browserChallengeRequestId != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AbredSizes.BookDetailInlineChallengeHeight),
                    ) {
                        BrowserChallengeGate(
                            session = browserChallengeSession,
                            hostedRequestId = browserChallengeRequestId,
                            inline = true,
                        )
                    }
                    return@BookDetailSectionCard
                }

                if (displayedProgress > 0.05) {
                    LinearProgressIndicator(
                        progress = { (displayedProgress / 100.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AbredSizes.ProgressTrack)
                            .clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.height(AbredSpacing.Xs))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = book.chapters.isNotEmpty() || canResolveRuTrackerPlayback(book),
                        onClick = {
                            val requiresResume = shouldResumeBookPlayback(
                                activeBookId = activeBook?.id,
                                targetBookId = book.id,
                            )
                            if (requiresResume) {
                                onResumeBook()
                            } else if (!playerState.isPlaying) {
                                onTogglePlayback()
                            }
                            if (
                                !shouldDeferPlayerNavigationForPreparation(
                                    requiresResume = requiresResume,
                                    usesRuTrackerTorrServe = canResolveRuTrackerPlayback(book),
                                )
                            ) {
                                onPlayer()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(AbredSizes.ControlHeight),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(AbredSizes.Icon))
                        Spacer(Modifier.width(AbredSpacing.Xs))
                        Text(
                            playbackButtonLabel(displayedProgress, showProgressPercent),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (book.selectedBookSourceId.isNotBlank()) {
                        CompactDownloadAction(
                            download = selectedDownload,
                            busy = downloadUi.busyBookSourceId == book.selectedBookSourceId,
                            onStart = { onStartDownload(book.selectedBookSourceId) },
                            onResume = { onResumeDownload(book.selectedBookSourceId) },
                            onRetry = { onRetryDownload(book.selectedBookSourceId) },
                        )
                    }
                }

                if (book.selectedBookSourceId.isNotBlank() && downloadUi.bookSourceId == book.selectedBookSourceId) {
                    downloadUi.error?.let {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    downloadUi.message?.let {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (book.genres.isNotEmpty() || book.durationSeconds > 0) {
                    Spacer(Modifier.height(AbredSpacing.Sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (book.genres.isNotEmpty()) {
                            val visibleGenres = if (ruTrackerBook) {
                                book.genres
                            } else {
                                book.genres.take(2)
                            }
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(end = AbredSpacing.Xs),
                                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                items(
                                    count = visibleGenres.size,
                                    key = { index ->
                                        val genre = visibleGenres[index]
                                        "detail-genre-${genre.id.ifBlank { genre.name }}-$index"
                                    },
                                ) { index ->
                                    val genre = visibleGenres[index]
                                    if (ruTrackerBook) {
                                        GenreLabelChip(genre.name)
                                    } else {
                                        SuggestionChip(
                                            onClick = { onGenre(genre) },
                                            shape = MaterialTheme.shapes.small,
                                            label = {
                                                Text(
                                                    genre.name,
                                                    modifier = Modifier.widthIn(max = 180.dp),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                        )
                                    }
                                }
                                if (!ruTrackerBook && book.genres.size > 2) {
                                    item(key = "detail-genre-overflow") {
                                        GenreOverflowChip(
                                            genres = book.genres,
                                            onGenre = onGenre,
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (book.durationSeconds > 0) {
                            if (book.genres.isNotEmpty()) Spacer(Modifier.width(AbredSpacing.Xs))
                            DurationPill(book.durationSeconds)
                        }
                    }
                }
            }
        }

        if (book.sourceVariants.size > 1) {
            item(key = "book-detail-source") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AbredSpacing.ScreenHorizontal,
                            vertical = AbredSpacing.Xxs,
                        ),
                ) {
                    Text(
                        "Источник",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    BookSourceDropdown(
                        variants = book.sourceVariants,
                        selectedSource = book.selectedSource,
                        onSelectSource = onSelectSource,
                    )
                }
                Spacer(Modifier.height(AbredSpacing.Xs))
            }
        }

        if (!seriesName.isNullOrBlank()) {
            item(key = "book-detail-series") {
                Spacer(Modifier.height(AbredSpacing.Sm))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AbredSpacing.ScreenHorizontal)
                        .clickable {
                            if (primarySeries != null) onSeries(primarySeries.id)
                            else onSourceSeries(book.id)
                        },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AbredSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.CollectionsBookmark,
                                    null,
                                    Modifier.size(AbredSizes.IconSmall),
                                )
                            }
                        }
                        Spacer(Modifier.width(AbredSpacing.Sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                seriesName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.abredAccentText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val seriesSubtitle = buildString {
                                if (seriesPosition != null) append("Книга $seriesPosition")
                                if (seriesPosition != null && seriesTotal != null) append(" из $seriesTotal")
                                else if (seriesTotal != null) append("$seriesTotal книг")
                            }
                            if (seriesSubtitle.isNotBlank()) {
                                Spacer(Modifier.height(AbredSpacing.Xxs))
                                Text(
                                    seriesSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
