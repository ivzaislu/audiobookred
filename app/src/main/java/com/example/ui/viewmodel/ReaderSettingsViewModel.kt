package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.narration.RuVoiceTtsEngine
import com.example.data.settings.ReaderAppearance
import com.example.data.settings.ReaderFont
import com.example.data.settings.ReaderSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReaderSettingsViewModel @Inject internal constructor(
    private val store: ReaderSettingsStore,
    private val ruVoice: RuVoiceTtsEngine,
) : ViewModel() {
    val settings = store.state
    // Settings and narration share the app-scoped TTS connection.
    internal suspend fun ruVoiceVoiceNames(): List<String> = ruVoice.voices().map { it.name }
    fun setReadingAppearance(value: com.example.data.settings.ReaderSettings) = store.setReadingAppearance(value)
    fun setFontSize(value: Int) = store.setFontSize(value)
    fun setFont(value: ReaderFont) = store.setFont(value)
    fun setLineSpacing(value: Float) = store.setLineSpacing(value)
    fun setWideMargins(value: Boolean) = store.setWideMargins(value)
    fun setFollowNarration(value: Boolean) = store.setFollowNarration(value)
    fun setAppearance(value: ReaderAppearance) = store.setAppearance(value)
    fun setCompressSavedNarration(value: Boolean) = store.setCompressSavedNarration(value)
    fun setSavedNarrationBitrateKbps(value: Int) = store.setSavedNarrationBitrateKbps(value)
}
