package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.model.BookmarkUiItem
import com.example.ui.theme.AbredSpacing

@Composable
internal fun BookmarkListV2(
    items: List<BookmarkUiItem>,
    queryActive: Boolean,
    onPlay: (BookmarkUiItem) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (items.isEmpty()) {
        LibraryEmptyState(if (queryActive) "Ничего не найдено" else "Закладок пока нет")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        items(
            count = items.size,
            key = { index -> "library-bookmark-${items[index].bookmark.id.ifBlank { index.toString() }}-$index" },
        ) { index ->
            val item = items[index]
            LibraryBookmarkCard(
                item = item,
                onPlay = { onPlay(item) },
                onDelete = { onDelete(item.bookmark.id) },
            )
        }
    }
}
