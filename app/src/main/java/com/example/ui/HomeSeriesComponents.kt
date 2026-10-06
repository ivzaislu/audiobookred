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
internal fun NextSeriesCard(series: MySeriesDto, onClick: () -> Unit) {
    val focus = series.nextBook ?: series.currentBook ?: return
    val book = focus.book
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal)
            .clickable(onClickLabel = "Открыть цикл", onClick = onClick),
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
                    .width(AbredSizes.CompactCoverWidth)
                    .height(AbredSizes.CompactCoverHeight),
            )
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    series.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.abredAccentText,
                    maxLines = if (largeText) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = when {
                        extraLargeText -> 4
                        largeText -> 3
                        else -> 2
                    },
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xs))
                Text(
                    if (series.completedCount > 0 || series.availableCount > 0) {
                        "${series.completedCount}/${series.availableCount} книг"
                    } else {
                        "Открыть цикл"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.Icon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PopularPeriodSelector(
    selected: HomePopularPeriod,
    onSelect: (HomePopularPeriod) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = AbredSizes.MinimumTouchTarget),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = AbredSpacing.Sm,
                    end = AbredSpacing.Xs,
                    top = AbredSpacing.Xs,
                    bottom = AbredSpacing.Xs,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected.label,
                    modifier = Modifier.widthIn(max = 120.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = if (abredLargeFontScale()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(AbredSpacing.Xxs))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            HomePopularPeriod.entries.forEach { period ->
                DropdownMenuItem(
                    modifier = Modifier.semantics { this.selected = period == selected },
                    text = {
                        Text(
                            period.label,
                            modifier = Modifier.widthIn(max = 180.dp),
                            fontWeight = if (period == selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = if (abredLargeFontScale()) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (period == selected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(period)
                    },
                )
            }
        }
    }
}
