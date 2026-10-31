package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.textbook.TextBookMetadata
import com.example.ui.viewmodel.TextLibraryViewModel
import com.example.ui.theme.AbredSpacing

/** A local book card and a cycle have their own entry-scoped state, independent of their caller. */
@Composable
internal fun PreparedTextBookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onRead: (String) -> Unit,
    onListen: (String) -> Unit,
    onSeries: (String) -> Unit,
    showProgressPercent: Boolean,
    onAuthorSearch: (String, String) -> Unit,
    onPlaySavedAudio: (String) -> Unit,
) {
    val vm: TextLibraryViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(bookId) {
        try { vm.refreshSourceDetails(bookId) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the offline card usable while its source is unavailable. */ }
    }
    val book = state.books.firstOrNull { it.id == bookId }
    if (book != null) {
        val sourceCode = state.membership.books[bookId]?.primarySource.orEmpty()
        TextLibraryBookDetailScreen(book, state.metadata[bookId] ?: TextBookMetadata(),
            state.membership.books[bookId], onBack,
            onRead = { onRead(bookId) }, onListen = { onListen(bookId) }, onSeries = onSeries,
            actions = { PreparedTextBookActions(bookId, { onRead(bookId) }, { onListen(bookId) },
                showProgressPercent, onPlaySavedAudio) },
            onAuthor = if (vm.supportsTextSource(sourceCode)) {
                { person -> onAuthorSearch(sourceCode, person.name) }
            } else null)
    } else {
        TextLibraryDestinationState(state.membershipLoaded,
            state.membershipError ?: "Книга удалена из библиотеки или недоступна.", onBack)
    }
}

@Composable
internal fun PreparedTextCycleScreen(
    seriesKey: String,
    onBack: () -> Unit,
    onBook: (String) -> Unit,
    onListen: (String) -> Unit,
) {
    val vm: TextLibraryViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val cycle = state.membership.availableSeries[seriesKey]
    if (cycle != null) {
        TextLibraryCycleScreen(cycle, state.membership.seriesBooks[seriesKey].orEmpty(),
            onBack, onBook, onListen)
    } else {
        TextLibraryDestinationState(state.membershipLoaded,
            state.membershipError ?: "В этом цикле нет сохранённых книг.", onBack)
    }
}

@Composable
private fun TextLibraryDestinationState(loaded: Boolean, message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BookDetailPageTopBar(onBack = onBack)
        Box(Modifier.fillMaxWidth().weight(1f).padding(AbredSpacing.ScreenHorizontal),
            contentAlignment = Alignment.Center) {
            if (loaded) Text(message) else CircularProgressIndicator()
        }
    }
}
