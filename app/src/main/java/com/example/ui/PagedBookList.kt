package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.data.model.BookCardDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import kotlin.math.roundToInt

/** Network-backed catalog/search/browse results rendered as one true vertical lazy list. */
internal fun LazyListScope.pagedBookShelves(
    items: LazyPagingItems<BookCardDto>,
    keyPrefix: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    repeat(items.itemCount) { index ->
        item(
            key = "$keyPrefix-book-$index",
            contentType = "book-row",
        ) {
            items[index]?.let { book ->
                PagedBookRowCard(
                    book = book,
                    showProgressPercent = showProgressPercent,
                    onClick = { onBook(book.id) },
                )
            } ?: Spacer(Modifier.fillMaxWidth().height(124.dp))
            Spacer(Modifier.height(AbredSpacing.Xs))
        }
    }

    val refresh = items.loadState.refresh
    val append = items.loadState.append
    when {
        refresh is LoadState.Loading && items.itemCount == 0 -> {
            item(key = "$keyPrefix-paging-initial", contentType = "paging-state") {
                PagingLoadingRow()
            }
        }
        refresh is LoadState.Error && items.itemCount == 0 -> {
            item(key = "$keyPrefix-paging-initial-error", contentType = "paging-state") {
                PagingErrorRow(
                    message = refresh.error.message ?: "Не удалось загрузить книги",
                    onRetry = items::retry,
                )
            }
        }
        append is LoadState.Loading -> {
            item(key = "$keyPrefix-paging-append", contentType = "paging-state") {
                PagingLoadingRow()
            }
        }
        append is LoadState.Error -> {
            item(key = "$keyPrefix-paging-append-error", contentType = "paging-state") {
                PagingErrorRow(
                    message = append.error.message ?: "Не удалось загрузить ещё книги",
                    onRetry = items::retry,
                )
            }
        }
    }
}

@Composable
private fun PagingLoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

@Composable
private fun PagingErrorRow(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}
