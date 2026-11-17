package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.example.data.textbook.TextLibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.BuildConfig
import com.example.data.storage.PublicAppDocumentStorage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.UserDataExportManager
import com.example.data.backup.UserDataImportManager
import com.example.data.download.AudiobookDownloadManager
import com.example.data.settings.AppThemeMode
import com.example.data.settings.ReaderSettingsStore
import com.example.data.settings.PlayerSettingsStore
import com.example.data.settings.SourceAvailabilityStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserDataExportUiState(
    val exporting: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class UserDataImportUiState(
    val importing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/** Nav3-scoped owner for the top-level Settings destination. */
@HiltViewModel
internal class GeneralSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textLibrary: TextLibraryRepository,
    private val settingsStore: PlayerSettingsStore,
    private val readerSettingsStore: ReaderSettingsStore,
    private val sourceAvailabilityStore: SourceAvailabilityStore,
    private val downloadManager: AudiobookDownloadManager,
    private val userDataExportManager: UserDataExportManager,
    private val userDataImportManager: UserDataImportManager,
) : ViewModel() {
    val settings = settingsStore.state

    private val _exportState = MutableStateFlow(UserDataExportUiState())
    val exportState: StateFlow<UserDataExportUiState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow(UserDataImportUiState())
    val importState: StateFlow<UserDataImportUiState> = _importState.asStateFlow()

    data class TextArchiveState(
        val busy: Boolean = false,
        val restoring: Boolean = false,
        val isError: Boolean = false,
        val preview: String? = null,
        val message: String? = null,
    )

    private val _textArchive = MutableStateFlow(TextArchiveState())
    val textArchive = _textArchive.asStateFlow()
    private var preparedArchive: File? = null

    fun prepareTextArchive() = runTextArchiveOperation {
        val file = withContext(Dispatchers.IO) { File.createTempFile("text-library-", ".zip", context.cacheDir) }
        preparedArchive = file
        val count = textLibrary.exportArchive(file)
        TextArchiveState(preview = "$count книг · ${file.length()} байт\nИсходники, обложки, оглавления и позиции чтения.")
    }

    fun discardTextArchive() {
        preparedArchive?.delete()
        preparedArchive = null
        _textArchive.value = TextArchiveState()
    }

    fun saveTextArchive() = runTextArchiveOperation {
        val file = checkNotNull(preparedArchive)
        val stored = withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val coroutine = currentCoroutineContext()
            PublicAppDocumentStorage(context).writeBackup(
                displayName = "AudioBookRed-" + BuildConfig.VERSION_NAME + "-text-library-" + timestamp + ".zip",
                mimeType = "application/zip",
                file = file,
                checkpoint = { coroutine.ensureActive() },
            )
        }
        discardTextArchive()
        TextArchiveState(message = "Архив текстовых книг сохранён.\nСохранено: ${stored.relativePath}")
    }

    fun restoreTextArchive(uri: Uri) = runTextArchiveOperation(restoring = true) {
        TextArchiveState(message = "Добавлено текстовых книг: ${textLibrary.restoreArchive(uri)}.")
    }

    private fun runTextArchiveOperation(
        restoring: Boolean = false,
        operation: suspend () -> TextArchiveState,
    ) {
        if (_textArchive.value.busy || _exportState.value.exporting || _importState.value.importing) return
        _textArchive.value = TextArchiveState(busy = true, restoring = restoring)
        viewModelScope.launch {
            try {
                _textArchive.value = operation().copy(restoring = restoring)
            } catch (cancelled: CancellationException) {
                discardTextArchive()
                throw cancelled
            } catch (error: Exception) {
                discardTextArchive()
                _textArchive.value = TextArchiveState(
                    restoring = restoring,
                    isError = true,
                    message = "Не удалось обработать архив текстовых книг.",
                )
            }
        }
    }

    override fun onCleared() {
        preparedArchive?.delete()
        super.onCleared()
    }

    fun setPinBottomNavigation(value: Boolean) = settingsStore.setPinBottomNavigation(value)

    fun setThemeMode(value: AppThemeMode) = settingsStore.setThemeMode(value)

    fun exportUserData() {
        if (_exportState.value.exporting || _importState.value.importing) return
        _exportState.value = UserDataExportUiState(exporting = true)
        viewModelScope.launch {
            try {
                val result = userDataExportManager.exportToPublicBackup()
                _exportState.value = UserDataExportUiState(
                    message = buildString {
                        append("Готово: ")
                        append(result.favoriteBooks)
                        append(" избранных · ")
                        append(result.historyBooks)
                        append(" в истории · ")
                        append(result.bookmarks)
                        append(" закладок · ")
                        append(result.progressEntries.coerceAtLeast(result.checkpoints))
                        append(" позиций")
                        result.location?.let { location ->
                            append("\nСохранено: ")
                            append(location)
                        }
                    }
                )
            } catch (cancelled: CancellationException) {
                _exportState.value = UserDataExportUiState()
                throw cancelled
            } catch (error: Exception) {
                _exportState.value = UserDataExportUiState(
                    message = "Не удалось сохранить резервную копию: " +
                        (error.message ?: "неизвестная ошибка"),
                    isError = true,
                )
            }
        }
    }

    fun importUserData(uri: Uri) {
        if (_importState.value.importing || _exportState.value.exporting) return
        _importState.value = UserDataImportUiState(importing = true)
        viewModelScope.launch {
            try {
                val result = userDataImportManager.import(uri)
                downloadManager.updateNetworkPolicy(settingsStore.state.value.downloadWifiOnly)
                _importState.value = UserDataImportUiState(
                    message = buildString {
                        append("Восстановлено: ")
                        append(result.favoriteBooks)
                        append(" избранных · ")
                        append(result.historyBooks)
                        append(" в истории · ")
                        append(result.bookmarks)
                        append(" закладок · ")
                        append(result.progressEntries.coerceAtLeast(result.checkpoints))
                        append(" позиций")
                    }
                )
            } catch (cancelled: CancellationException) {
                _importState.value = UserDataImportUiState()
                throw cancelled
            } catch (error: Exception) {
                _importState.value = UserDataImportUiState(
                    message = "Не удалось восстановить резервную копию: ${error.message ?: "неизвестная ошибка"}",
                    isError = true,
                )
            }
        }
    }

    fun resetSettings() {
        settingsStore.reset()
        readerSettingsStore.reset()
        sourceAvailabilityStore.reset()
        val wifiOnly = settingsStore.state.value.downloadWifiOnly
        viewModelScope.launch {
            downloadManager.updateNetworkPolicy(wifiOnly)
        }
    }
}
