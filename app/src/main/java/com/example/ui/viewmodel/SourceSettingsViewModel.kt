package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.settings.SourceAvailabilityStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SourceSettingsViewModel @Inject constructor(
    private val store: SourceAvailabilityStore,
) : ViewModel() {
    val enabledSources = store.enabled

    fun setEnabled(sourceCode: String, enabled: Boolean) {
        store.setEnabled(sourceCode, enabled)
    }
}
