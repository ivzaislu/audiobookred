package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.settings.ReaderAppearance
import com.example.data.settings.ReaderFont
import com.example.data.settings.ReaderSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReaderSettingsViewModel @Inject constructor(private val store: ReaderSettingsStore) : ViewModel() {
    val settings = store.state
    fun setFontSize(value: Int) = store.setFontSize(value)
    fun setFont(value: ReaderFont) = store.setFont(value)
    fun setLineSpacing(value: Float) = store.setLineSpacing(value)
    fun setWideMargins(value: Boolean) = store.setWideMargins(value)
    fun setFollowNarration(value: Boolean) = store.setFollowNarration(value)
    fun setAppearance(value: ReaderAppearance) = store.setAppearance(value)
}
