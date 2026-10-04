package com.example.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.CatalogViewModel
import kotlinx.coroutines.launch

/** Nav3-scoped Catalog host. Filter/metadata state belongs only to CatalogViewModel. */
@Composable
internal fun PreparedCatalogScreen(
    listState: LazyListState,
    onBook: (String) -> Unit,
    showProgressPercent: Boolean,
) {
    val viewModel: CatalogViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resetViewport: () -> Unit = { scope.launch { listState.scrollToItem(0) } }
    val browserChallengeSession = rememberInlineBrowserChallengeSession()

    PagingCatalogHost(
        state = state,
        listState = listState,
        onSearch = { query -> resetViewport(); viewModel.search(query) },
        onClearSearch = { resetViewport(); viewModel.clearSearch() },
        onSearchAllSources = { enabled -> resetViewport(); viewModel.setSearchAllSources(enabled) },
        onGenre = { genreId -> resetViewport(); viewModel.selectGenre(genreId) },
        onSource = { source -> resetViewport(); viewModel.selectSource(source) },
        onBook = onBook,
        onRefreshMetadata = viewModel::refreshMetadata,
        showProgressPercent = showProgressPercent,
        browserChallengeSession = browserChallengeSession,
    )
}
