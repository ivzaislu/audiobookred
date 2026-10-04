package com.example.ui

import com.example.data.model.BookDetailDto
import com.example.data.source.StandaloneSourceRegistry

/**
 * Book detail source-series navigation is exposed only when the provider registry
 * says that the listing/paging contract is verified.
 *
 * The current Book Detail host resolves both series click paths through a seed book
 * and the source-native series loader, so book-level series metadata alone is not
 * sufficient to make the card navigable.
 */
internal fun isRuTrackerBook(book: BookDetailDto): Boolean {
    val source = book.selectedSource.ifBlank {
        book.primarySource.ifBlank { book.sourceCodes.firstOrNull().orEmpty() }
    }
    return source.equals("rutracker", ignoreCase = true)
}

internal fun canOpenBookSeries(book: BookDetailDto): Boolean {
    val source = book.selectedSource.ifBlank {
        book.primarySource.ifBlank { book.sourceCodes.firstOrNull().orEmpty() }
    }
    return StandaloneSourceRegistry.supportsSeries(source)
}
