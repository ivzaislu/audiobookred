package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.data.model.BookCardDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import kotlin.math.roundToInt

/**
 * Decorative cover used inside book cards and rows.
 * The visible book title in the same container carries accessibility semantics.
 */
@Composable
internal fun AbredBookCover(
    model: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BookCoverImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun AbredBookProgress(
    progressPercent: Double,
    modifier: Modifier = Modifier,
    showPercentLabel: Boolean = true,
) {
    val progress = progressPercent.coerceIn(0.0, 100.0)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { (progress / 100.0).toFloat() },
            modifier = Modifier
                .weight(1f)
                .height(AbredSizes.ProgressTrack)
                .clip(MaterialTheme.shapes.extraSmall)
                .then(
                    if (showPercentLabel) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    }
                ),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        if (showPercentLabel) {
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(
                "${progress.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.abredAccentText,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun AbredBookSourceLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun abredBookCardBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** Network-backed catalog/search/browse results rendered as one true vertical lazy list. */
internal fun LazyListScope.pagedBookShelves(
    items: LazyPagingItems<BookCardDto>,
    keyPrefix: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    repeat(items.itemCount) { index ->
        item(
            key = "$keyPrefix-book-$index",
            contentType = "book-row",
        ) {
            items[index]?.let { book ->
                PagedBookRowCard(
                    book = book,
                    showProgressPercent = showProgressPercent,
                    onClick = { onBook(book.id) },
                )
            } ?: Spacer(Modifier.fillMaxWidth().height(124.dp))
            Spacer(Modifier.height(AbredSpacing.Xs))
        }
    }

    val refresh = items.loadState.refresh
    val append = items.loadState.append
    when {
        refresh is LoadState.Loading && items.itemCount == 0 -> {
            item(key = "$keyPrefix-paging-initial", contentType = "paging-state") {
                PagingLoadingRow()
            }
        }
        refresh is LoadState.Error && items.itemCount == 0 -> {
            item(key = "$keyPrefix-paging-initial-error", contentType = "paging-state") {
                PagingErrorRow(
                    message = refresh.error.message ?: "Не удалось загрузить книги",
                    onRetry = items::retry,
                )
            }
        }
        append is LoadState.Loading -> {
            item(key = "$keyPrefix-paging-append", contentType = "paging-state") {
                PagingLoadingRow()
            }
        }
        append is LoadState.Error -> {
            item(key = "$keyPrefix-paging-append-error", contentType = "paging-state") {
                PagingErrorRow(
                    message = append.error.message ?: "Не удалось загрузить ещё книги",
                    onRetry = items::retry,
                )
            }
        }
    }
}

@Composable
private fun PagingLoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

@Composable
private fun PagingErrorRow(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
internal fun PagedBookRowCard(
    book: BookCardDto,
    showProgressPercent: Boolean,
    onClick: () -> Unit,
) {
    val sourceLabel = book.sourceDisplayLabel()
    val seriesLabel = book.seriesDisplayLabel()
    val largeText = abredLargeFontScale()
    val sourceReserve = if (largeText) {
        AbredSizes.BookCardSourceReserveLargeText
    } else {
        AbredSizes.BookCardSourceReserve
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal)
            .clickable(onClickLabel = "Открыть книгу", onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = abredBookCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            AbredBookCover(
                model = book.coverUrl,
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
                                sourceReserve
                            } else {
                                0.dp
                            },
                        ),
                ) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (largeText) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.authorText.isNotBlank()) {
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            book.authorText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (book.narratorText.isNotBlank()) {
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            "Читает: ${book.narratorText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (book.sourceMeta.isNotBlank()) {
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            book.sourceMeta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showProgressPercent && book.progressPercent > 0.05) {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        AbredBookProgress(book.progressPercent)
                    }
                    if (seriesLabel.isNotBlank()) {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        Text(
                            seriesLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.abredAccentText,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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

/** Compact fixed-width card for genuinely horizontal local/detail rows. */
@Composable
internal fun PagedShelfBookCard(
    book: BookCardDto,
    showProgressPercent: Boolean,
    onClick: () -> Unit,
) {
    val reservedLine = "\u00A0"
    val progress = book.progressPercent.coerceIn(0.0, 100.0)
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val cardWidth = when {
        extraLargeText -> AbredSizes.BookShelfCardWidthExtraLargeText
        largeText -> AbredSizes.BookShelfCardWidthLargeText
        else -> AbredSizes.BookShelfCardWidth
    }
    val coverHeight = when {
        extraLargeText -> AbredSizes.BookShelfCoverHeightExtraLargeText
        largeText -> AbredSizes.BookShelfCoverHeightLargeText
        else -> AbredSizes.BookShelfCoverHeight
    }

    Card(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClickLabel = "Открыть книгу", onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = abredBookCardBorder(),
    ) {
        Column(Modifier.padding(AbredSpacing.Xs)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight),
            ) {
                AbredBookCover(
                    model = book.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showProgressPercent && progress > 0.05) {
                    LinearProgressIndicator(
                        progress = { (progress / 100.0).toFloat() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(AbredSizes.ProgressTrack)
                            .clearAndSetSemantics { },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = AbredSpacing.Xxs,
                                bottom = AbredSpacing.Xs,
                            ),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            "${progress.roundToInt()}%",
                            modifier = Modifier.padding(
                                horizontal = AbredSpacing.Xxs,
                                vertical = 2.dp,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(AbredSpacing.Xs))
            Box(Modifier.heightIn(min = 40.dp)) {
                Text(
                    book.title.ifBlank { reservedLine },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = when {
                        extraLargeText -> 4
                        largeText -> 3
                        else -> 2
                    },
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(AbredSpacing.Xxs))
            Text(
                book.authorText.ifBlank { reservedLine },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(AbredSpacing.Xxs))
            Text(
                if (book.narratorText.isNotBlank()) "Читает: ${book.narratorText}" else reservedLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
