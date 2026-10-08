package com.example.di

import android.content.Context
import com.example.data.api.ApiClient
import com.example.data.download.AudiobookDownloadManager
import com.example.data.local.DownloadStore
import com.example.data.local.LibraryCacheStore
import com.example.data.local.ListeningStateStore
import com.example.data.local.LocalCacheStore
import com.example.data.local.NormalizedLibraryStore
import com.example.data.local.SeriesPageCacheStore
import com.example.data.player.PlaybackReadRepository
import com.example.data.player.PlaybackResumeStore
import com.example.data.repository.AudiobookRepository
import com.example.data.repository.LibraryRepository
import com.example.data.settings.BookSourcePreferenceStore
import com.example.data.settings.ExternalServiceCredentialsStore
import com.example.data.settings.PlayerSettingsStore
import com.example.data.settings.SourceAvailabilityStore
import com.example.data.torrserve.RuTrackerTorrServePlaybackResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PagingAudiobookRepository

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAudiobookRepository(
        ruTrackerTorrServeResolver: RuTrackerTorrServePlaybackResolver,
        sourceAvailabilityStore: SourceAvailabilityStore,
    ): AudiobookRepository = AudiobookRepository(
        ruTrackerTorrServeResolver = ruTrackerTorrServeResolver,
        sourceAvailabilityStore = sourceAvailabilityStore,
    )

    /**
     * Unscoped on purpose: Paging uses repository-local page buffers.
     * Provider.get() therefore creates exactly the lifetime requested by each VM.
     */
    @Provides
    @PagingAudiobookRepository
    fun providePagingAudiobookRepository(
        sourceAvailabilityStore: SourceAvailabilityStore,
    ): AudiobookRepository = AudiobookRepository(sourceAvailabilityStore = sourceAvailabilityStore)

    @Provides
    @Singleton
    fun provideLocalCacheStore(@ApplicationContext context: Context): LocalCacheStore =
        LocalCacheStore(context)

    @Provides
    @Singleton
    fun provideSeriesPageCacheStore(@ApplicationContext context: Context): SeriesPageCacheStore =
        SeriesPageCacheStore(context)

    @Provides
    @Singleton
    fun provideLibraryCacheStore(
        @ApplicationContext context: Context,
        cacheStore: LocalCacheStore,
        normalizedStore: NormalizedLibraryStore,
    ): LibraryCacheStore = LibraryCacheStore(
        context = context,
        cacheStore = cacheStore,
        normalizedStore = normalizedStore,
    )

    @Provides
    @Singleton
    fun provideDownloadStore(
        @ApplicationContext context: Context,
        cacheStore: LocalCacheStore,
    ): DownloadStore = DownloadStore(context, cacheStore)

    @Provides
    @Singleton
    fun providePlaybackResumeStore(@ApplicationContext context: Context): PlaybackResumeStore =
        PlaybackResumeStore(context)

    @Provides
    @Singleton
    fun provideListeningStateStore(
        @ApplicationContext context: Context,
        resumeStore: PlaybackResumeStore,
    ): ListeningStateStore = ListeningStateStore(context, resumeStore)

    @Provides
    @Singleton
    fun providePlaybackReadRepository(
        @ApplicationContext context: Context,
        cacheStore: LocalCacheStore,
        resumeStore: PlaybackResumeStore,
    ): PlaybackReadRepository = PlaybackReadRepository(context, cacheStore, resumeStore)

    @Provides
    @Singleton
    fun providePlayerSettingsStore(@ApplicationContext context: Context): PlayerSettingsStore =
        PlayerSettingsStore(context)

    @Provides
    @Singleton
    fun provideSourceAvailabilityStore(@ApplicationContext context: Context): SourceAvailabilityStore =
        SourceAvailabilityStore(context)

    @Provides
    @Singleton
    fun provideBookSourcePreferenceStore(@ApplicationContext context: Context): BookSourcePreferenceStore =
        BookSourcePreferenceStore(context)

    @Provides
    @Singleton
    fun provideExternalServiceCredentialsStore(
        @ApplicationContext context: Context,
    ): ExternalServiceCredentialsStore = ExternalServiceCredentialsStore(context)

    @Provides
    @Singleton
    fun provideLibraryRepository(
        libraryCacheStore: LibraryCacheStore,
        cacheStore: LocalCacheStore,
        downloadStore: DownloadStore,
        resumeStore: PlaybackResumeStore,
        listeningStateStore: ListeningStateStore,
    ): LibraryRepository = LibraryRepository(
        local = libraryCacheStore,
        cacheStore = cacheStore,
        downloadStore = downloadStore,
        resumeStore = resumeStore,
        listeningStateStore = listeningStateStore,
    )

    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        repository: AudiobookRepository,
        store: DownloadStore,
    ): AudiobookDownloadManager = AudiobookDownloadManager(
        context = context,
        repository = repository,
        store = store,
    )

    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadHttpClient(): OkHttpClient =
        ApiClient.createHttpClient(readTimeoutSeconds = 120L)
            .newBuilder()
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host.lowercase()
                val builder = original.newBuilder()
                val referer = original.header("Referer").orEmpty()
                when {
                    host == "uknig.com" || host.endsWith(".uknig.com") ->
                        builder.header("Referer", referer.ifBlank { "https://uknig.com/" })
                    "uknig.com" in referer.lowercase() ->
                        builder.removeHeader("Referer")
                    host == "audiopolka.club" || host.endsWith(".audiopolka.club") ->
                        builder.header("Referer", referer.ifBlank { "https://audiopolka.club" })
                }
                chain.proceed(builder.build())
            }
            .build()
}
