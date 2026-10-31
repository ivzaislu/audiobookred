package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.data.model.BookDetailDto
import com.example.data.textsource.RemoteTextBook
import com.example.data.textsource.RemoteTextDownload
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.CatalogUiState
import com.example.ui.viewmodel.TextCatalogState
import com.example.ui.viewmodel.TextCatalogViewModel

@Composable
internal fun PreparedTextCatalogScreen(
    onReadTextBook: (String) -> Unit,
    onListenTextBook: (String) -> Unit,
    showProgressPercent: Boolean,
    onPlaySavedAudio: (String) -> Unit,
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
    destinations.SaveableStateProvider(state.destinationKey) {
        if (state.isBookDestination) TextCatalogBookPage(state, vm, onReadTextBook, onListenTextBook, showProgressPercent, onPlaySavedAudio)
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
                        sourceOptions = state.sources.map { CatalogSourceOption(it.code, it.name, sourceFaviconRes(it.code)) },
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
private fun TextCatalogBookPage(
    state: TextCatalogState, vm: TextCatalogViewModel,
    onRead: (String) -> Unit, onListen: (String) -> Unit, showProgressPercent: Boolean,
    onPlaySavedAudio: (String) -> Unit,
) {
    TextBookDetailPage(
        book = state.book?.let { BookDetailDto(id = it.id, title = it.title, coverUrl = it.coverUrl.orEmpty()) },
        onBack = { vm.back() },
        loading = state.loading,
        error = state.error,
        onRetry = vm::retry,
        description = state.book?.description.orEmpty(),
        descriptionKey = state.destinationKey,
        actions = { state.book?.let {
            TextCatalogBookActions(it, state, vm::importBook, vm::cancelImport, onRead, onListen,
                localActions = { id -> PreparedTextBookActions(id, { onRead(id) }, { onListen(id) }, showProgressPercent, onPlaySavedAudio,
                    enabled = !state.busy && !state.loading) })
        } },
        metadata = { state.book?.let {
            TextBookMetadataSection(it, state.sourceName, enabled = !state.busy, onCollection = vm::collection)
        } },
    )
}

@Composable
internal fun TextCatalogBookActions(
    book: RemoteTextBook,
    state: TextCatalogState,
    onImport: (RemoteTextDownload?) -> Unit,
    onCancel: () -> Unit,
    onRead: (String) -> Unit,
    onListen: (String) -> Unit,
    localActions: @Composable (String) -> Unit = { local ->
        TextBookReadingActions(onRead = { onRead(local) }, onListen = { onListen(local) },
            enabled = !state.busy && !state.loading)
    },
) {
    val local = state.localBookId
    if (local != null) localActions(local)
    else TextBookImportActions(book, state, onImport, onCancel)
}

@Composable
private fun TextBookImportActions(
    book: RemoteTextBook,
    state: TextCatalogState,
    onOpen: (RemoteTextDownload?) -> Unit,
    onCancel: () -> Unit,
) {
    var choosingFormat by rememberSaveable(book.url) { mutableStateOf(false) }
    val context = LocalContext.current
    var awaitingStorage by rememberSaveable(book.url) { mutableStateOf(false) }
    var pendingFormat by rememberSaveable(book.url) { mutableStateOf<String?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val awaiting = awaitingStorage
        val code = pendingFormat
        awaitingStorage = false
        pendingFormat = null
        if (awaiting && granted) {
            val option = book.downloads.firstOrNull { it.code == code && it.canImport }
            if (code == null || option != null) onOpen(option)
        } else if (awaiting) {
            Toast.makeText(context, "Разрешите доступ к хранилищу для сохранения книг в Download/AudioBookRed/Books.", Toast.LENGTH_LONG).show()
        }
    }
    val requestOpen: (RemoteTextDownload?) -> Unit = { option ->
        if (state.localBookId == null && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            awaitingStorage = true
            pendingFormat = option?.code
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else onOpen(option)
    }
    if (choosingFormat && !state.busy) TextBookFormatDialog(
        book = book, onDismiss = { choosingFormat = false },
        onSelect = { option -> choosingFormat = false; requestOpen(option) },
    )
    BookDetailActionsCard {
        Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
            TextBookActionButton(
                text = when {
                    state.importing -> "Добавление книги…"
                    else -> "Добавить в библиотеку"
                },
                icon = Icons.Default.MenuBook,
                onClick = { choosingFormat = true },
                enabled = !state.busy && !state.loading && book.downloads.any { it.canImport },
            )
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
