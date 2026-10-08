package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.UserDataExportManager
import com.example.data.backup.UserDataImportManager
import com.example.data.download.AudiobookDownloadManager
import com.example.data.settings.AppThemeMode
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
class GeneralSettingsViewModel @Inject constructor(
    private val settingsStore: PlayerSettingsStore,
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
        sourceAvailabilityStore.reset()
        val wifiOnly = settingsStore.state.value.downloadWifiOnly
        viewModelScope.launch {
            downloadManager.updateNetworkPolicy(wifiOnly)
        }
    }
}
