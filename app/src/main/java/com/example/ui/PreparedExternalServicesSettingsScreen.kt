package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.ExternalServicesSettingsViewModel

@Composable
internal fun PreparedExternalServicesSettingsScreen(
    onBack: () -> Unit,
) {
    val vm: ExternalServicesSettingsViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val torrServeConnection by vm.torrServeConnection.collectAsStateWithLifecycle()

    var rutrackerLogin by rememberSaveable(settings.rutrackerLogin) {
        mutableStateOf(settings.rutrackerLogin)
    }
    var rutrackerPassword by rememberSaveable { mutableStateOf("") }
    var showRuTrackerPassword by rememberSaveable { mutableStateOf(false) }

    var torrServeUrl by rememberSaveable(settings.torrServeUrl) {
        mutableStateOf(settings.torrServeUrl)
    }
    var torrServeLogin by rememberSaveable(settings.torrServeLogin) {
        mutableStateOf(settings.torrServeLogin)
    }
    var torrServePassword by rememberSaveable { mutableStateOf("") }
    var showTorrServePassword by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "external-services-header") {
            AbredSubpageHeader(
                title = "RuTracker и TorrServe",
                onBack = onBack,
            )
        }

        item { AbredSettingsSectionTitle("RuTracker") }
        item {
            RuTrackerSettingsCard(
                login = rutrackerLogin,
                onLoginChange = { rutrackerLogin = it },
                password = rutrackerPassword,
                onPasswordChange = { rutrackerPassword = it },
                passwordVisible = showRuTrackerPassword,
                onTogglePasswordVisible = { showRuTrackerPassword = !showRuTrackerPassword },
                passwordSaved = settings.rutrackerPasswordSaved,
                onSave = {
                    vm.saveRuTracker(
                        login = rutrackerLogin,
                        newPassword = rutrackerPassword.takeIf(String::isNotBlank),
                    )
                    rutrackerPassword = ""
                },
                onClearPassword = {
                    vm.clearRuTrackerPassword()
                    rutrackerPassword = ""
                },
            )
        }

        item { AbredSettingsSectionTitle("TorrServe") }
        item {
            TorrServeSettingsCard(
                url = torrServeUrl,
                onUrlChange = { torrServeUrl = it },
                login = torrServeLogin,
                onLoginChange = { torrServeLogin = it },
                password = torrServePassword,
                onPasswordChange = { torrServePassword = it },
                passwordVisible = showTorrServePassword,
                onTogglePasswordVisible = { showTorrServePassword = !showTorrServePassword },
                passwordSaved = settings.torrServePasswordSaved,
                connectionChecking = torrServeConnection.checking,
                connectionMessage = torrServeConnection.message,
                connectionIsError = torrServeConnection.isError,
                onSave = {
                    vm.saveTorrServe(
                        url = torrServeUrl,
                        login = torrServeLogin,
                        newPassword = torrServePassword.takeIf(String::isNotBlank),
                    )
                    torrServePassword = ""
                },
                onClearPassword = {
                    vm.clearTorrServePassword()
                    torrServePassword = ""
                },
                onSaveAndCheck = {
                    vm.saveTorrServe(
                        url = torrServeUrl,
                        login = torrServeLogin,
                        newPassword = torrServePassword.takeIf(String::isNotBlank),
                    )
                    torrServePassword = ""
                    vm.checkTorrServe()
                },
            )
        }

        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}
