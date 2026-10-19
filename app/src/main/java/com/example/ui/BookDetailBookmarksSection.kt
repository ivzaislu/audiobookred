package com.example.ui

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
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.BookDetailDesign
import com.example.ui.theme.abredCardColors
import com.example.ui.theme.abredFlatCardElevation
import com.example.ui.theme.abredOutlineBorder

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
            colors = abredCardColors(),
            elevation = abredFlatCardElevation(),
            border = abredOutlineBorder(opacity = AbredOpacity.Soft),
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
                            modifier = Modifier.size(BookDetailDesign.BookmarkPlaySize),
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
                        Modifier.padding(start = BookDetailDesign.BookmarkContentInset),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

