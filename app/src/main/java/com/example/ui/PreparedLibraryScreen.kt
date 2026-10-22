package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BookmarkUiItem
import com.example.data.narration.TextAudioReadingPosition
import com.example.data.model.MySeriesDto
import com.example.ui.viewmodel.LibraryViewModel
import com.example.ui.viewmodel.TextLibraryViewModel
import com.example.ui.viewmodel.legacyTextHistoryActivity

/** Nav3-scoped Library host. Displayed Library state comes only from LibraryViewModel/Room. */
@Composable
internal fun PreparedLibraryScreen(
    entryKey: Int,
    onBook: (String) -> Unit,
    onContinueBook: (String) -> Unit,
    onDownloadedBook: (String) -> Unit,
    onPlayDownloaded: (String) -> Unit,
    onPlayBookmark: (BookmarkUiItem) -> Unit,
    onSeries: (MySeriesDto) -> Unit,
    showProgressPercent: Boolean,
    audioSpeed: Float = 1f,
    onSetAudioSpeed: (Float) -> Unit = {},
    onRuVoiceVoiceSelected: (String, String) -> Unit = { _, _ -> },
    onPlayText: (String, String?) -> Unit = { _, _ -> },
    onListenFromText: (String, String, Double) -> Unit = { book, fragment, _ -> onPlayText(book, fragment) },
    onListenLive: (String) -> Unit = { onPlayText(it, null) },
    textHighlight: com.example.data.narration.TextPlaybackHighlight? = null,
    audioBookId: String? = null,
    audioPlaying: Boolean = false,
    audioBuffering: Boolean = false,
    audioError: String? = null,
    onToggleAudio: () -> Unit = {},
    onReaderFocusChanged: (Boolean) -> Unit = {},
    requestedTextBookId: String? = null,
    requestedAudioReadingPosition: TextAudioReadingPosition? = null,
    onAudioReadingPositionConsumed: () -> Unit = {},
    onTextBookRequestConsumed: () -> Unit = {},
) {
    val textViewModel: TextLibraryViewModel = hiltViewModel()
    val textState by textViewModel.state.collectAsStateWithLifecycle()
    // Capture the value, not the live State delegate, in callbacks retained by the old screen.
    val readerSession = textState.readerSession
    var textBookId by rememberSaveable(entryKey) { mutableStateOf<String?>(null) }
    var libraryTab by rememberSaveable(entryKey) { mutableStateOf(LibraryTabV2.History) }
    val textImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) textViewModel.importBook(uri)
    }
    if (textState.archiveEntries.isNotEmpty()) {
        TextArchiveSelectionDialog(textState.archiveEntries, textViewModel::chooseArchiveEntry, textViewModel::cancelArchiveImport)
    }
    LaunchedEffect(requestedTextBookId) {
        requestedTextBookId?.let { id ->
            val jump = requestedAudioReadingPosition?.takeIf { it.bookId == id }
            textBookId = id
            libraryTab = LibraryTabV2.Texts
            textViewModel.open(id, jump?.chapterId, preview = jump)
            onAudioReadingPositionConsumed()
            onTextBookRequestConsumed()
        }
    }
    LaunchedEffect(textBookId) {
        if (requestedTextBookId == null) {
            textBookId?.let(textViewModel::ensureOpen)
        }
    }
    if (textBookId != null) {
        TextBookReaderScreen(
            state = textState,
            onBack = {
                textViewModel.closeReader()
                textBookId = null
            },
            onBackAtPosition = { fragment, offset ->
                textViewModel.closeReader(readerSession, fragment, offset)
                textBookId = null
            },
            textHighlight = textHighlight,
            onFollowChanged = { enabled -> textViewModel.setFollowing(readerSession, enabled) },
            readingJump = textState.readingJump,
            onReadingJumpApplied = { textViewModel.readingJumpApplied(readerSession) },
            onPosition = { book, chapter, fragment, offset ->
                textViewModel.savePosition(readerSession, book, chapter, fragment, offset)
            },
            onCommitPreview = { fragment, offset -> textViewModel.commitPreview(readerSession, fragment, offset) },
            onReturnToBookmark = { textViewModel.returnToBookmark(readerSession) },
            onListen = { book, fragment ->
                textViewModel.allowFollowing(readerSession)
                onPlayText(book, fragment)
            },
            onListenFromText = { book, fragment, fraction ->
                textViewModel.allowFollowing(readerSession)
                onListenFromText(book, fragment, fraction)
            },
            onListenLive = { book ->
                textViewModel.allowFollowing(readerSession)
                onListenLive(book)
            },
            audioBookId = audioBookId, audioPlaying = audioPlaying, audioBuffering = audioBuffering,
            audioError = audioError, audioSpeed = audioSpeed, onSetAudioSpeed = onSetAudioSpeed,
            onRuVoiceVoiceSelected = onRuVoiceVoiceSelected,
            onToggleAudio = onToggleAudio,
            onReaderFocusChanged = onReaderFocusChanged,
            onSaveAudio = textViewModel::saveAudio, onPauseAudio = textViewModel::pauseAudio,
            onResumeAudio = textViewModel::resumeAudio, onRemoveAudio = textViewModel::removeAudio,
            onPlaySavedAudio = { source ->
                textViewModel.allowFollowing(readerSession)
                onPlayDownloaded(source)
            },
        )
        return
    }
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val state by libraryViewModel.state.collectAsStateWithLifecycle()
    val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
    LaunchedEffect(state.history, downloads, textState.books) {
        val textIds = textState.books.map { it.id }.toSet()
        val legacy = legacyTextHistoryActivity(state.history, state.historyActivityTimes, downloads, textIds)
        textViewModel.restoreLegacyHistory(legacy)
    }
    val context = LocalContext.current

    var pendingStorageAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStorageBookSourceId by rememberSaveable { mutableStateOf<String?>(null) }

    val executeStorageAction: (String, String) -> Unit = { action, bookSourceId ->
        when (action) {
            "resume" -> libraryViewModel.resumeDownload(bookSourceId)
            "retry" -> libraryViewModel.retryDownload(bookSourceId)
            "remove" -> libraryViewModel.removeDownload(bookSourceId)
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingStorageAction
        val bookSourceId = pendingStorageBookSourceId
        pendingStorageAction = null
        pendingStorageBookSourceId = null
        if (granted && action != null && bookSourceId != null) {
            executeStorageAction(action, bookSourceId)
        } else if (!granted) {
            Toast.makeText(
                context,
                "Разрешите доступ к хранилищу для работы со скачанными файлами в папке Download.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val requestStorageAction: (String, String) -> Unit = { action, bookSourceId ->
        val permissionNeeded =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        if (permissionNeeded) {
            pendingStorageAction = action
            pendingStorageBookSourceId = bookSourceId
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            executeStorageAction(action, bookSourceId)
        }
    }

    LibraryScreenV2(
        state = state,
        entryKey = entryKey,
        downloads = downloads,
        onBook = onBook,
        onContinueBook = onContinueBook,
        onDownloadedBook = onDownloadedBook,
        onPlayDownloaded = onPlayDownloaded,
        onPlayBookmark = onPlayBookmark,
        onDeleteBookmark = libraryViewModel::removeBookmark,
        onSeries = onSeries,
        onRemoveHistory = libraryViewModel::removeHistory,
        onRemoveSeries = libraryViewModel::removeSeries,
        onPauseDownload = libraryViewModel::pauseDownload,
        onResumeDownload = { bookSourceId -> requestStorageAction("resume", bookSourceId) },
        onRetryDownload = { bookSourceId -> requestStorageAction("retry", bookSourceId) },
        onRemoveDownload = { bookSourceId -> requestStorageAction("remove", bookSourceId) },
        showProgressPercent = showProgressPercent,
        selectedTab = libraryTab,
        onSelectTab = { libraryTab = it },
        textLibrary = textState,
        onImportText = { textImporter.launch(arrayOf("*/*")) },
        onOpenText = {
            textBookId = it
        },
        onRemoveText = textViewModel::remove,
        onRemoveTextHistory = textViewModel::removeHistory,
        onRemoveTextSeries = textViewModel::removeSeries,
        onPlayText = { onPlayText(it, null) },
    )
}

