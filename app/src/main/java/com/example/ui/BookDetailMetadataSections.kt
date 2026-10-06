package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText

@Composable
internal fun BookDetailGenresAndDuration(
    book: BookDetailDto,
    ruTrackerBook: Boolean,
    onGenre: (GenreDto) -> Unit,
) {
    if (book.genres.isEmpty() && book.durationSeconds <= 0) return

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
            if (book.genres.isNotEmpty()) {
                Spacer(Modifier.width(AbredSpacing.Xs))
            }
            DurationPill(book.durationSeconds)
        }
    }
}

@Composable
internal fun BookDetailSourceSection(
    book: BookDetailDto,
    onSelectSource: (String) -> Unit,
) {
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

@Composable
internal fun BookDetailSeriesSection(
    name: String,
    position: Int?,
    total: Int?,
    onOpen: () -> Unit,
) {
    Spacer(Modifier.height(AbredSpacing.Sm))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal)
            .clickable(onClick = onOpen),
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
                        contentDescription = null,
                        modifier = Modifier.size(AbredSizes.IconSmall),
                    )
                }
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.abredAccentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = bookDetailSeriesSubtitle(position, total)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Text(
                        subtitle,
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

internal fun bookDetailSeriesSubtitle(
    position: Int?,
    total: Int?,
): String = buildString {
    if (position != null) append("Книга $position")
    if (position != null && total != null) {
        append(" из $total")
    } else if (total != null) {
        append("$total книг")
    }
}
