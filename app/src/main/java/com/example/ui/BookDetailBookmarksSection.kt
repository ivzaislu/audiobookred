package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun BookmarksSection(
    book: BookDetailDto,
    bookmarks: List<BookmarkDto>,
    onPlayBookmark: (BookmarkDto) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AbredSpacing.ScreenHorizontal,
                vertical = AbredSpacing.Xs,
            ),
    ) {
        Text(
            "Закладки",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            ),
        ) {
            bookmarks.forEachIndexed { index, bookmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayBookmark(bookmark) }
                        .padding(
                            start = AbredSpacing.Sm,
                            top = AbredSpacing.Xs,
                            bottom = AbredSpacing.Xs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Bookmark,
                                null,
                                modifier = Modifier.size(AbredSizes.IconSmall),
                            )
                        }
                    }
                    Spacer(Modifier.width(AbredSpacing.Sm))
                    Column(Modifier.weight(1f)) {
                        val chapterTitle = book.chapters.getOrNull(bookmark.chapterIndex)?.title
                            ?: "Глава ${bookmark.chapterIndex + 1}"
                        Text(
                            chapterTitle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            detailFormatMillis(bookmark.positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                        Icon(Icons.Default.DeleteOutline, "Удалить")
                    }
                }
                if (index != bookmarks.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
