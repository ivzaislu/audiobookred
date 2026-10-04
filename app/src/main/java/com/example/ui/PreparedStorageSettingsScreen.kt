package com.example.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.ui.viewmodel.StorageSettingsViewModel

/** Nav3-scoped host for standalone local storage settings. */
@Composable
internal fun PreparedStorageSettingsScreen(
    onBack: () -> Unit,
) {
    val viewModel: StorageSettingsViewModel = hiltViewModel()
    StandaloneStorageSettingsScreen(vm = viewModel, onBack = onBack)
}
