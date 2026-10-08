package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.settings.HomePopularDefaultPeriod
import com.example.data.settings.PlayerSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeSettingsViewModel @Inject constructor(
    private val settingsStore: PlayerSettingsStore,
) : ViewModel() {
    val settings = settingsStore.state

    fun setShowNew(value: Boolean) = settingsStore.setHomeShowNew(value)
    fun setShowPopular(value: Boolean) = settingsStore.setHomeShowPopular(value)
    fun setShowContinue(value: Boolean) = settingsStore.setHomeShowContinue(value)
    fun setShowDownloads(value: Boolean) = settingsStore.setHomeShowDownloads(value)
    fun setPopularDefaultPeriod(value: HomePopularDefaultPeriod) =
        settingsStore.setHomePopularDefaultPeriod(value)

    fun setCacheDays(value: Int) = settingsStore.setHomeCacheDays(value)
}
