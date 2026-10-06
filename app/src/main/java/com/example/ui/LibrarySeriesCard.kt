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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySeriesCard(
    item: MySeriesDto,
    selected: Boolean,
    onOpen: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectForDelete: () -> Unit,
    onContinueBook: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val focus = item.currentBook ?: item.nextBook
    val focusBook = focus?.book
    val haptic = LocalHapticFeedback.current
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val detail = librarySeriesProgressDetail(item)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .combinedClickable(
                onClickLabel = "Открыть цикл",
                onLongClickLabel = "Выбрать цикл для удаления",
                onClick = {
                    if (selected) onClearSelection() else onOpen()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelectForDelete()
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
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (focusBook != null) {
                BookCoverImage(
                    model = focusBook.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .width(68.dp)
                        .height(100.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                Surface(
                    modifier = Modifier
                        .width(68.dp)
                        .height(100.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LibraryBooks, null, Modifier.size(28.dp))
                    }
                }
            }

            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.abredAccentText,
                    maxLines = when {
                        extraLargeText -> 4
                        largeText -> 3
                        else -> 2
                    },
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (largeText) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xs))
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AbredSizes.ProgressTrack)
                        .clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                if (!item.isCompleted && focusBook != null) {
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    TextButton(
                        onClick = { onContinueBook(focusBook.id) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            null,
                            Modifier.size(AbredSizes.IconSmall),
                        )
                        Spacer(Modifier.width(AbredSpacing.Xxs))
                        Text(if (item.currentBook != null) "Продолжить" else "Начать следующую")
                    }
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
                    Icon(Icons.Default.DeleteOutline, "Удалить цикл")
                }
            } else {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
