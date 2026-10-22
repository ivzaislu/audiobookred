package com.example.ui

import androidx.compose.runtime.*
import com.example.data.model.BookCardDto
import kotlinx.coroutines.CancellationException

/** Fetch missing descriptive metadata only while this row is composed; bookmarks are untouched. */
@Composable
internal fun TextCatalogBookRow(
    book: BookCardDto, sourceLabel: String, resolve: suspend (BookCardDto) -> BookCardDto, onOpen: (String) -> Unit,
) {
    var displayed by remember(book) { mutableStateOf(book) }
    val resolver by rememberUpdatedState(resolve)
    LaunchedEffect(book) {
        if (book.coverUrl.isBlank() || book.authors.isEmpty()) {
            try { displayed = resolver(book) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the known title/author and normal cover placeholder. */ }
        }
    }
    PagedBookRowCard(displayed, showProgressPercent = false, sourceLabelOverride = sourceLabel,
        onClick = { onOpen(book.id) })
}
