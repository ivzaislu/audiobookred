package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
) {
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val state by libraryViewModel.state.collectAsStateWithLifecycle()
    val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
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
    )
}
