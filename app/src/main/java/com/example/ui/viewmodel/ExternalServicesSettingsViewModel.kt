package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.parser.RuTrackerCloudflareSession
import com.example.data.torrserve.TorrServeClient
import com.example.data.settings.ExternalServiceCredentialsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TorrServeConnectionUiState(
    val checking: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class ExternalServicesSettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialsStore: ExternalServiceCredentialsStore,
    private val torrServeClient: TorrServeClient,
) : ViewModel() {
    init {
        RuTrackerCloudflareSession.initialize(context.applicationContext)
    }

    val settings = credentialsStore.state

    private val _torrServeConnection = MutableStateFlow(TorrServeConnectionUiState())
    val torrServeConnection: StateFlow<TorrServeConnectionUiState> =
        _torrServeConnection.asStateFlow()

    fun saveRuTracker(login: String, newPassword: String?) {
        credentialsStore.saveRuTracker(
            login = login,
            newPassword = newPassword,
        )
        RuTrackerCloudflareSession.clearCookies()
    }

    fun clearRuTrackerPassword() {
        credentialsStore.clearRuTrackerPassword()
        RuTrackerCloudflareSession.clearCookies()
    }

    fun saveTorrServe(url: String, login: String, newPassword: String?) {
        credentialsStore.saveTorrServe(
            url = url,
            login = login,
            newPassword = newPassword,
        )
        _torrServeConnection.value = TorrServeConnectionUiState()
    }

    fun checkTorrServe() {
        if (_torrServeConnection.value.checking) return
        _torrServeConnection.value = TorrServeConnectionUiState(checking = true)
        viewModelScope.launch {
            try {
                val info = torrServeClient.checkConnection()
                _torrServeConnection.value = TorrServeConnectionUiState(
                    message = buildString {
                        append("Подключение успешно")
                        if (info.version.isNotBlank()) {
                            append(" · ")
                            append(info.version)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                _torrServeConnection.value = TorrServeConnectionUiState()
                throw cancelled
            } catch (error: Exception) {
                _torrServeConnection.value = TorrServeConnectionUiState(
                    message = error.message ?: "Не удалось проверить TorrServe.",
                    isError = true,
                )
            }
        }
    }

    fun clearTorrServePassword() {
        credentialsStore.clearTorrServePassword()
        _torrServeConnection.value = TorrServeConnectionUiState()
    }
}
