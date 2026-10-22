package com.example.di

import com.example.data.api.ApiClient
import com.example.data.textsource.FantasyWorldsProvider
import com.example.data.textsource.RemoteTextSourceRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object TextSourcesModule {
    @Provides
    @Singleton
    fun fantasyWorldsProvider(): FantasyWorldsProvider = FantasyWorldsProvider(ApiClient.createHttpClient())

    @Provides
    @Singleton
    fun textSourceRegistry(fantasyWorlds: FantasyWorldsProvider): RemoteTextSourceRegistry =
        RemoteTextSourceRegistry(listOf(fantasyWorlds))
}
