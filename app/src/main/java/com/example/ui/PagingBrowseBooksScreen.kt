package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.data.model.BookCardDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.BrowseUiState

/** Author/Narrator/Genre/Search/Source rendered from the same PagingData path. */
@Composable
internal fun PagingBrowseBooksScreen(
    state: BrowseUiState,
    books: LazyPagingItems<BookCardDto>,
    listState: LazyListState,
    onBack: () -> Unit,
    onBook: (String) -> Unit,
    showProgressPercent: Boolean,
) {
    val refreshAll: () -> Unit = { books.refresh() }
    val refreshState = books.loadState.refresh
    val kindLabel = browseKindLabel(state.target.kind)
    val icon = browseKindIcon(state.target.kind)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
        ) {
            item(key = "browse-top-bar") {
                AbredBackTopBar(
                    title = kindLabel,
                    onBack = onBack,
                )
            }

            item(key = "browse-hero") {
                PagingBrowseHero(
                    title = state.target.name,
                    icon = icon,
                    itemCount = books.itemCount,
                )
                Spacer(Modifier.size(AbredSpacing.Xs))
            }

            when {
                refreshState is LoadState.Error && books.itemCount == 0 -> {
                    item(key = "browse-error") {
                        PagingScreenError(
                            refreshState.error.message ?: "Не удалось загрузить книги",
                            refreshAll,
                        )
                    }
                }

                refreshState is LoadState.NotLoading && books.itemCount == 0 -> {
                    item(key = "browse-empty") {
                        AbredEmptyState(
                            icon = Icons.Default.MenuBook,
                            title = "Книг пока нет",
                        )
                    }
                }

                else -> pagedBookShelves(
                    items = books,
                    keyPrefix = "browse-" + state.target.kind + "-" + state.target.id,
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }
        }
    }
}
