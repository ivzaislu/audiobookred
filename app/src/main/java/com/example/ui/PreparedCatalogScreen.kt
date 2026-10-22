package com.example.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredSizes
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.CatalogViewModel
import com.example.ui.viewmodel.TextCatalogViewModel
import kotlinx.coroutines.launch

/** Text sources enter the normal local text library, independently of audio providers. */
@Composable
internal fun PreparedCatalogScreen(
    listState: LazyListState,
    onBook: (String) -> Unit,
    showProgressPercent: Boolean,
    onOpenTextBook: (String) -> Unit,
) {
    var textBooks by rememberSaveable { mutableStateOf(false) }
    val modes = rememberSaveableStateHolder()
    val textViewModel: TextCatalogViewModel = hiltViewModel()
    val modeActions: @Composable () -> Unit = {
        CatalogModeActions(textBooks = textBooks, onSelect = { textBooks = it })
    }
    Box(Modifier.fillMaxSize()) {
        modes.SaveableStateProvider(if (textBooks) "books" else "audio") {
            if (textBooks) PreparedTextCatalogScreen(onOpenTextBook, textViewModel, modeActions)
            else PreparedAudioCatalogScreen(listState, onBook, showProgressPercent, modeActions)
        }
    }
}

/** Nav3-scoped Catalog host. Filter/metadata state belongs only to CatalogViewModel. */
@Composable
private fun PreparedAudioCatalogScreen(
    listState: LazyListState,
    onBook: (String) -> Unit,
    showProgressPercent: Boolean,
    modeActions: @Composable () -> Unit,
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
        headerActions = modeActions,
    )
}

/** Compact catalogue switch; descriptions remain available to accessibility services. */
@Composable
private fun CatalogModeActions(textBooks: Boolean, onSelect: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs)) {
        listOf(false, true).forEach { books ->
            val active = textBooks == books
            Surface(
                onClick = { onSelect(books) },
                modifier = Modifier.size(AbredSizes.MinimumTouchTarget).semantics { selected = active },
                shape = MaterialTheme.shapes.medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(
                        imageVector = if (books) Icons.Default.MenuBook else Icons.Default.Headphones,
                        contentDescription = if (books) "Книги" else "Аудиокниги",
                    )
                }
            }
        }
    }
}
