package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.TextCatalogViewModel

/** Search authors only through the registered source's own URL builder and paging. */
@Composable
internal fun PreparedTextAuthorScreen(
    sourceCode: String,
    authorName: String,
    onBack: () -> Unit,
    onRead: (String) -> Unit,
    onListen: (String) -> Unit,
    showProgressPercent: Boolean,
    onPlaySavedAudio: (String) -> Unit,
) {
    val vm: TextCatalogViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var initialized by rememberSaveable(sourceCode, authorName) { mutableStateOf(false) }
    LaunchedEffect(sourceCode, authorName) {
        if (!initialized) {
            vm.openAuthorSearch(sourceCode, authorName)
            initialized = true
        }
    }
    Column {
        if (!initialized) LinearProgressIndicator()
        else {
            if (!state.canGoBack) AbredBackTopBar(title = "Книги автора", onBack = onBack)
            PreparedTextCatalogScreen(onRead, onListen, showProgressPercent, onPlaySavedAudio, vm)
        }
    }
}
