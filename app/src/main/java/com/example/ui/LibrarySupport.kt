package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookCardDto
import com.example.data.model.BookmarkUiItem
import com.example.data.model.MySeriesDto

@Composable
internal fun LibraryEmptyState(text: String) {
    AbredEmptyState(
        icon = Icons.Default.LibraryBooks,
        title = text,
        modifier = Modifier.fillMaxSize(),
    )
}

internal fun BookCardDto.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) ||
        authors.any { it.name.contains(query, ignoreCase = true) } ||
        narrators.any { it.name.contains(query, ignoreCase = true) }
}

internal fun MySeriesDto.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val focus = currentBook?.book ?: nextBook?.book
    return name.contains(query, ignoreCase = true) || focus?.matchesLibraryQuery(query) == true
}

internal fun BookmarkUiItem.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return bookTitle.contains(query, ignoreCase = true) ||
        chapterTitle.contains(query, ignoreCase = true) ||
        bookmark.note.contains(query, ignoreCase = true)
}

internal fun DownloadBookEntity.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) || sourceCode.contains(query, ignoreCase = true)
}
