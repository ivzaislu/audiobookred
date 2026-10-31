package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.textbook.TextChapterEntity
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.TextLibraryUiState
import com.example.ui.viewmodel.TextLibraryViewModel
import kotlinx.coroutines.CancellationException

/** Book actions can be hosted by either the source card or the offline card. */
@Composable
internal fun PreparedTextBookActions(
    bookId: String,
    onRead: () -> Unit,
    onListen: () -> Unit,
    showProgressPercent: Boolean,
    onPlaySavedAudio: (String) -> Unit,
    enabled: Boolean = true,
) {
    val vm: TextLibraryViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var showSave by rememberSaveable(bookId) { mutableStateOf(false) }
    var chapters by remember(bookId) { mutableStateOf(emptyList<TextChapterEntity>()) }
    var loading by remember(bookId) { mutableStateOf(false) }
    var loadError by remember(bookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(showSave, bookId) {
        if (showSave) {
            loading = true
            loadError = null
            try { chapters = vm.narrationChapters(bookId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { loadError = error.message ?: "Не удалось прочитать главы книги." }
            finally { loading = false }
        }
    }
    val request = rememberBookDownloadRequester(
        onStart = { vm.saveAudio(bookId, it.takeUnless { value -> value == "all" }) },
        onResume = vm::resumeAudio, onRetry = vm::resumeAudio,
    )
    TextBookReadingActions(onRead, onListen, enabled = enabled,
        progress = state.membership.books[bookId]?.progressPercent ?: 0.0,
        showProgressPercent = showProgressPercent,
        onSaveAudio = { showSave = true }, saving = state.startingAudioExport, message = state.message)
    if (showSave) {
        TextBookNarrationSheet(bookId, chapters, state, loading, loadError,
            onDismiss = { showSave = false },
            onStart = { request(BookDownloadAction.Start, it ?: "all") },
            onPause = vm::pauseAudio, onResume = vm::resumeAudio, onRemove = vm::removeAudio,
            onPlaySaved = onPlaySavedAudio)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextBookNarrationSheet(
    bookId: String,
    chapters: List<TextChapterEntity>,
    state: TextLibraryUiState,
    loading: Boolean,
    loadError: String?,
    onDismiss: () -> Unit,
    onStart: (String?) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPlaySaved: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
            item(key = "title") { Text("Сохранить озвучку", style = MaterialTheme.typography.titleLarge) }
            if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            loadError?.let { error -> item(key = "error") {
                Text(error, color = MaterialTheme.colorScheme.error)
            } }
            item(key = "all") {
                TextButton(onClick = { onStart(null) }, enabled = !loading && loadError == null && !state.startingAudioExport) {
                    Text("Всю книгу")
                }
            }
            val selectable = chapters.filterNot { it.automatic }
            if (selectable.isNotEmpty()) item(key = "chapters") { Text("Отдельную главу", style = MaterialTheme.typography.titleMedium) }
            items(selectable, key = { "chapter:${it.id}" }) { chapter ->
                TextButton(onClick = { onStart(chapter.id) }, enabled = !loading && loadError == null && !state.startingAudioExport) {
                    Text(chapter.title)
                }
            }
            state.message?.let { message -> item(key = "message") { Text(message) } }
            item(key = "exports") {
                TextBookAudioExports(bookId, chapters, state, onPause, onResume, onRemove, onPlaySaved)
            }
        }
    }
}
