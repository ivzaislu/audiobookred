package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BookCardDto
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.CatalogUiState

/** Catalog rendered completely from one PagingData stream. */
@Composable
internal fun PagingCatalogScreen(
    state: CatalogUiState,
    books: LazyPagingItems<BookCardDto>,
    listState: LazyListState,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchAllSources: (Boolean) -> Unit,
    onGenre: (String?) -> Unit,
    onSource: (String) -> Unit,
    onBook: (String) -> Unit,
    onRefreshMetadata: () -> Unit,
    showProgressPercent: Boolean,
    browserChallengeSession: BrowserChallengeSession?,
) {
    var text by remember(state.query) { mutableStateOf(state.query) }
    val focusManager = LocalFocusManager.current
    val submitSearch: () -> Unit = {
        focusManager.clearFocus()
        onSearch(text)
    }
    val onTextChange: (String) -> Unit = { value ->
        text = value
        if (value.isBlank() && state.query.isNotBlank()) onClearSearch()
    }
    val refreshAll: () -> Unit = {
        if (state.genres.isEmpty()) onRefreshMetadata()
        books.refresh()
    }
    val refreshState = books.loadState.refresh
    val contentAlpha = rememberSelectionFade(
        listOf(
            state.query,
            state.selectedGenreId,
            state.selectedSource,
            state.searchAllSources,
        )
    )

    val challengeState = browserChallengeSession?.let { session ->
        session.uiState.collectAsStateWithLifecycle().value
    }

    if (
        browserChallengeSession != null &&
        challengeState?.required == true
    ) {
        CatalogBrowserChallengeContent(
            state = state,
            text = text,
            onTextChange = onTextChange,
            onSearch = submitSearch,
            onSearchAllSources = onSearchAllSources,
            onGenre = onGenre,
            onSource = onSource,
            session = browserChallengeSession,
            requestId = challengeState.requestId,
        )
        return
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth()
                .graphicsLayer { alpha = contentAlpha },
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
        ) {
            item(key = "catalog-header") {
                CatalogHeaderContent(
                    state = state,
                    text = text,
                    onTextChange = onTextChange,
                    onSearch = submitSearch,
                    onSearchAllSources = onSearchAllSources,
                    onGenre = onGenre,
                    onSource = onSource,
                )
            }

            when {
                refreshState is LoadState.Error && books.itemCount == 0 -> {
                    item(key = "catalog-error") {
                        CatalogLoadErrorState(
                            message = refreshState.error.message ?: "Не удалось загрузить каталог",
                            onRetry = refreshAll,
                        )
                    }
                }

                refreshState is LoadState.NotLoading && books.itemCount == 0 -> {
                    item(key = "catalog-empty") {
                        CatalogEmptyResultState()
                    }
                }

                else -> pagedBookShelves(
                    items = books,
                    keyPrefix =
                        "catalog-" +
                            state.query + "-" +
                            state.selectedGenreId.orEmpty() + "-" +
                            state.selectedSource + "-" +
                            state.searchAllSources,
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }
        }
    }
}
