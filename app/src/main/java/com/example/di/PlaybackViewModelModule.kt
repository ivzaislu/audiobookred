package com.example.di

import android.content.Context
import com.example.data.local.DownloadStore
import com.example.data.local.LocalCacheStore
import com.example.data.player.AudiobookPlayerManager
import com.example.data.player.PlaybackResumeStore
import com.example.data.settings.PlayerSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped

/** Hilt-owned MediaController facade for the app playback ViewModel. */
@Module
@InstallIn(ViewModelComponent::class)
object PlaybackViewModelModule {
    @Provides
    @ViewModelScoped
    fun provideAudiobookPlayerManager(
        @ApplicationContext context: Context,
        resumeStore: PlaybackResumeStore,
        downloadStore: DownloadStore,
        cacheStore: LocalCacheStore,
        settingsStore: PlayerSettingsStore,
    ): AudiobookPlayerManager = AudiobookPlayerManager(
        context = context,
        resumeStore = resumeStore,
        downloadStore = downloadStore,
        cacheStore = cacheStore,
    ).also { player ->
        player.setDefaultSpeed(settingsStore.state.value.defaultSpeed)
    }
}
