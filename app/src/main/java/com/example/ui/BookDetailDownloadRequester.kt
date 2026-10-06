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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

internal enum class BookDownloadAction {
    Start,
    Resume,
    Retry,
}

@Composable
internal fun rememberBookDownloadRequester(
    onStart: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
): (BookDownloadAction, String) -> Unit {
    val context = LocalContext.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnResume by rememberUpdatedState(onResume)
    val currentOnRetry by rememberUpdatedState(onRetry)

    var pendingAction by rememberSaveable { mutableStateOf<BookDownloadAction?>(null) }
    var pendingBookSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }

    val executeDownload: (BookDownloadAction, String) -> Unit = { action, bookSourceId ->
        when (action) {
            BookDownloadAction.Start -> currentOnStart(bookSourceId)
            BookDownloadAction.Resume -> currentOnResume(bookSourceId)
            BookDownloadAction.Retry -> currentOnRetry(bookSourceId)
        }
    }

    fun consumePendingDownload(): Pair<BookDownloadAction, String>? {
        val action = pendingAction
        val bookSourceId = pendingBookSourceId
        pendingAction = null
        pendingBookSourceId = null
        return if (action != null && bookSourceId != null) {
            action to bookSourceId
        } else {
            null
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        consumePendingDownload()?.let { (action, bookSourceId) ->
            executeDownload(action, bookSourceId)
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = consumePendingDownload()
        if (granted) {
            pending?.let { (action, bookSourceId) ->
                executeDownload(action, bookSourceId)
            }
        } else {
            Toast.makeText(
                context,
                "Разрешите доступ к хранилищу, чтобы сохранять книги в папку Download.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    return remember(
        context,
        notificationPermissionRequested,
        notificationPermissionLauncher,
        storagePermissionLauncher,
        executeDownload,
    ) {
        { action, bookSourceId ->
            val legacyStoragePermissionNeeded =
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED
            val notificationPermissionNeeded =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED

            when {
                legacyStoragePermissionNeeded -> {
                    pendingAction = action
                    pendingBookSourceId = bookSourceId
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }

                notificationPermissionNeeded && !notificationPermissionRequested -> {
                    notificationPermissionRequested = true
                    pendingAction = action
                    pendingBookSourceId = bookSourceId
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> executeDownload(action, bookSourceId)
            }
        }
    }
}
