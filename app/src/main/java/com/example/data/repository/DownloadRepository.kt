package com.example.data.repository

import android.content.Context
import com.example.data.download.AudiobookDownloadManager
import com.example.data.download.DownloadScheduler
import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.player.PlaybackReadRepository
import com.example.data.player.PlaybackSessionCommands
import com.example.data.settings.PlayerSettingsStore
import com.example.util.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Durable download commands shared by screen-level owners. */
@Singleton
class DownloadRepository @Inject constructor(
    private val downloadManager: AudiobookDownloadManager,
    private val downloadStore: DownloadStore,
    @ApplicationContext context: Context,
    private val settingsStore: PlayerSettingsStore,
    private val playback: PlaybackReadRepository,
    private val playbackCommands: PlaybackSessionCommands,
) {
    private val appContext = context.applicationContext

    suspend fun start(bookSourceId: String): DownloadBookEntity =
        downloadManager.start(bookSourceId, settingsStore.state.value.downloadWifiOnly)

    suspend fun pause(bookSourceId: String) {
        downloadManager.pause(bookSourceId)
    }

    suspend fun resume(bookSourceId: String) {
        downloadManager.resume(bookSourceId, settingsStore.state.value.downloadWifiOnly)
    }

    suspend fun retry(bookSourceId: String): DownloadBookEntity =
        downloadManager.retry(bookSourceId, settingsStore.state.value.downloadWifiOnly)

    suspend fun remove(bookSourceId: String): DownloadBookEntity? {
        val activeBookSourceId = runCatchingCancellable {
            playback.current()?.book?.selectedBookSourceId
        }.getOrNull()
        if (activeBookSourceId == bookSourceId) {
            playbackCommands.pause()
        }

        // Current deletion is synchronous: stop the active generation before
        // deleting its Room rows and files.
        DownloadScheduler.cancel(appContext, bookSourceId)
        return downloadStore.markPendingDelete(bookSourceId)
    }
}
