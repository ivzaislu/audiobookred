package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.settings.PlayerSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Activity-scoped read owner for settings used by the app shell and player UI. */
@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    settingsStore: PlayerSettingsStore,
) : ViewModel() {
    val settings = settingsStore.state
}
