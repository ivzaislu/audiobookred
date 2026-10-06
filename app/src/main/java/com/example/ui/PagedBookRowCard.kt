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
