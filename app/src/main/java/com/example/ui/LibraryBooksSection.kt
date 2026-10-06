package com.example.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import com.example.data.model.BookCardDto
import com.example.ui.theme.AbredSpacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBooksListV2(
    books: List<BookCardDto>,
    emptyText: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var selectedForDelete by remember { mutableStateOf<String?>(null) }
    if (books.isEmpty()) {
        LibraryEmptyState(emptyText)
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
            count = books.size,
            key = { index -> "library-book-${books[index].id.ifBlank { books[index].title }}-$index" },
        ) { index ->
            val book = books[index]
            val selected = selectedForDelete == book.id && onDelete != null

            LibraryBookCard(
                book = book,
                selected = selected,
                selectionActive = selectedForDelete != null,
                showProgressPercent = showProgressPercent,
                canDelete = onDelete != null,
                onOpen = { onBook(book.id) },
                onClearSelection = { selectedForDelete = null },
                onSelectForDelete = { selectedForDelete = book.id },
                onDelete = {
                    selectedForDelete = null
                    onDelete?.invoke(book.id)
                },
            )
        }
    }
}
