package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookmarkUiItem
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSpacing

@Composable
internal fun LibraryBookmarkCard(
    item: BookmarkUiItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Воспроизвести закладку",
                onClick = onPlay,
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = abredBookCardBorder(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                model = item.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(58.dp)
                    .height(82.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    item.bookTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (largeText) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    "${item.chapterTitle} · ${libraryFormatBookmarkMillis(item.bookmark.positionMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (largeText) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.bookmark.note.trim().takeIf { it.isNotEmpty() }?.let { note ->
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Text(
                        "«$note»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (extraLargeText) 4 else if (largeText) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, "Удалить закладку")
            }
        }
    }
}
