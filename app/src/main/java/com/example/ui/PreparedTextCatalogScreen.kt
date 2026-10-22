package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.data.model.BookDetailDto
import com.example.data.textsource.RemoteTextBook
import com.example.data.textsource.RemoteTextDownload
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.CatalogUiState
import com.example.ui.viewmodel.TextCatalogState
import com.example.ui.viewmodel.TextCatalogViewModel

@Composable
internal fun PreparedTextCatalogScreen(
    onOpenTextBook: (String) -> Unit,
    vm: TextCatalogViewModel = hiltViewModel(),
    headerActions: (@Composable () -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val destinations = rememberSaveableStateHolder()
    var savedDestinations by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(state.navigationKeys) {
        savedDestinations.filter { it !in state.navigationKeys }.forEach(destinations::removeState)
        savedDestinations = state.navigationKeys
    }
    BackHandler(state.canGoBack || state.busy) { vm.back() }
    LaunchedEffect(state.openedBookId) {
        state.openedBookId?.let { id -> vm.consumeOpened(); onOpenTextBook(id) }
    }
    destinations.SaveableStateProvider(state.destinationKey) {
        if (state.isBookDestination) TextCatalogBookPage(state, vm)
        else TextCatalogList(state, vm, headerActions = headerActions,
            onClearSearch = {
                val rootKey = vm.catalogRootKey(state.sourceCode)
                if (rootKey != state.destinationKey) destinations.removeState(rootKey)
                vm.search("")
            },
            onSource = { code ->
                if (code != state.sourceCode) {
                    destinations.removeState(vm.catalogRootKey(code))
                    vm.selectSource(code)
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextCatalogList(state: TextCatalogState, vm: TextCatalogViewModel, onClearSearch: () -> Unit, onSource: (String) -> Unit, headerActions: (@Composable () -> Unit)?) {
    val books = remember(state.destinationKey) { vm.catalogBooks() }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var query by rememberSaveable(state.query) { mutableStateOf(state.query) }
    val focus = LocalFocusManager.current
    val catalog = remember(state.sourceCode, state.query, state.sources) {
        CatalogUiState(selectedSource = state.sourceCode, enabledSources = state.sources.map { it.code }.toSet(),
            query = state.query, searchAllSources = false)
    }
    val submit: () -> Unit = { focus.clearFocus(); if (query.isBlank()) onClearSearch() else vm.search(query) }
    PullToRefreshBox(
        isRefreshing = books.loadState.refresh is LoadState.Loading && books.itemCount > 0,
        onRefresh = books::refresh, modifier = Modifier.fillMaxSize(),
    ) {
        PagingCatalogScreen(
            state = catalog, books = books, listState = listState, onSearch = vm::search,
            onClearSearch = onClearSearch, onSearchAllSources = {}, onGenre = {}, onSource = onSource,
            onBook = vm::openBook, onRefreshMetadata = {}, showProgressPercent = false, browserChallengeSession = null,
            sourceLabelOverride = state.sourceName,
            bookRow = { book -> TextCatalogBookRow(book, state.sourceName, vm::listingDetails, vm::openBook) },
            headerContent = {
                if (state.isCollection) {
                    AbredBackTopBar(title = "Книги", onBack = { vm.back() }, actions = headerActions)
                    PagingBrowseHero(title = state.page?.title ?: state.sourceName,
                        icon = Icons.Default.MenuBook, itemCount = books.itemCount)
                    Spacer(Modifier.height(AbredSpacing.Xs))
                } else {
                    CatalogHeaderContent(
                        state = catalog, text = query, headerActions = headerActions,
                        onTextChange = { value ->
                            query = value
                            if (value.isBlank() && state.query.isNotBlank()) onClearSearch()
                        },
                        onSearch = submit, onSearchAllSources = {}, onGenre = {}, onSource = onSource,
                        sourceOptions = state.sources.map { CatalogSourceOption(it.code, it.name, null) },
                        sourceIcon = Icons.Default.MenuBook, searchScopeEnabled = false, selectedSourceLabel = state.sourceName,
                    )
                }
                state.error?.let { error ->
                    Text(error, modifier = Modifier.fillMaxWidth().padding(horizontal = AbredSpacing.ScreenHorizontal,
                        vertical = AbredSpacing.Xs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun TextCatalogBookPage(state: TextCatalogState, vm: TextCatalogViewModel) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxHeight().widthIn(max = AbredSizes.ContentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl)) {
            item(key = "header") { BookDetailPageTopBar(onBack = { vm.back() }) }
            if (state.loading) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = AbredSpacing.ScreenHorizontal))
            }
            if (state.book == null) state.error?.let { error -> item(key = "error") {
                CatalogLoadErrorState(error, vm::retry)
            } }
            state.book?.let { book ->
                item(key = "hero") {
                    BookDetailHeroSection(remember(book) { BookDetailDto(id = book.id, title = book.title, coverUrl = book.coverUrl.orEmpty()) })
                }
                item(key = "actions") { TextBookImportActions(book, state, vm::importAndOpen, vm::cancelImport) }
                item(key = "metadata") {
                    TextBookMetadataSection(book, state.sourceName, enabled = !state.busy, onCollection = vm::collection)
                }
                if (book.description.isNotBlank()) item(key = "description") {
                    DescriptionSection(book.description, state.destinationKey)
                }
            }
        }
    }
}

@Composable
private fun TextBookImportActions(
    book: RemoteTextBook,
    state: TextCatalogState,
    onOpen: (RemoteTextDownload?) -> Unit,
    onCancel: () -> Unit,
) {
    var choosingFormat by rememberSaveable(book.url) { mutableStateOf(false) }
    if (choosingFormat && !state.busy) TextBookFormatDialog(
        book = book, onDismiss = { choosingFormat = false },
        onSelect = { option -> choosingFormat = false; onOpen(option) },
    )
    BookDetailActionsCard {
        Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
            Button(
                onClick = { if (state.localBookId != null) onOpen(null) else choosingFormat = true },
                enabled = !state.busy && !state.loading && (state.localBookId != null || book.downloads.any { it.canImport }),
                modifier = Modifier.fillMaxWidth().height(AbredSizes.ControlHeight),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(AbredSizes.Icon))
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text(when {
                    state.importing -> "Добавление книги…"
                    state.localBookId != null -> "Открыть в библиотеке"
                    else -> "Добавить в библиотеку"
                }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text("Отменить загрузку") }
            }
            state.error?.let { error ->
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let { notice ->
                Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.localBookId == null && book.downloads.none { it.canImport }) {
                Text("Загрузка книги недоступна у источника.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
