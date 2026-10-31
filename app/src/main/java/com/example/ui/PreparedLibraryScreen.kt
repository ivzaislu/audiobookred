package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onPlayText: (String, String?) -> Unit = { _, _ -> },
    onTextBook: (String) -> Unit,
) {
    val textViewModel: TextLibraryViewModel = hiltViewModel()
    val textState by textViewModel.state.collectAsStateWithLifecycle()
    var libraryTab by rememberSaveable(entryKey) { mutableStateOf(LibraryTabV2.History) }
    val context = LocalContext.current
    val textImporter = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }) { uri ->
        if (uri != null) textViewModel.requestImport(uri)
    }
    val importPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) textViewModel.chooseImportLocation(true)
        else Toast.makeText(context, "Для переноса книги разрешите запись в папку Download.", Toast.LENGTH_LONG).show()
    }
    if (textState.choosingImportLocation) {
        AlertDialog(
            onDismissRequest = textViewModel::cancelArchiveImport,
            title = { Text("Перенести файл книги?") },
            text = { Text("Перенести исходный файл в Download/AudioBookRed/Books? После удаления книги из библиотеки её файлы в этой папке тоже будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) importPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else textViewModel.chooseImportLocation(true)
                }) { Text("Перенести") }
            },
            dismissButton = {
                TextButton(onClick = { textViewModel.chooseImportLocation(false) }) { Text("Оставить на месте") }
            },
        )
    }
    if (textState.archiveEntries.isNotEmpty()) {
        TextArchiveSelectionDialog(textState.archiveEntries, textViewModel::chooseArchiveEntry, textViewModel::cancelArchiveImport)
    }
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val state by libraryViewModel.state.collectAsStateWithLifecycle()
    val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
    LaunchedEffect(state.history, downloads, textState.books) {
        val textIds = textState.books.map { it.id }.toSet()
        val legacy = legacyTextHistoryActivity(state.history, state.historyActivityTimes, downloads, textIds)
        textViewModel.restoreLegacyHistory(legacy)
    }

    var pendingStorageAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStorageBookSourceId by rememberSaveable { mutableStateOf<String?>(null) }

    val executeStorageAction: (String, String) -> Unit = { action, bookSourceId ->
        when (action) {
            "resume" -> libraryViewModel.resumeDownload(bookSourceId)
            "retry" -> libraryViewModel.retryDownload(bookSourceId)
            "remove" -> libraryViewModel.removeDownload(bookSourceId)
            "remove-text" -> textViewModel.remove(bookSourceId)
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
        onOpenText = onTextBook,
        onRemoveText = { bookId ->
            textViewModel.remove(bookId) { requestStorageAction("remove-text", bookId) }
        },
        onRemoveTextHistory = textViewModel::removeHistory,
        onRemoveTextSeries = textViewModel::removeSeries,
        onPlayText = { onPlayText(it, null) },
    )
}

