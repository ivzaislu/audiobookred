package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.ui.paging.BookPageRequest
import com.example.ui.paging.BookPagingViewModel
import com.example.ui.theme.AbredSpacing

@Composable
internal fun SimilarBooksSection(
    bookId: String,
    showProgressPercent: Boolean,
    onSimilarBook: (String) -> Unit,
) {
    val pagingVm: BookPagingViewModel = hiltViewModel()
    val request = remember(bookId) { BookPageRequest.Similar(bookId) }
    val flow = remember(request) { pagingVm.books(request) }
    val books = flow.collectAsLazyPagingItems()

    BookDetailSectionCard(
        modifier = Modifier.padding(top = AbredSpacing.Sm),
    ) {
        Text(
            "Похожие",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))

        when {
            books.loadState.refresh is LoadState.Loading && books.itemCount == 0 -> {
                Box(
                    Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            books.loadState.refresh is LoadState.Error && books.itemCount == 0 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Не удалось загрузить похожие книги",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    OutlinedButton(onClick = books::retry) { Text("Повторить") }
                }
            }
            books.itemCount == 0 -> {
                Text(
                    "Похожих книг пока нет",
                    modifier = Modifier.padding(vertical = AbredSpacing.Xs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = AbredSpacing.Xxs),
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Sm),
                ) {
                    items(
                        count = books.itemCount,
                        key = { index -> "detail-similar-${books.peek(index)?.id ?: "placeholder"}-$index" },
                    ) { index ->
                        books[index]?.let { similar ->
                            PagedShelfBookCard(
                                book = similar,
                                showProgressPercent = showProgressPercent,
                                onClick = { onSimilarBook(similar.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
