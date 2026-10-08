package com.example.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.ui.viewmodel.GeneralSettingsViewModel

/** Nav3-scoped host for standalone top-level settings. */
@Composable
internal fun PreparedSettingsScreen(
    onStorage: () -> Unit,
    onHome: () -> Unit,
    onPlayer: () -> Unit,
    onSources: () -> Unit,
    onExternalServices: () -> Unit,
) {
    val viewModel: GeneralSettingsViewModel = hiltViewModel()
    StandaloneSettingsScreen(
        vm = viewModel,
        onStorage = onStorage,
        onHome = onHome,
        onPlayer = onPlayer,
        onSources = onSources,
        onExternalServices = onExternalServices,
    )
}
