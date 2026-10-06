package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookCardDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBookCard(
    book: BookCardDto,
    selected: Boolean,
    selectionActive: Boolean,
    showProgressPercent: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectForDelete: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val metrics = libraryBookRowTextMetrics()
    val largeText = metrics.largeText
    val extraLargeText = metrics.extraLargeText
    val seriesLabel = book.seriesDisplayLabel()
    val sourceLabel = book.sourceDisplayLabel()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .combinedClickable(
                onClickLabel = "Открыть книгу",
                onLongClickLabel = if (canDelete) "Выбрать для удаления" else null,
                onClick = {
                    if (selectionActive) onClearSelection() else onOpen()
                },
                onLongClick = {
                    if (canDelete) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectForDelete()
                    }
                },
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            LibraryBookRowMainContent(
                coverUrl = book.coverUrl,
                sourceLabel = sourceLabel,
                sourceReserve = metrics.sourceReserve,
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = metrics.titleMaxLines,
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
            if (selected) {
                FilledIconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.DeleteOutline, "Удалить")
                }
            } else {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
