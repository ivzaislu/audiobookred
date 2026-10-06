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
