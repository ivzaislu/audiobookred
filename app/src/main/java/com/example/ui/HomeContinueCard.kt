package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.HomePopularPeriod
import kotlin.math.roundToInt

/** Stable Home cards, shelves and selectors used by PreparedHomeScreen. */
@Composable
internal fun ContinueHeroCard(
    book: BookCardDto,
    showProgressPercent: Boolean,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
) {
    val sourceLabel = book.sourceDisplayLabel()
    val seriesLabel = book.seriesDisplayLabel()
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal)
            .clickable(onClickLabel = "Открыть книгу", onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = abredBookCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbredBookCover(
                model = book.coverUrl,
                modifier = Modifier
                    .width(AbredSizes.BookHeroCoverWidth)
                    .height(AbredSizes.BookHeroCoverHeight),
            )
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = when {
                    extraLargeText -> 4
                    largeText -> 3
                    else -> 2
                },
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
                Spacer(Modifier.height(AbredSpacing.Sm))
                AbredBookProgress(
                    progressPercent = book.progressPercent,
                    showPercentLabel = showProgressPercent,
                )
                Spacer(Modifier.height(AbredSpacing.Xs))
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AbredSizes.ControlHeight),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = AbredSpacing.Sm),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(AbredSizes.IconSmall),
                    )
                    Spacer(Modifier.width(AbredSpacing.Xxs))
                    Text(
                        if (showProgressPercent && book.progressPercent > 0.05) {
                            "Продолжить · ${book.progressPercent.roundToInt()}%"
                        } else {
                            "Продолжить"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sourceLabel.isNotBlank()) {
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    AbredBookSourceLabel(
                        text = sourceLabel,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}
