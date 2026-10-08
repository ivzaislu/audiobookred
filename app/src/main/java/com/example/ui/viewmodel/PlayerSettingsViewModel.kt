package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.settings.PlayerSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Nav3-scoped owner for the Player settings destination. */
@HiltViewModel
class PlayerSettingsViewModel @Inject constructor(
    private val settingsStore: PlayerSettingsStore,
) : ViewModel() {
    val settings = settingsStore.state

    fun setDefaultSpeed(value: Float) = settingsStore.setDefaultSpeed(value)
    fun setRememberBookSpeed(value: Boolean) = settingsStore.setRememberBookSpeed(value)
    fun setRewindSeconds(value: Int) = settingsStore.setRewindSeconds(value)
    fun setForwardSeconds(value: Int) = settingsStore.setForwardSeconds(value)
    fun setAutoNextChapter(value: Boolean) = settingsStore.setAutoNextChapter(value)
    fun setSimplifyChapterTitles(value: Boolean) = settingsStore.setSimplifyChapterTitles(value)
    fun setShowProgressPercent(value: Boolean) = settingsStore.setShowProgressPercent(value)
    fun setSkipSilenceEnabled(value: Boolean) = settingsStore.setSkipSilenceEnabled(value)
    fun setSmartRewindAfterPause(value: Boolean) = settingsStore.setSmartRewindAfterPause(value)
}